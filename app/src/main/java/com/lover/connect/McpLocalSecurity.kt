package com.lover.connect

import android.content.Context
import java.security.SecureRandom

object McpLocalSecurity {
    const val PREFS_NAME = "lc_mcp_security"
    private const val TOKEN_KEY = "endpoint_token"
    private const val TOKEN_BYTES = 16
    private const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2
    private val lock = Any()

    fun endpoint(context: Context): String =
        "http://127.0.0.1:5000/mcp/${getOrCreateToken(context)}"

    fun isAuthorizedRequestLine(context: Context, requestLine: String): Boolean {
        return isAuthorizedRequestLine(requestLine, getOrCreateToken(context))
    }

    internal fun isAuthorizedRequestLine(requestLine: String, expectedToken: String): Boolean {
        val parts = requestLine.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts[0] != "POST") return false
        if (!parts[1].startsWith("/mcp/")) return false
        val supplied = parts[1].removePrefix("/mcp/")
        if (supplied.length != TOKEN_HEX_LENGTH || !supplied.all { it in "0123456789abcdef" }) return false
        return constantTimeEquals(supplied, expectedToken)
    }

    fun getOrCreateToken(context: Context): String = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(TOKEN_KEY, null)
        if (existing != null && existing.length == TOKEN_HEX_LENGTH &&
            existing.all { it in "0123456789abcdef" }
        ) return@synchronized existing

        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val alphabet = "0123456789abcdef"
        val token = buildString(TOKEN_HEX_LENGTH) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
        check(prefs.edit().putString(TOKEN_KEY, token).commit()) {
            "Unable to persist the private local MCP endpoint"
        }
        token
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        for (index in left.indices) {
            difference = difference or (left[index].code xor right[index].code)
        }
        return difference == 0
    }
}
