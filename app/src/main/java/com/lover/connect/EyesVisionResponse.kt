package com.lover.connect

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class EyesVisionResult(
    val content: String,
    val rejectionCode: String?,
    val metadata: JSONObject,
)

/** Decodes a completion envelope without promoting reasoning or unsupported parts to diary text. */
internal object EyesVisionResponse {
    private val finishReasons = setOf("stop", "length", "content_filter", "tool_calls", "function_call")

    fun decode(response: String): EyesVisionResult {
        val metadata = JSONObject()
            .put("finish_reason", "missing")
            .put("content_type", "missing")
            .put("content_chars", 0)
            .put("reasoning_chars", 0)
            .put("has_refusal", false)
            .put("has_tool_calls", false)

        fun reject(code: String) = EyesVisionResult("", code, metadata)

        return try {
            val tokenizer = JSONTokener(response)
            val envelope = tokenizer.nextValue() as? JSONObject ?: return reject("invalid_response")
            if (tokenizer.nextClean() != '\u0000') return reject("invalid_response")
            val choices = envelope.opt("choices") as? JSONArray ?: return reject("invalid_response")
            val choice = choices.opt(0) as? JSONObject ?: return reject("invalid_response")
            val message = choice.opt("message") as? JSONObject ?: return reject("invalid_response")

            val finishReason = when (val raw = choice.opt("finish_reason")) {
                null, JSONObject.NULL -> "missing"
                is String -> raw.takeIf { it in finishReasons } ?: "other"
                else -> "other"
            }
            val rawContent = message.opt("content")
            val parts = rawContent as? JSONArray
            val hasRefusal = message.hasRefusal() || (0 until (parts?.length() ?: 0)).any {
                (parts?.opt(it) as? JSONObject)?.opt("type") == "refusal"
            }
            // Empty tool-call arrays and blank refusal strings are provider placeholders.
            // Malformed fields and non-null legacy function_call remain conservative rejects.
            val hasToolCalls = message.hasToolCalls() || message.hasNonNull("function_call")
            metadata.put("finish_reason", finishReason)
                .put("has_refusal", hasRefusal)
                .put("has_tool_calls", hasToolCalls)
                .put(
                    "reasoning_chars",
                    listOf("reasoning_content", "reasoning").sumOf {
                        (message.opt(it) as? String)?.length?.toLong() ?: 0L
                    },
                )
            (envelope.opt("usage") as? JSONObject)?.let { usage ->
                metadata.putNonNegativeNumber("completion_tokens", usage.opt("completion_tokens"))
                val details = usage.opt("completion_tokens_details") as? JSONObject
                metadata.putNonNegativeNumber(
                    "reasoning_tokens",
                    details?.opt("reasoning_tokens") ?: usage.opt("reasoning_tokens"),
                )
            }

            val content = when (rawContent) {
                null -> null.also { metadata.put("content_type", "missing") }
                JSONObject.NULL -> null.also { metadata.put("content_type", "null") }
                is String -> rawContent.also { metadata.put("content_type", "string") }
                is JSONArray -> decodeTextParts(rawContent).also {
                    metadata.put("content_type", if (it == null) "unsupported_parts" else "text_parts")
                }
                is JSONObject -> null.also { metadata.put("content_type", "object") }
                is Number -> null.also { metadata.put("content_type", "number") }
                is Boolean -> null.also { metadata.put("content_type", "boolean") }
                else -> null.also { metadata.put("content_type", "other") }
            }
            metadata.put("content_chars", content?.length ?: 0)

            when {
                finishReason == "length" -> reject("truncated_response")
                finishReason == "content_filter" -> reject("filtered_response")
                hasRefusal -> reject("refusal_response")
                hasToolCalls || finishReason == "tool_calls" || finishReason == "function_call" ->
                    reject("tool_calls_response")
                rawContent == null || rawContent == JSONObject.NULL -> reject("empty_content")
                content == null -> reject("unsupported_content")
                content.all { it.isWhitespace() || it == '\uFEFF' || it == '\u200B' } ->
                    reject("empty_content")
                else -> EyesVisionResult(content, null, metadata)
            }
        } catch (_: Exception) {
            // Provider errors and JSON exception messages can include the response itself.
            reject("invalid_response")
        }
    }

    private fun decodeTextParts(parts: JSONArray): String? {
        val text = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.opt(index) as? JSONObject ?: return null
            if (part.opt("type") != "text") return null
            text.append(part.opt("text") as? String ?: return null)
        }
        // Preserve adjacent fragments exactly, including a JSON command split across parts.
        return text.toString()
    }

    private fun JSONObject.hasNonNull(name: String): Boolean = has(name) && !isNull(name)

    private fun JSONObject.hasRefusal(): Boolean = when (val refusal = opt("refusal")) {
        null, JSONObject.NULL -> false
        is String -> refusal.isNotBlank()
        else -> true
    }

    private fun JSONObject.hasToolCalls(): Boolean = when (val calls = opt("tool_calls")) {
        null, JSONObject.NULL -> false
        is JSONArray -> calls.length() > 0
        else -> true
    }

    private fun JSONObject.putNonNegativeNumber(name: String, value: Any?) {
        val number = value as? Number ?: return
        if (number.toDouble().let { it.isFinite() && it >= 0.0 }) put(name, number)
    }
}
