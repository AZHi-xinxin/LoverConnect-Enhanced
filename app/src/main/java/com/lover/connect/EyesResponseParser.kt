package com.lover.connect

import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

internal data class EyesAnalysisResponse(
    val action: String,
    val message: String,
    val reason: String = "visual_observation",
)

/** Separates model command envelopes from the text that is safe to put in the diary. */
internal object EyesResponseParser {
    private val completeFence = Regex(
        "^```(?:json)?[ \\t]*(?:\\r?\\n)?([\\s\\S]*?)\\s*```$",
        RegexOption.IGNORE_CASE,
    )
    private val commandField = Regex(
        "(?:^|[\\s{,])[\"']?(?:action|message|reason)[\"']?\\s*:",
        RegexOption.IGNORE_CASE,
    )
    private val jsonFence = Regex("```[ \\t]*json\\b", RegexOption.IGNORE_CASE)
    private val supportedActions = setOf("log", "notify", "popup", "none")

    fun parse(response: String): EyesAnalysisResponse? {
        val raw = EyesDiaryText.nonBlank(response) ?: return null
        val fence = completeFence.matchEntire(raw)
        val text = if (fence != null) {
            EyesDiaryText.nonBlank(fence.groupValues[1]) ?: return null
        } else raw

        // Providers sometimes add a Markdown fence or a short introduction to the JSON.
        // Never fall back to logging the raw command when parsing or validation fails.
        val looksStructured = text.startsWith("{") || commandField.containsMatchIn(text) ||
            jsonFence.containsMatchIn(raw)
        if (looksStructured) {
            if (text.startsWith("[")) return null
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val json = parseObject(text.substring(start, end + 1)) ?: return null
            val message = (json.opt("message") as? String)
                ?.let(EyesDiaryText::nonBlank) ?: return null
            val action = (json.opt("action") as? String)?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it in supportedActions } ?: "log"
            val reason = (json.opt("reason") as? String)
                ?.let(EyesDiaryText::nonBlank) ?: "visual_observation"
            return EyesAnalysisResponse(action, message, reason)
        }

        return EyesDiaryText.nonBlank(text)?.let { EyesAnalysisResponse("log", it) }
    }

    private fun parseObject(text: String): JSONObject? = try {
        val tokenizer = JSONTokener(text)
        val value = tokenizer.nextValue()
        if (value is JSONObject && tokenizer.nextClean() == '\u0000') value else null
    } catch (_: Exception) {
        null
    }
}

internal object EyesDiaryText {
    fun nonBlank(content: String): String? = content
        .trim { it.isWhitespace() || it == '\uFEFF' || it == '\u200B' }
        .takeIf { it.isNotEmpty() }
}
