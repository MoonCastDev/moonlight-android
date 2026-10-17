package com.limelight.xbox

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Xbox Remote Play over Google's WebRTC (libwebrtc, the library Chrome and XStreaming's default player use): hardware
 * decoding through MediaCodec, Google's jitter buffer, congestion control and loss recovery. The Xbox protocol on top
 * (session signalling, the input/control/message/chat channels and the input packet format) follows XStreaming
 * (github.com/Geocld/XStreaming, AGPL-3.0): src/webrtc and nano-rs.
 *
 * Lifecycle: [start] runs the session on a worker thread; video goes to [videoSink]; [stop] ends it.
 */
class XboxRtc(
    private val ctx: Context,
    private val eglBase: EglBase,
    private val consoleId: String,
    private val baseUri: String,
    private val gsToken: String,
    private val connectToken: String,
    /** 720 = "android", 1080 = "windows", 1081 = "tizen": the device type the Xbox sets quality from. */
    private val resolution: Int,
    private val language: String,
    private val coop: Boolean,
    /** Settings, Xbox, Bitrate: a cap in Mbps (0 = let the Xbox decide), sent as b=AS in the offer like XStreaming. */
    private val maxMbps: Int,
    /** Settings, Video, Ultra low latency: Artemis' vendor decoder keys on top of Moonlight's. */
    private val ultraLowLatency: Boolean,
    private val videoSink: VideoSink,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onConnected()
        fun onFailed(reason: String)
        fun onRumble(gamepad: Int, weak: Double, strong: Double, durationMs: Int)
    }

    /** One controller's state for one input packet. Axes -1..1 (raw Android: Y down is positive), triggers 0..1. */
    class PadFrame(val index: Int) {
        var nexus = false; var menu = false; var view = false
        var a = false; var b = false; var x = false; var y = false
        var up = false; var down = false; var left = false; var right = false
        var lb = false; var rb = false; var ls = false; var rs = false
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f; var lt = 0f; var rt = 0f
    }

    companion object {
        private const val TAG = "MoonCast-XboxRtc"
        private const val ACCESS_KEY = "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"
        private const val NANO_VERSION = "V3;WebrtcTransport.dll"
        @Volatile private var factoryReady = false

        private val ICE_SERVERS = listOf(
            "stun:worldaz.relay.teams.microsoft.com:3478", "stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
    }

    private val worker = HandlerThread("XboxRtc").apply { start() }
    private val work = Handler(worker.looper)
    @Volatile private var closed = false
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private val channels = HashMap<String, DataChannel>()
    private var sessionPath: String? = null
    private val localCandidates = ArrayList<IceCandidate>()
    private val gatheringDone = CountDownLatch(1)
    @Volatile private var inputStarted = false
    private val inputSequence = AtomicInteger(0)
    private val messageCv = AtomicInteger(0)
    private var videoTrackSeen = false
    private var audioTrackSeen = false
    private var keyframeAsked = false
    @Volatile var connected = false; private set

    /** Latest state of each pad, sent 62.5 times a second once the input channel is ready (XStreaming's poll rate). */
    @Volatile var pads: List<PadFrame> = emptyList()

    // ------------------------------------------------------------------ lifecycle

    fun start() = work.post { try { run() } catch (e: Exception) { if (!closed) { Log.w(TAG, "session failed", e); listener.onFailed(e.message ?: e.javaClass.simpleName) } } }

    fun stop() {
        if (closed) return
        closed = true
        work.removeCallbacksAndMessages(null)
        work.post {
            try { sessionPath?.let { http("DELETE", it, null) } } catch (e: Exception) { }
            try { channels.values.forEach { it.unregisterObserver(); it.close() } } catch (e: Exception) { }
            try { pc?.close() } catch (e: Exception) { }
            try { pc?.dispose() } catch (e: Exception) { }
            try { factory?.dispose() } catch (e: Exception) { }
            worker.quitSafely()
        }
    }

    // ------------------------------------------------------------------ signalling (xHome: /v5/sessions/home/...)

    private fun osName() = when (resolution) { 1080 -> "windows"; 1081, 1440 -> "tizen"; else -> "android" }

    private fun http(method: String, url: String, body: String?, deviceInfo: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer $gsToken")
            c.setRequestProperty("Content-Type", "application/json")
            if (method == "POST") c.setRequestProperty("Accept", "application/json")
            deviceInfo?.let { c.setRequestProperty("X-MS-Device-Info", it) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${url.substringAfterLast('/')}: ${text.take(160)}")
            return text
        } finally { c.disconnect() }
    }

    private fun deviceInfo() = JSONObject()
        .put("appInfo", JSONObject().put("env", JSONObject().put("clientAppId", "www.xbox.com").put("clientAppType", "browser")
            .put("clientAppVersion", "29.9.35").put("clientSdkVersion", "10.6.8").put("httpEnvironment", "prod").put("sdkInstallId", "")))
        .put("dev", JSONObject()
            .put("hw", JSONObject().put("make", "Microsoft").put("model", "unknown").put("platformType", "desktop").put("sdktype", "web"))
            .put("os", JSONObject().put("name", osName()).put("ver", "22631.2715").put("platform", "desktop"))
            .put("displayInfo", JSONObject().put("dimensions", JSONObject().put("widthInPixels", 4096).put("heightInPixels", 2160))
                .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1)))
            .put("browser", JSONObject().put("browserName", "edge").put("browserVersion", "140.0.3485.66")))
        .toString()

    private fun run() {
        val base = baseUri.trimEnd('/')
        listener.onStatus("Starting a session on your Xbox…")
        val play = JSONObject(http("POST", "$base/v5/sessions/home/play", JSONObject()
            .put("clientSessionId", "").put("titleId", "").put("systemUpdateGroup", "")
            .put("settings", JSONObject().put("nanoVersion", NANO_VERSION).put("enableTextToSpeech", false).put("highContrast", 0)
                .put("locale", language).put("useIceConnection", false).put("timezoneOffsetMinutes", 240).put("sdkType", "web")
                .put("osName", osName()))
            .put("serverId", consoleId).put("fallbackRegionNames", JSONArray()).toString(), deviceInfo()))
        val id = play.getString("sessionPath").split('/').getOrNull(3) ?: throw IllegalStateException("No session id")
        val path = "$base/v5/sessions/home/$id"
        sessionPath = path

        // Wait until the console has the session ready ("ReadyToConnect" wants the user token first)
        var connectSent = false
        while (!closed) {
            val st = JSONObject(http("GET", "$path/state", null))
            when (val s = st.optString("state")) {
                "Provisioned" -> break
                "ReadyToConnect" -> if (!connectSent) {
                    http("POST", "$path/connect", JSONObject().put("userToken", connectToken).toString()); connectSent = true
                }
                "Failed" -> throw IllegalStateException(st.optJSONObject("errorDetails")?.optString("message") ?: "The Xbox couldn't start the session")
                else -> Log.i(TAG, "state $s")
            }
            Thread.sleep(1000)
        }
        if (closed) return
        val pulse = try { JSONObject(http("GET", "$path/configuration", null)).optInt("keepAlivePulseInSeconds", 20) } catch (e: Exception) { 20 }

        listener.onStatus("Connecting…")
        createPeer()
        val offer = createOffer()
        http("POST", "$path/sdp", JSONObject().put("messageType", "offer").put("sdp", offer.description)
            .put("configuration", JSONObject()
                .put("chatConfiguration", JSONObject().put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                    .put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                    .put("numChannels", 1).put("sampleFrequencyHz", 24000))
                .put("chat", range(1, 1)).put("control", range(1, 3)).put("input", range(1, 8)).put("message", range(1, 1)))
            .toString())

        var answer: String? = null
        while (!closed && answer == null) {
            answer = sdpFrom(http("GET", "$path/sdp", null))
            if (answer == null) Thread.sleep(1000)
        }
        if (closed) return
        // what the Xbox agreed to: media sections, directions and the video codec it picked
        answer!!.replace("\r", "").split("\n").filter { it.startsWith("m=") || it == "a=sendonly" || it == "a=recvonly" ||
                it == "a=sendrecv" || it == "a=inactive" || it.startsWith("a=rtpmap") && (it.contains("H264") || it.contains("opus")) }
            .forEach { Log.i(TAG, "answer $it") }
        setRemote(answer)

        // Local candidates (gathering finishes quickly on a LAN; don't wait forever)
        gatheringDone.await(5, TimeUnit.SECONDS)
        val cands = JSONArray()
        synchronized(localCandidates) {
            localCandidates.filterNot { it.sdp.contains("UDP") && it.sdp.contains("tcptype") }.forEach {
                cands.put(JSONObject().put("candidate", it.sdp.removePrefix("a=")).put("messageType", "iceCandidate")
                    .put("sdpMLineIndex", it.sdpMLineIndex.toString()).put("sdpMid", it.sdpMid ?: "0"))
            }
        }
        http("POST", "$path/ice", JSONObject().put("messageType", "iceCandidate").put("candidate", cands).toString())

        var remote: JSONArray? = null
        while (!closed && (remote == null || remote.length() == 0)) {
            remote = iceFrom(http("GET", "$path/ice", null))
            if (remote == null || remote.length() == 0) Thread.sleep(1000)
        }
        if (closed) return
        val list = (0 until remote!!.length()).map { remote.getJSONObject(it) }
        for (o in expandTeredo(list)) {
            val cand = o.optString("candidate").trim()
            if (cand.isBlank() || cand == "a=end-of-candidates" || (cand.contains("UDP") && cand.contains("tcptype"))) continue
            pc?.addIceCandidate(IceCandidate(o.optString("sdpMid", "0"), o.optString("sdpMLineIndex", "0").toIntOrNull() ?: 0,
                cand.removePrefix("a=")))
        }
        Log.i(TAG, "remote candidates ${list.size}")
        if (!connectSent) http("POST", "$path/connect", JSONObject().put("userToken", connectToken).toString())

        // Keep the session alive for as long as we play
        val keep = object : Runnable {
            override fun run() {
                if (closed) return
                try { http("POST", "$path/keepalive", "{}") } catch (e: Exception) { Log.w(TAG, "keepalive: $e") }
                work.postDelayed(this, maxOf(5, pulse) * 1000L)
            }
        }
        work.postDelayed(keep, maxOf(5, pulse) * 1000L)
    }

    /**
     * The Xbox often offers a Teredo IPv6 address (2001:0::/32). Add the IPv4 host candidates hidden inside it (Teredo
     * server and the client's mapped address and port, plus the Xbox game port 3074), as XStreaming does for xHome, so
     * an IPv4-only network can still reach the console.
     */
    private fun expandTeredo(list: List<JSONObject>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        list.forEachIndexed { i, o ->
            out.add(o)
            val tok = o.optString("candidate").trim().split(Regex("\\s+"))
            val ipIdx = tok.indexOfFirst { it.startsWith("2001:0:") || it.startsWith("2001:0000:") }
            if (ipIdx < 0) return@forEachIndexed
            val addr = try { java.net.InetAddress.getByName(tok[ipIdx]).address } catch (e: Exception) { return@forEachIndexed }
            if (addr.size != 16) return@forEachIndexed
            fun v4(bs: List<Int>) = bs.joinToString(".")
            val server4 = v4((4..7).map { addr[it].toInt() and 0xff })
            val client4 = v4((12..15).map { (addr[it].toInt() and 0xff) xor 0xff })
            val udp = (((addr[10].toInt() and 0xff) shl 8) or (addr[11].toInt() and 0xff)) xor 0xffff
            val port = tok.getOrNull(ipIdx + 1)?.toIntOrNull() ?: 9002
            val adds = mutableListOf(client4 to udp, server4 to port, client4 to port)
            if (port != 3074) { adds += server4 to 3074; adds += client4 to 3074 }
            adds.forEachIndexed { k, (ip, pt) ->
                out.add(JSONObject().put("candidate", "a=candidate:${i + 2 + k} 1 UDP 100 $ip $pt typ host ")
                    .put("sdpMid", o.optString("sdpMid", "0")).put("sdpMLineIndex", o.optString("sdpMLineIndex", "0")))
            }
        }
        return out
    }

    private fun range(min: Int, max: Int) = JSONObject().put("minVersion", min).put("maxVersion", max)

    /** The answer SDP, or null while the console hasn't answered yet. */
    private fun sdpFrom(body: String): String? = try {
        val j = JSONObject(body)
        val ex = j.opt("exchangeResponse")
        when {
            ex is String -> try { JSONObject(ex).optString("sdp").ifBlank { ex } } catch (e: Exception) { ex }
            ex is JSONObject -> ex.optString("sdp").ifBlank { null }
            else -> j.optString("sdp").ifBlank { null }
        }
    } catch (e: Exception) { null }

    private fun iceFrom(body: String): JSONArray? = try {
        val j = JSONObject(body)
        when (val ex = j.opt("exchangeResponse") ?: j.opt("candidate")) {
            is String -> JSONArray(ex)
            is JSONArray -> ex
            else -> null
        }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ peer connection

    private fun createPeer() {
        if (!factoryReady) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            factoryReady = true
        }
        val adm = JavaAudioDeviceModule.builder(ctx.applicationContext).setUseStereoOutput(true).createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(XboxDecoderFactory(eglBase.eglBaseContext, ultraLowLatency))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, false, false))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        adm.release()
        factory = f
        val cfg = PeerConnection.RTCConfiguration(ICE_SERVERS.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val p = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("Couldn't create the connection")
        pc = p
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        // Same channels and protocols as XStreaming's player
        open(p, "input", "1.0")
        open(p, "chat", "chatV1")
        open(p, "control", "controlV1")
        open(p, "message", "messageV1")
    }

    private fun open(p: PeerConnection, name: String, protocol: String) {
        val ch = p.createDataChannel(name, DataChannel.Init().apply { ordered = true; this.protocol = protocol }) ?: return
        channels[name] = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                if (ch.state() == DataChannel.State.OPEN) work.post { onChannelOpen(name) }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                work.post { onChannelMessage(name, bytes, buffer.binary) }
            }
        })
    }

    private fun createOffer(): SessionDescription {
        val p = pc!!
        var made: SessionDescription? = null
        var error: String? = null
        val l1 = CountDownLatch(1)
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) { made = d; l1.countDown() }
            override fun onCreateFailure(e: String?) { error = e; l1.countDown() }
        }, MediaConstraints())
        l1.await(10, TimeUnit.SECONDS)
        val offer = made ?: throw IllegalStateException("Offer failed: $error")
        val munged = SessionDescription(offer.type, capBitrate(stereo(offer.description)))
        val l2 = CountDownLatch(1)
        p.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { l2.countDown() }
            override fun onSetFailure(e: String?) { error = e; l2.countDown() }
        }, munged)
        l2.await(10, TimeUnit.SECONDS)
        return munged
    }

    private fun setRemote(sdp: String) {
        val l = CountDownLatch(1)
        var error: String? = null
        pc!!.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { l.countDown() }
            override fun onSetFailure(e: String?) { error = e; l.countDown() }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        l.await(10, TimeUnit.SECONDS)
        error?.let { throw IllegalStateException("Answer rejected: $it") }
    }

    /** XStreaming's _setBitrate: a b=AS line (kbps, Mbps x 1024) in the video section, after its i= / c= lines. */
    private fun capBitrate(sdp: String): String {
        if (maxMbps <= 0) return sdp
        val lines = sdp.replace("\r", "").split("\n").toMutableList()
        val m = lines.indexOfFirst { it.startsWith("m=video") }
        if (m < 0) return sdp
        var i = m + 1
        while (i < lines.size && (lines[i].startsWith("i=") || lines[i].startsWith("c="))) i++
        val line = "b=AS:${maxMbps * 1024}"
        if (i < lines.size && lines[i].startsWith("b=AS:")) lines[i] = line else lines.add(i, line)
        Log.i(TAG, "video capped at $maxMbps Mbps")
        return lines.joinToString("\r\n")
    }

    /** Opus in stereo with in-band FEC, as XStreaming does (forceStereoAudio). */
    private fun stereo(sdp: String): String {
        val lines = sdp.replace("\r", "").split("\n").toMutableList()
        val opus = lines.firstOrNull { it.startsWith("a=rtpmap:") && it.lowercase().contains(" opus/") }
            ?.removePrefix("a=rtpmap:")?.substringBefore(' ') ?: return sdp
        val i = lines.indexOfFirst { it.startsWith("a=fmtp:$opus ") }
        if (i < 0) return sdp
        val keep = lines[i].removePrefix("a=fmtp:$opus ").split(';').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("stereo=") && !it.startsWith("minptime=") && !it.startsWith("useinbandfec=") }
        lines[i] = "a=fmtp:$opus " + (keep + listOf("stereo=1", "minptime=10", "useinbandfec=1")).joinToString(";")
        return lines.joinToString("\r\n")
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { Log.i(TAG, "ice $s") }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.countDown()
        }
        override fun onIceCandidate(c: IceCandidate) { synchronized(localCandidates) { localCandidates.add(c) } }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {
            val t = r.track() ?: return
            Log.i(TAG, "track ${t.kind()} arrived")
            work.post {
                if (t is VideoTrack) { t.addSink(videoSink); videoTrackSeen = true } else audioTrackSeen = true
                if (videoTrackSeen && audioTrackSeen && !keyframeAsked) { keyframeAsked = true; askKeyframe() }
            }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection $s")
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connected = true; listener.onConnected() }
                PeerConnection.PeerConnectionState.FAILED -> if (!closed) listener.onFailed("The connection to the Xbox dropped")
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ channels

    private fun sendText(name: String, text: String) {
        channels[name]?.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
    }

    private fun onChannelOpen(name: String) {
        Log.i(TAG, "channel $name open")
        if (name == "message") sendText("message", JSONObject().put("type", "Handshake").put("version", "messageV1")
            .put("id", UUID.randomUUID().toString()).put("cv", messageCv.getAndIncrement().toString()).toString())
    }

    private fun onChannelMessage(name: String, data: ByteArray, binary: Boolean) {
        when (name) {
            "message" -> {
                val j = try { JSONObject(String(data)) } catch (e: Exception) { return }
                if (j.optString("type") == "HandshakeAck" && !inputStarted) onHandshake()
            }
            "input" -> parseInput(data)
        }
    }

    /** The Xbox accepted us: authorise, announce the pads, start input and send the client description. */
    private fun onHandshake() {
        Log.i(TAG, "message handshake acked")
        sendText("control", JSONObject().put("message", "authorizationRequest").put("accessKey", ACCESS_KEY).toString())
        val slots = if (coop) listOf(0, 1) else listOf(0)
        slots.forEach { sendText("control", gamepadChanged(it, false)) }
        work.postDelayed({ slots.forEach { sendText("control", gamepadChanged(it, true)) } }, 500)

        // Input: the client metadata packet first, then the pad state on a fixed beat
        channels["input"]?.send(DataChannel.Buffer(packet(clientMetadata = true), true))
        inputStarted = true
        work.post(inputBeat)
        work.postDelayed(frameFeedback, 2000)
        // Ask for the first picture now that the control channel is open: the request made when the tracks arrived
        // can come before the channel opens and get lost, and then the Xbox never starts the video (test129).
        askKeyframe()
        work.postDelayed(keyframeWatch, 3000)

        listOf(
            "/streaming/systemUi/configuration" to JSONObject().put("version", JSONArray(listOf(0, 2, 0)))
                .put("systemUis", JSONArray(listOf(10, 19, 31, 27, 32, -44, 40, 41, -43))),
            "/streaming/properties/clientappinstallidchanged" to JSONObject().put("clientAppInstallId", UUID.randomUUID().toString()),
            "/streaming/characteristics/orientationchanged" to JSONObject().put("orientation", 0),
            "/streaming/characteristics/touchinputenabledchanged" to JSONObject().put("touchInputEnabled", false),
            "/streaming/characteristics/clientdevicecapabilities" to JSONObject(),
            "/streaming/characteristics/dimensionschanged" to JSONObject().put("horizontal", 1920).put("vertical", 1080)
                .put("preferredWidth", 1920).put("preferredHeight", 1080).put("safeAreaLeft", 0).put("safeAreaTop", 0)
                .put("safeAreaRight", 1920).put("safeAreaBottom", 1080).put("supportsCustomResolution", true),
        ).forEach { (target, content) ->
            sendText("message", JSONObject().put("type", "Message").put("content", content.toString())
                .put("id", UUID.randomUUID().toString()).put("target", target).put("cv", messageCv.getAndIncrement().toString()).toString())
        }
    }

    private fun gamepadChanged(index: Int, added: Boolean) =
        JSONObject().put("message", "gamepadChanged").put("gamepadIndex", index).put("wasAdded", added).toString()

    /** Until video flows, ask for a keyframe again every 3 s (up to 5 times). */
    private var keyframeTries = 0
    private val keyframeWatch = object : Runnable {
        override fun run() {
            if (closed || videoFlowing) return
            if (keyframeTries++ >= 5) return
            Log.i(TAG, "no video yet, asking for a keyframe again")
            askKeyframe()
            work.postDelayed(this, 3000)
        }
    }
    @Volatile private var videoFlowing = false

    private fun askKeyframe() = sendText("control", JSONObject().put("message", "videoKeyframeRequested").put("ifrRequested", false).toString())

    private val inputBeat = object : Runnable {
        override fun run() {
            if (closed) return
            channels["input"]?.let { if (it.state() == DataChannel.State.OPEN) it.send(DataChannel.Buffer(packet(), true)) }
            work.postDelayed(this, 16)
        }
    }

    // A "frame processed" report every 10 s, as XStreaming sends it (the Xbox expects some feedback)
    private var pendingFeedback = false
    private val frameFeedback = object : Runnable {
        override fun run() {
            if (closed) return
            pendingFeedback = true
            work.postDelayed(this, 10_000)
        }
    }

    private fun nowMs() = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0

    /** XStreaming's input packet (Packet/index.ts): report type, sequence, timestamp, then metadata / gamepad frames. */
    private fun packet(clientMetadata: Boolean = false): ByteBuffer {
        val now = nowMs()
        val frames = if (clientMetadata) emptyList() else pads.ifEmpty { listOf(PadFrame(0)) }
        val feedback = !clientMetadata && pendingFeedback
        var size = 14
        if (feedback) size += 1 + 28
        if (frames.isNotEmpty()) size += 1 + 23 * frames.size
        if (clientMetadata) size += 1
        val b = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        var type = 0
        if (feedback) type = type or 1
        if (frames.isNotEmpty()) type = type or 2
        if (clientMetadata) type = 8
        b.putShort(type.toShort())
        b.putInt(inputSequence.getAndIncrement())
        b.putDouble(now)
        if (feedback) {
            pendingFeedback = false
            val n = now.toLong().toInt(); val sub = n - 10
            b.put(1.toByte())
            b.putInt(System.currentTimeMillis().toInt()); b.putInt(sub); b.putInt(sub); b.putInt(n); b.putInt(n); b.putInt(n); b.putInt(n)
        }
        if (frames.isNotEmpty()) {
            b.put(frames.size.toByte())
            frames.forEach { f ->
                b.put(f.index.toByte())
                var m = 0
                if (f.nexus) m = m or 2; if (f.menu) m = m or 4; if (f.view) m = m or 8
                if (f.a) m = m or 16; if (f.b) m = m or 32; if (f.x) m = m or 64; if (f.y) m = m or 128
                if (f.up) m = m or 256; if (f.down) m = m or 512; if (f.left) m = m or 1024; if (f.right) m = m or 2048
                if (f.lb) m = m or 4096; if (f.rb) m = m or 8192; if (f.ls) m = m or 16384; if (f.rs) m = m or 32768
                b.putShort(m.toShort())
                fun ax(v: Float) = (v * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
                fun tr(v: Float) = (v.coerceIn(0f, 1f) * 65535f).toInt().toShort()
                b.putShort(ax(f.lx)); b.putShort(ax(-f.ly)); b.putShort(ax(f.rx)); b.putShort(ax(-f.ry))
                b.putShort(tr(f.lt)); b.putShort(tr(f.rt))
                var ph = 0
                if (f.up) ph = ph or 0x1; if (f.down) ph = ph or 0x2; if (f.left) ph = ph or 0x4; if (f.right) ph = ph or 0x8
                if (f.menu) ph = ph or 0x10; if (f.view) ph = ph or 0x20; if (f.ls) ph = ph or 0x40; if (f.rs) ph = ph or 0x80
                if (f.lb) ph = ph or 0x100; if (f.rb) ph = ph or 0x200; if (f.nexus) ph = ph or 0x400
                if (f.a) ph = ph or 0x1000; if (f.b) ph = ph or 0x2000; if (f.x) ph = ph or 0x4000; if (f.y) ph = ph or 0x8000
                if (f.lt > 0f) ph = ph or 0x10000; if (f.rt > 0f) ph = ph or 0x20000
                if (f.lx != 0f) ph = ph or 0x40000; if (f.ly != 0f) ph = ph or 0x80000
                if (f.rx != 0f) ph = ph or 0x100000; if (f.ry != 0f) ph = ph or 0x200000
                b.putInt(ph); b.putInt(0)
            }
        }
        if (clientMetadata) b.put(0.toByte())   // max touch points
        b.flip()
        return b
    }

    /** Server messages on the input channel: picture size and rumble. */
    private fun parseInput(d: ByteArray) {
        if (d.size < 2) return
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val type = b.getShort(0).toInt() and 0xffff
        var off = 2
        if (type and 16 != 0) off += 8
        if (type and 128 != 0 && d.size >= off + 11) {
            val pad = d[off + 1].toInt() and 0xff
            val left = (d[off + 2].toInt() and 0xff) / 100.0
            val right = (d[off + 3].toInt() and 0xff) / 100.0
            val dur = b.getShort(off + 6).toInt() and 0xffff
            listener.onRumble(pad, right, left, dur)
        }
    }

    // ------------------------------------------------------------------ stats

    data class Stats(val fps: Double, val rttMs: Double, val mbps: Double, val lossPct: Double, val width: Int, val height: Int)
    private var lastBytes = -1L
    private var lastAt = 0L

    /** Live numbers for the Quick menu. Calls back on WebRTC's thread. */
    fun stats(cb: (Stats) -> Unit) {
        val p = pc ?: return
        p.getStats { report ->
            var fps = -1.0; var rtt = -1.0; var mbps = -1.0; var loss = -1.0; var w = 0; var h = 0
            report.statsMap.values.forEach { s ->
                val m = s.members
                if (s.type == "inbound-rtp" && m["kind"] == "video") {
                    fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: fps
                    w = (m["frameWidth"] as? Number)?.toInt() ?: w; h = (m["frameHeight"] as? Number)?.toInt() ?: h
                    val lost = (m["packetsLost"] as? Number)?.toDouble() ?: 0.0
                    val got = (m["packetsReceived"] as? Number)?.toDouble() ?: 0.0
                    if (lost + got > 0) loss = 100.0 * lost / (lost + got)
                    val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: -1L
                    val now = System.currentTimeMillis()
                    if (bytes >= 0 && lastBytes >= 0 && now > lastAt) mbps = (bytes - lastBytes) * 8.0 / ((now - lastAt) * 1000.0)
                    if (bytes >= 0) { lastBytes = bytes; lastAt = now }
                }
                if (s.type == "candidate-pair" && m["state"] == "succeeded" && m["nominated"] == true)
                    rtt = ((m["currentRoundTripTime"] as? Number)?.toDouble() ?: -0.001) * 1000.0
            }
            if (fps > 0 || mbps > 0) videoFlowing = true
            cb(Stats(fps, rtt, mbps, loss, w, h))
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
