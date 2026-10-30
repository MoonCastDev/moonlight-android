package com.limelight.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.http.ComputerDetails
import java.net.InetAddress

/**
 * Is a PC stream "away" (over the internet) or at home (on this device's own network)? Decided once at stream start
 * from the address the PC was reached on (ComputerDetails.activeAddress, which Game gets as EXTRA_HOST):
 *
 *  1. The PC's address is on-link on one of this device's own networks (Wi-Fi / Ethernet, not a VPN): home. This is
 *     the same test Moonlight's NvConnection uses for LAN detection (a route with no gateway), done over every
 *     non-VPN network so a phone VPN with the LAN left out doesn't hide the home network. IPv6 global addresses on
 *     the home LAN count as home this way too.
 *  2. The device's networks are known but none has the PC on-link: away. That covers a public address, a mobile
 *     network, and a private address reached through a gateway: a site-to-site WireGuard/VPN or another subnet.
 *  3. The networks can't be read: the address decides. Loopback, private (RFC 1918), link-local and IPv6 ULA are
 *     home; anything else (public, CGNAT 100.64/10 as Tailscale uses) is away. A name that didn't resolve is away
 *     when it is the PC's saved internet address (remote or manual) and not its home address.
 *
 * The pure rules ([kindOf], [decide]) take no Android types, so they can be tested on the JVM.
 */
object Network {
    enum class Kind { LOOPBACK, LINK_LOCAL, PRIVATE, ULA, CGNAT, PUBLIC }

    /** What kind of address [a] (4 or 16 bytes) is. */
    @JvmStatic
    fun kindOf(a: ByteArray): Kind {
        fun u(i: Int) = a[i].toInt() and 0xff
        if (a.size == 16) {
            // an IPv4-mapped IPv6 address (::ffff:a.b.c.d) is its IPv4 address
            if ((0 until 10).all { a[it].toInt() == 0 } && u(10) == 0xff && u(11) == 0xff) return kindOf(a.copyOfRange(12, 16))
            if ((0 until 15).all { a[it].toInt() == 0 } && u(15) == 1) return Kind.LOOPBACK
            if (u(0) == 0xfe && (u(1) and 0xc0) == 0x80) return Kind.LINK_LOCAL   // fe80::/10
            if ((u(0) and 0xfe) == 0xfc) return Kind.ULA                          // fc00::/7
            return Kind.PUBLIC
        }
        if (a.size != 4) return Kind.PUBLIC
        return when {
            u(0) == 127 -> Kind.LOOPBACK
            u(0) == 169 && u(1) == 254 -> Kind.LINK_LOCAL
            u(0) == 10 -> Kind.PRIVATE
            u(0) == 172 && u(1) in 16..31 -> Kind.PRIVATE
            u(0) == 192 && u(1) == 168 -> Kind.PRIVATE
            u(0) == 100 && u(1) in 64..127 -> Kind.CGNAT
            else -> Kind.PUBLIC
        }
    }

    /** Addresses that only exist on a local network. CGNAT isn't one: it is a carrier's or a VPN's (Tailscale). */
    @JvmStatic
    fun isLocalKind(k: Kind) = k == Kind.LOOPBACK || k == Kind.LINK_LOCAL || k == Kind.PRIVATE || k == Kind.ULA

    /**
     * The rule. [onLink]: true when the PC's address is on-link on one of this device's own networks, false when the
     * networks were read and it isn't, null when they couldn't be read. [kind]: the address' kind, null when it
     * didn't resolve. [savedInternet]: the address is the PC's saved internet address (remote or manual) and not its
     * home (local) one. True means away.
     */
    @JvmStatic
    fun decide(onLink: Boolean?, kind: Kind?, savedInternet: Boolean): Boolean = when {
        kind == Kind.LOOPBACK -> false
        onLink == true -> false
        onLink == false -> true
        kind == null -> savedInternet
        else -> !isLocalKind(kind)
    }

    /** The answer and why, for the log. */
    class Verdict(@JvmField val away: Boolean, @JvmField val why: String)

    /**
     * Whether a stream to [host] (the address the PC was reached on) is away. [pc] is the saved PC, when known. Blocks
     * for a name lookup when [host] is a name: call it off the main thread.
     */
    @JvmStatic
    fun isAway(ctx: Context, host: String?, pc: ComputerDetails?): Verdict {
        if (host.isNullOrBlank()) return Verdict(false, "no address")
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val savedInternet = pc != null && pc.localAddress?.address?.equals(h, true) != true &&
                (pc.remoteAddress?.address?.equals(h, true) == true || pc.manualAddress?.address?.equals(h, true) == true)
        val addrs = try { InetAddress.getAllByName(h).toList() } catch (e: Exception) { emptyList() }
        if (addrs.isEmpty()) return Verdict(decide(null, null, savedInternet), "$h didn't resolve" + if (savedInternet) ", saved internet address" else "")
        val addr = addrs[0]
        val kind = kindOf(addr.address)
        val onLink = onLink(ctx, addr)
        return Verdict(decide(onLink, kind, savedInternet),
                "$h = ${addr.hostAddress} ($kind, " + when (onLink) { true -> "on this network"; false -> "not on this network"; null -> "networks unknown" } + ")")
    }

    /** [pc] by its active address. */
    @JvmStatic
    fun isAway(ctx: Context, pc: ComputerDetails): Boolean = isAway(ctx, pc.activeAddress?.address, pc).away

    /**
     * True when [addr] is on-link (a route without a gateway) on one of this device's non-VPN networks, false when
     * those networks were read and none has it, null when they can't be read (before Android 6 or an error).
     */
    @JvmStatic
    fun onLink(ctx: Context, addr: InetAddress): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var seen = false
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                seen = true
                for (r in lp.routes) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && r.type != android.net.RouteInfo.RTN_UNICAST) continue
                    if (!r.matches(addr)) continue
                    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !r.hasGateway()
                                 else r.gateway == null || r.gateway!!.isAnyLocalAddress
                    if (direct) return true
                }
            }
            if (seen) false else null
        } catch (e: Exception) {
            LimeLog.warning("Network: couldn't read the routes: $e")
            null
        }
    }
}
