package com.sixoffive.androidmcp.server

import java.net.Inet4Address
import java.net.NetworkInterface

/** Resolves which local address the server binds to for a given mode. */
object Net {

    /** Tailscale hands out addresses in the 100.64.0.0/10 CGNAT range. */
    fun tailnetAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { a ->
                val o = a.address
                (o[0].toInt() and 0xFF) == 100 && (o[1].toInt() and 0xFF) in 64..127
            }
            ?.hostAddress
    }.getOrNull()

    /** First non-loopback IPv4 (for LAN mode display). */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()

    /** Host to actually bind the listener to. */
    fun bindHost(mode: String): String = when (mode) {
        "lan" -> "0.0.0.0"
        "tailscale" -> tailnetAddress() ?: "127.0.0.1"
        else -> "127.0.0.1"
    }

    /** Human-facing reachable address for the given mode. */
    fun reachableHost(mode: String): String = when (mode) {
        "lan" -> lanAddress() ?: "0.0.0.0"
        "tailscale" -> tailnetAddress() ?: "(tailnet down)"
        else -> "127.0.0.1"
    }
}
