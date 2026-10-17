package com.limelight.handheld

/**
 * The one controller server for this app process. It starts with the first game sent to a TV and stays up while you
 * re-send, move a game, or controller mode reopens, so the TV's link to it (PhoneGamepad / Ps5Pad / XboxPhonePad)
 * keeps working instead of dropping and hitting a closed port. Each TV gets one pad key for the session, sent again
 * on every /play to it. Only Stop, Play here instead, or leaving the app ends it.
 */
object PadLink {
    private var server: PadServer? = null
    private val keys = HashMap<String, String>()   // TV id -> its pad key for this session

    /** Listeners for the number of connected TVs (called on a background thread). */
    @Volatile var onClients: ((Int) -> Unit)? = null

    @Synchronized
    fun server(): PadServer = server?.takeIf { it.isRunning } ?: PadServer().also { s ->
        s.keys.addAll(keys.values)
        s.onClients = { n -> onClients?.invoke(n) }
        s.start()
        server = s
    }

    /** The pad key for [tvId]: the same one for as long as the server runs, so the TV's link stays valid. */
    @Synchronized
    fun keyFor(tvId: String): String {
        val k = keys.getOrPut(tvId) { TvSender.newPadKey() }
        server().keys.add(k)
        return k
    }

    val clientCount: Int get() = server?.clientCount ?: 0
    val running: Boolean get() = server?.isRunning == true

    fun update(b: FloatArray, a: FloatArray) { server?.update(b, a) }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        keys.clear()
    }
}
