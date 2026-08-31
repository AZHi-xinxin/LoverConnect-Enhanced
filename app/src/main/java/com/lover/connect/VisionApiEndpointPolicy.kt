package com.lover.connect

import java.net.URI

/**
 * A vision API request carries a bearer credential and screenshot content.
 * Public endpoints therefore require HTTPS. Plain HTTP is accepted only for
 * the same device loopback, where neither value leaves the phone.
 */
object VisionApiEndpointPolicy {
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
        if (uri.path.isNullOrBlank()) return false

        return scheme == "https" ||
            (scheme == "http" && (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1"))
    }
}
