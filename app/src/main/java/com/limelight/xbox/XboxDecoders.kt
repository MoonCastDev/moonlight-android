package com.limelight.xbox

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.limelight.binding.video.MediaCodecHelper
import org.webrtc.EglBase
import org.webrtc.EncodedImage
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hardware decoders for the Xbox player with MoonCast's low-latency decoder settings, the same ones the PC player uses
 * (Moonlight's vendor keys plus Artemis' ultra-low-latency set when Settings, Video, Ultra low latency is on).
 *
 * libwebrtc configures MediaCodec itself, so, like XStreaming's LowLatencyVideoDecoder, we swap the decoder's
 * MediaCodecWrapperFactory for a proxy and add our keys to the MediaFormat when it calls configure().
 */
class XboxDecoderFactory(egl: EglBase.Context, private val ultraLowLatency: Boolean) : VideoDecoderFactory {
    // The codec list (and so the offer the Xbox sees) must stay exactly libwebrtc's default: with our own list the
    // Xbox accepted the connection but never sent video (test127). Only the H.264 decoder itself is ours.
    private val default = DefaultVideoDecoderFactory(egl)
    private val hw = HardwareVideoDecoderFactory(egl)

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        // Our extra decoder settings only with Settings, Video, Ultra low latency: with them on the Amlogic stick,
        // libwebrtc's decoding queue aborted right after the decoder started (test131). Default = libwebrtc as-is.
        if (ultraLowLatency && info.name.equals("H264", true)) hw.createDecoder(info)?.let {
            Log.i("MoonCast-XboxDec", "H.264 decoder: ${it.implementationName} (low latency, ultra=$ultraLowLatency)")
            return LowLatencyDecoder(it, ultraLowLatency)
        }
        return default.createDecoder(info)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        // The Xbox answers H.264 at profile-level-id 42e02a (level 4.2) when the offer only has 42e01f (level 3.1), and
        // libwebrtc then drops that payload type: the packets arrive, no codec is mapped, no frame is ever decoded
        // (test150). So the offer also lists the same H.264 at 42e02a. Everything else stays libwebrtc's default list.
        val base = default.supportedCodecs
        val extra = base.filter { it.name.equals("H264", true) && it.params["profile-level-id"] == "42e01f" }
            .map { VideoCodecInfo("H264", HashMap(it.params).apply { put("profile-level-id", "42e02a") }) }
            .filter { e -> base.none { it.name == e.name && it.params == e.params } }
        val list = (base.toList() + extra).toTypedArray()
        Log.i("MoonCast-XboxDec", "offering " + list.joinToString { it.name + it.params })
        return list
    }
}

private class LowLatencyDecoder(private val delegate: VideoDecoder, private val ultra: Boolean) : VideoDecoder {
    private var patched = false

    override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
        if (!patched) { patched = true; patch() }
        return delegate.initDecode(settings, callback)
    }
    override fun release(): VideoCodecStatus = delegate.release()
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo): VideoCodecStatus = delegate.decode(frame, info)
    override fun getImplementationName(): String = delegate.implementationName + "+mooncast-low-latency"

    private fun patch() {
        try {
            var c: Class<*>? = delegate.javaClass
            var field: java.lang.reflect.Field? = null
            while (c != null && field == null) {
                field = c.declaredFields.firstOrNull { it.type.name == "org.webrtc.MediaCodecWrapperFactory" }
                c = c.superclass
            }
            val f = field ?: run { Log.w(TAG, "no MediaCodecWrapperFactory on ${delegate.javaClass.name}"); return }
            f.isAccessible = true
            val original = f.get(delegate) ?: return
            f.set(delegate, Proxy.newProxyInstance(f.type.classLoader, arrayOf(f.type), FactoryHandler(original, ultra)))
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't add low-latency settings", t)
        }
    }

    private class FactoryHandler(private val original: Any, private val ultra: Boolean) : InvocationHandler {
        override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? {
            val result = call(original, m, args)
            if (m.name != "createByCodecName" || result == null) return result
            val name = args?.getOrNull(0) as? String ?: ""
            val iface = result.javaClass.interfaces.firstOrNull { it.name == "org.webrtc.MediaCodecWrapper" } ?: return result
            return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), CodecHandler(result, name, ultra))
        }
    }

    private class CodecHandler(private val codec: Any, private val name: String, private val ultra: Boolean) : InvocationHandler {
        override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? {
            if (m.name == "configure" && args?.getOrNull(0) is MediaFormat) {
                val fmt = args[0] as MediaFormat
                val info = codecInfo(name)
                // never let our extra settings stop the decoder: if they can't be applied, configure as libwebrtc would
                val applied = info != null && try {
                    MediaCodecHelper.setDecoderLowLatencyOptions(fmt, info, ultra, 0); true
                } catch (t: Throwable) { Log.w(TAG, "low-latency settings skipped: $t"); false }
                if (applied) {
                    Log.i(TAG, "low-latency settings on $name (ultra=$ultra)")
                    try { return call(codec, m, args) } catch (t: Throwable) {
                        // Some decoder refused a key: fall back to libwebrtc's own settings rather than fail the stream
                        Log.w(TAG, "$name refused the low-latency settings, using its defaults: $t")
                        val plain = MediaFormat.createVideoFormat(fmt.getString(MediaFormat.KEY_MIME)!!,
                            fmt.getInteger(MediaFormat.KEY_WIDTH), fmt.getInteger(MediaFormat.KEY_HEIGHT))
                        if (fmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) plain.setInteger(MediaFormat.KEY_COLOR_FORMAT, fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT))
                        val retry = args.toMutableList().also { it[0] = plain }.toTypedArray()
                        return call(codec, m, retry)
                    }
                }
            }
            return call(codec, m, args)
        }
    }

    companion object {
        private const val TAG = "MoonCast-XboxDec"

        private fun codecInfo(name: String): MediaCodecInfo? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name.equals(name, true) }
        } catch (t: Throwable) { null }

        private fun call(target: Any, m: Method, args: Array<out Any?>?): Any? = try {
            m.isAccessible = true
            m.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) { throw e.cause ?: e }
    }
}
