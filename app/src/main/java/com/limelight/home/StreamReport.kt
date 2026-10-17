package com.limelight.home

/**
 * How the stream on this device is going, for whoever started it from another device: the TV app answers the
 * handheld's GET /status with it, so the handheld can show the TV's own error ("your Xbox didn't answer") instead of
 * guessing. The players (PC, PS5, Xbox) report; nothing listens on the handheld app.
 */
object StreamReport {
    /** (state, message): state is "starting", "playing" or "failed". Called on any thread. */
    @JvmStatic var listener: ((String, String?) -> Unit)? = null

    @JvmStatic fun starting() { listener?.invoke("starting", null) }
    @JvmStatic fun playing() { listener?.invoke("playing", null) }
    /** The player gave up: [message] in plain words, as shown on its own screen. */
    @JvmStatic fun failed(message: String) { listener?.invoke("failed", message.replace('\n', ' ').trim()) }
}
