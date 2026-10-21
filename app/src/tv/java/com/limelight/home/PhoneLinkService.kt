package com.limelight.home

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.limelight.PhoneLink
import com.limelight.R

/**
 * Keeps the phone's channel (:8099) open whether or not MoonCast is on screen, from boot onwards, so the Odin or a
 * phone always finds this TV and can start a game on it. A foreground service, so Android doesn't stop it in the
 * background. The home screen, when it's showing, listens in to show "Sent from …".
 */
class PhoneLinkService : Service(), PhoneLink.Listener {
    private var link: PhoneLink? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        link = PhoneLink(this, this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        link?.stop()
        link = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onIncoming(title: String, from: String) { listener?.onIncoming(title, from) }
    override fun onConsolesChanged() { listener?.onConsolesChanged() }
    override fun onRegistering(host: String) { listener?.onRegistering(host) }
    override fun onRegistered(host: String, name: String?) { listener?.onRegistered(host, name) }

    private fun goForeground() {
        // the name-based lookup: getSystemService(Class) is Android 6+, and the TV app runs on Android 5 sticks too
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ready for your phone", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps MoonCast TV reachable so your phone can start games here"
                setShowBadge(false)
            })
            Notification.Builder(this, CHANNEL)
        } else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        val n = b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle("MoonCast TV")
                .setContentText("Ready for your phone")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    companion object {
        private const val CHANNEL = "mc_phone_link"
        private const val ID = 8099

        /** The home screen while it's showing; gets "Sent from …" and console changes. */
        @Volatile var listener: PhoneLink.Listener? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, PhoneLinkService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { android.util.Log.w("MoonCast", "phone link service: $e") }
        }
    }
}

/** Start listening for the phone as soon as the TV boots (and after an app update). */
class PhoneLinkBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" ->
                PhoneLinkService.start(ctx)
        }
    }
}
