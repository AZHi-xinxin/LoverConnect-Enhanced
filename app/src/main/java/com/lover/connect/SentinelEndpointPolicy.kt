package com.lover.connect

import java.net.URI

/**
 * Keeps sentinel configuration usable without embedding a private deployment URL.
 * Public HTTPS endpoints are allowed. Plain HTTP is limited to loopback, LAN,
 * link-local, and Tailscale/private ranges.
 */
object SentinelEndpointPolicy {
    fun isAllowed(rawUrl: String): Boolean {
        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (uri.rawUserInfo != null || uri.rawFragment != null) return false
        if (uri.port == 0 || uri.port > 65535) return false

        return when (scheme) {
            "https" -> true
            "http" -> isPrivateHost(host)
            else -> false
        }
    }

    fun locationEventsUrl(rawUrl: String): String? {
        if (!isAllowed(rawUrl)) return null
        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return null
        }
        val path = uri.path.orEmpty().trimEnd('/')
        val locationPath = when {
            path.endsWith("/loverconnect/v1/location-events") -> path
            path.endsWith("/loverconnect/alert") ->
                path.removeSuffix("/loverconnect/alert") + "/loverconnect/v1/location-events"
            else -> return null
        }
        return try {
            URI(uri.scheme, null, uri.host, uri.port, locationPath, null, null).toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") || host.endsWith(".ts.net")) return true
        if (host.contains(':')) {
            if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) {
                return true
            }
            return false
        }

        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val a = octets[0]!!
        val b = octets[1]!!
        return a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
}
