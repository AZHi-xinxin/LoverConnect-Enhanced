package com.lover.connect

import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

internal data class EyesAnalysisResponse(
    val action: String,
    val message: String,
    val reason: String = "visual_observation",
)

/** Diagnostic codes describe shape only; they never contain model text. */
internal data class EyesResponseParseResult(
    val analysis: EyesAnalysisResponse?,
    val rejectionCode: String?,
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
    private val unquotedKey = Regex("[A-Za-z_][A-Za-z0-9_]*\\s*:")
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val thinkingTag = Regex("</?think\\b", RegexOption.IGNORE_CASE)
    private val thinkingOpen = Regex("<think\\s*>", RegexOption.IGNORE_CASE)
    private val thinkingClose = Regex("</think\\s*>", RegexOption.IGNORE_CASE)
    private val supportedActions = setOf("log", "notify", "popup", "none")

    fun parse(response: String): EyesAnalysisResponse? = parseDetailed(response).analysis

    fun parseDetailed(response: String): EyesResponseParseResult {
        val withoutReasoning = stripReasoning(response) ?: return rejected("incomplete_reasoning")
        val raw = EyesDiaryText.nonBlank(withoutReasoning) ?: return rejected("empty_content")
        val fence = completeFence.matchEntire(raw)
        val text = if (fence != null) {
            EyesDiaryText.nonBlank(fence.groupValues[1]) ?: return rejected("empty_content")
        } else raw

        if (text in setOf("null", "true", "false") || jsonNumber.matches(text)) {
            return rejected("unsupported_structure")
        }

        val candidates = mutableListOf<String>()
        var position = 0
        while (position < text.length) {
            if (!isContainerStart(text, position)) {
                position++
                continue
            }
            val reader = JsonContainerReader(text, position)
            try {
                reader.readValue()
            } catch (error: InvalidJson) {
                return rejected(error.code)
            }
            candidates += text.substring(position, reader.position)
            if (candidates.size > 1) return rejected("ambiguous_json")
            position = reader.position
        }

        if (candidates.isEmpty()) {
            val hasEnvelope = commandField.containsMatchIn(text) || jsonFence.containsMatchIn(raw) ||
                text.contains("<|begin_of_box|>") || text.contains("<|end_of_box|>")
            return if (hasEnvelope) rejected("invalid_json")
            else accepted(EyesAnalysisResponse("log", text))
        }

        val candidate = candidates.single()
        if (candidate.startsWith("[")) return rejected("unsupported_structure")
        val json = parseObject(candidate) ?: return rejected("invalid_json")
        if (!json.has("message")) return rejected("missing_message")
        val rawMessage = json.opt("message") as? String ?: return rejected("non_string_message")
        val message = EyesDiaryText.nonBlank(rawMessage) ?: return rejected("empty_message")
        val action = (json.opt("action") as? String)?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it in supportedActions } ?: "log"
        val reason = (json.opt("reason") as? String)
            ?.let(EyesDiaryText::nonBlank) ?: "visual_observation"
        return accepted(EyesAnalysisResponse(action, message, reason))
    }

    private fun accepted(analysis: EyesAnalysisResponse) = EyesResponseParseResult(analysis, null)
    private fun rejected(code: String) = EyesResponseParseResult(null, code)

    /** Strip only complete reasoning blocks outside quoted JSON values. */
    private fun stripReasoning(text: String): String? {
        val visible = StringBuilder()
        var position = 0
        var depth = 0
        var quoted = false
        var escaped = false
        while (position < text.length) {
            val char = text[position]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                if (char == '<' && thinkingTag.matchAt(text, position) != null) {
                    val opening = thinkingOpen.matchAt(text, position) ?: return null
                    val closing = thinkingClose.find(text, opening.range.last + 1) ?: return null
                    if (thinkingOpen.find(text, opening.range.last + 1)?.range?.first
                        ?.let { it < closing.range.first } == true) return null
                    visible.append('\n')
                    position = closing.range.last + 1
                    continue
                }
                if (char == '{' || char == '[') depth++
                else if (char == '}' || char == ']') depth = (depth - 1).coerceAtLeast(0)
                else if (char == '"' && depth > 0) quoted = true
            }
            visible.append(char)
            position++
        }
        return visible.toString()
    }

    private fun isContainerStart(text: String, position: Int): Boolean {
        val char = text[position]
        if (char != '{' && char != '[') return false
        var next = position + 1
        while (next < text.length && text[next].isWhitespace()) next++
        if (next == text.length) return true
        val following = text[next]
        if (char == '{') {
            // Braces in ordinary prose, such as "页面还有 {草稿} 标记", are not JSON.
            return position == 0 || following in "\"'{}[" || unquotedKey.matchAt(text, next) != null
        }
        return following in "\"'{}[]-" || following.isDigit() ||
            listOf("true", "false", "null").any { text.startsWith(it, next) }
    }

    private class InvalidJson(val code: String) : Exception()

    /**
     * Validate JSON syntax while locating its exact end. Android's JSONTokener and the JVM
     * test dependency accept different malformed syntax, so neither decides our boundaries.
     * No repair, field substitution or trailing-comma tolerance is applied here.
     */
    private class JsonContainerReader(private val text: String, var position: Int) {
        fun readValue(depth: Int = 0) {
            if (depth > 64) throw InvalidJson("invalid_json")
            whitespace()
            when (peek()) {
                '{' -> readContainer('}', depth)
                '[' -> readContainer(']', depth)
                '"' -> readString()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> {
                    val number = jsonNumber.matchAt(text, position) ?: throw InvalidJson("invalid_json")
                    position = number.range.last + 1
                }
                else -> throw InvalidJson("invalid_json")
            }
        }

        private fun readContainer(end: Char, depth: Int) {
            position++
            whitespace()
            if (consume(end)) return
            while (true) {
                if (end == '}') {
                    readString()
                    whitespace()
                    expect(':')
                }
                readValue(depth + 1)
                whitespace()
                if (consume(end)) return
                expect(',')
                whitespace()
                if (peek() == end) throw InvalidJson("invalid_json")
            }
        }

        private fun readString() {
            expect('"')
            while (true) {
                val char = peek()
                position++
                if (char == '"') return
                if (char.code < 0x20) throw InvalidJson("invalid_json")
                if (char == '\\') {
                    val escape = peek()
                    position++
                    if (escape == 'u') {
                        repeat(4) {
                            if (peek() !in "0123456789abcdefABCDEF") throw InvalidJson("invalid_json")
                            position++
                        }
                    } else if (escape !in "\"\\/bfnrt") throw InvalidJson("invalid_json")
                }
            }
        }

        private fun literal(value: String) {
            for (char in value) expect(char)
        }

        private fun whitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        private fun peek(): Char = text.getOrNull(position) ?: throw InvalidJson("incomplete_json")
        private fun consume(char: Char): Boolean = if (peek() == char) {
            position++
            true
        } else false

        private fun expect(char: Char) {
            if (!consume(char)) throw InvalidJson("invalid_json")
        }
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
