package com.limelight.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.limelight.Ps5StreamActivity
import com.limelight.XboxStreamActivity

/**
 * Starting a PS5 or Xbox stream on this device, shared by both homes (the TV and the handheld) and the TV's phone
 * channel. PC games start through Moonlight's ServerHelper.doStart.
 */
object Launch {
    @JvmStatic
    fun ps5Intent(ctx: Context, host: String, registKey: String, morning: String, isPs5: Boolean,
                  psnToken: String?, duid: String?, accountId: String?): Intent =
        Intent(ctx, Ps5StreamActivity::class.java).apply {
            putExtra(Ps5StreamActivity.EXTRA_HOST, host)
            putExtra(Ps5StreamActivity.EXTRA_REGIST_KEY, registKey)
            putExtra(Ps5StreamActivity.EXTRA_MORNING, morning)
            putExtra(Ps5StreamActivity.EXTRA_PS5, isPs5)
            if (!psnToken.isNullOrEmpty()) putExtra(Ps5StreamActivity.EXTRA_PSN_TOKEN, psnToken)
            if (!duid.isNullOrEmpty()) putExtra(Ps5StreamActivity.EXTRA_DUID, duid)
            if (!accountId.isNullOrEmpty()) putExtra(Ps5StreamActivity.EXTRA_ACCOUNT_ID, accountId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @JvmStatic
    fun xboxIntent(ctx: Context, c: Xbox.Console): Intent =
        Intent(ctx, XboxStreamActivity::class.java)
                .putExtra(XboxStreamActivity.EXTRA_CONSOLE_ID, c.id).putExtra(XboxStreamActivity.EXTRA_NAME, c.name)

    /**
     * Play a registered PS5: on the home network directly; away from home through PlayStation Network when signed in
     * (the player still tries the home network first).
     */
    @JvmStatic
    fun playPs(act: Activity, c: Ps5Store.Console, power: Ps5Store.Power, toast: (String) -> Unit) {
        if (power != Ps5Store.Power.NOT_FOUND || !Psn.signedIn(act)) {
            if (power == Ps5Store.Power.NOT_FOUND)
                return toast("${c.name} isn't on this network. Sign in to PlayStation Network in Settings to play it from here.")
            FeelSounds.play(act, FeelSounds.Cue.LAUNCH)
            act.startActivity(ps5Intent(act, c.host, c.registKey, c.morning, c.ps5, null, null, null))
            return
        }
        Thread {
            val r = Psn.remoteFor(act, c.name)
            act.runOnUiThread {
                if (r == null) toast("PlayStation Network doesn't list ${c.name} for Remote Play. Check Remote Play is on in its settings.")
                else {
                    FeelSounds.play(act, FeelSounds.Cue.LAUNCH)
                    act.startActivity(ps5Intent(act, c.host, c.registKey, c.morning, c.ps5, r.first, r.second, r.third))
                }
            }
        }.start()
    }
}
