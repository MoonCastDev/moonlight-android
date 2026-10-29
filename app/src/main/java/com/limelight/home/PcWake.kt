package com.limelight.home

import android.app.Activity
import android.os.Handler
import android.os.SystemClock
import com.limelight.R
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.wol.WakeOnLanSender
import java.util.concurrent.ConcurrentHashMap

/**
 * Waking a PC and waiting for it to come back, for both homes (handheld and TV).
 *
 * One Wake-on-LAN packet wasn't enough after Hibernate (test263, Saleh): pressed right away, it reached the PC while
 * Windows was still writing its memory to disk, before the network card listens for the packet, and nothing sent
 * another, so MoonCast had to be restarted. So the packet goes again every 3 s until the PC answers (up to 2 min); the
 * service polls it every 1.5 s meanwhile (its old answer is dropped first), the PC says "Waking up…", and a game pressed
 * meanwhile starts once the PC is up. Starting a game on a PC hibernated from the Quick menu opens its Desktop, where
 * the game still is (PowerResume, in ServerHelper).
 *
 * [binder] is the home's ComputerManagerService binder (null while unbound), [known] its own copy of a PC by uuid.
 */
class PcWake(
    private val act: Activity,
    private val binder: () -> ComputerManagerService.ComputerManagerBinder?,
    private val known: (String) -> ComputerDetails?,
    private val toast: (String) -> Unit,
    private val render: () -> Unit,
) {
    /** PCs MoonCast is waiting for (uuid -> until when, elapsed ms): woken, or just restarted from the Quick menu. */
    private val waking = ConcurrentHashMap<String, Long>()
    private var afterWake: Pair<String, () -> Unit>? = null

    /** Wakes [d], sending Wake-on-LAN until it answers; then runs [then]. */
    fun wake(d: ComputerDetails, then: (() -> Unit)? = null) {
        if (d.macAddress == null || d.macAddress == "00:00:00:00:00:00") return toast(act.getString(R.string.wol_no_mac))
        watch(d, sendWol = true, then = then)
    }

    /** Runs [then] once the PC [uuid], which MoonCast is already waiting for, answers (replaces an earlier one). */
    fun after(uuid: String, then: () -> Unit) { afterWake = uuid to then }

    /** True while MoonCast waits for this PC to wake or come back. */
    fun isWaking(d: ComputerDetails) = d.uuid?.let { waking.containsKey(it) } == true

    /** Waits up to [forMs] for [d] to answer, sending Wake-on-LAN every 3 s if [sendWol]; then runs [then]. */
    fun watch(d: ComputerDetails, sendWol: Boolean, forMs: Long = 120_000, then: (() -> Unit)? = null) {
        val uuid = d.uuid ?: return
        if (then != null) afterWake = uuid to then
        val already = waking.containsKey(uuid)
        waking[uuid] = SystemClock.elapsedRealtime() + forMs
        binder()?.invalidateStateForComputer(uuid)   // forget the old answer: it's asked again within 1.5 s
        if (already) { toast("Still waiting for ${d.name}…"); return }
        toast(if (sendWol) "Waking ${d.name}. It can take a minute; MoonCast keeps trying" else "Waiting for ${d.name} to come back…")
        render()
        Thread {
            var failed = false
            while (true) {
                val until = waking[uuid] ?: break
                if (binder()?.getComputer(uuid)?.state == ComputerDetails.State.ONLINE) break
                if (SystemClock.elapsedRealtime() > until) break
                if (sendWol) try { WakeOnLanSender.sendWolPacket(binder()?.getComputer(uuid) ?: d) } catch (e: Exception) { failed = true }
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
            act.runOnUiThread {
                waking.remove(uuid)
                val pc = known(uuid) ?: binder()?.getComputer(uuid)
                val up = pc != null && (pc.state == ComputerDetails.State.ONLINE || binder()?.getComputer(uuid)?.state == ComputerDetails.State.ONLINE)
                val next = afterWake?.takeIf { it.first == uuid }?.second
                if (afterWake?.first == uuid) afterWake = null
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (up) { toast("${d.name} is awake"); next?.invoke() }
                else toast(if (failed) act.getString(R.string.wol_fail) else "${d.name} didn't answer. Check it's on and Wake-on-LAN is on in its network card settings")
                render()
            }
        }.start()
    }

    /**
     * For the home's onResume: back from a stream that ended with the PC going down (Quick menu, Power), drop its old
     * state at once (so Wake is offered, not a stale "online"), and after Restart wait for it to come back.
     */
    fun backFromStream(ui: Handler) {
        PowerResume.takeWentDown()?.let { (uuid, kind) ->
            val d = known(uuid) ?: binder()?.getComputer(uuid)
            binder()?.invalidateStateForComputer(uuid)
            if (d != null && kind == "restart") ui.postDelayed({ watch(d, sendWol = false, forMs = 240_000) }, 1500)
        }
    }
}
