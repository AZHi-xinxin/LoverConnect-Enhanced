package com.lover.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EyesVisionResponseTest {
    @Test
    fun acceptsStringContentWithoutChangingItsText() {
        val content = "  观察正文。\n{\"action\":\"log\",\"message\":\"合成记录\"}  "
        val result = EyesVisionResponse.decode(response(content))

        assertNull(result.rejectionCode)
        assertEquals(content, result.content)
        assertEquals("string", result.metadata.getString("content_type"))
        assertEquals(content.length, result.metadata.getInt("content_chars"))
        assertEquals("stop", result.metadata.getString("finish_reason"))
    }

    @Test
    fun concatenatesOnlyStandardTextPartsWithoutStringifyingTheArray() {
        val first = "{\"action\":\"log\",\"message\":\""
        val second = "合成记录\"}"
        val parts = JSONArray().put(textPart(first)).put(textPart(second))
        val result = EyesVisionResponse.decode(response(parts))

        assertNull(result.rejectionCode)
        assertEquals(first + second, result.content)
        assertEquals("text_parts", result.metadata.getString("content_type"))
        assertEquals(first.length + second.length, result.metadata.getInt("content_chars"))
    }

    @Test
    fun rejectsMixedNonTextAndMalformedPartsWithoutKeepingAPartialBody() {
        val invalidParts = listOf(
            JSONObject().put("type", "image_url").put("image_url", "https://synthetic.invalid/private"),
            JSONObject().put("type", "reasoning").put("text", "SYNTHETIC_REASONING"),
            JSONObject().put("type", "text").put("text", JSONObject().put("value", "nested")),
            JSONObject().put("type", "text").put("text", JSONObject.NULL),
            JSONObject().put("text", "missing type"),
            JSONObject().put("type", "text"),
            "not a part object",
            42,
            JSONObject.NULL,
        )
        for (invalid in invalidParts) {
            for (parts in listOf(
                JSONArray().put(textPart("SYNTHETIC_BODY")).put(invalid),
                JSONArray().put(invalid).put(textPart("SYNTHETIC_BODY")),
            )) {
                val result = EyesVisionResponse.decode(response(parts))
                assertRejected("unsupported_content", result)
                assertEquals("unsupported_parts", result.metadata.getString("content_type"))
                assertFalse(result.metadata.toString().contains("SYNTHETIC"))
                assertFalse(result.metadata.toString().contains("https://"))
            }
        }
    }

    @Test
    fun rejectsObjectsNumbersAndBooleansAsContent() {
        for ((content, type) in listOf(
            JSONObject().put("text", "SYNTHETIC_BODY") to "object",
            42 to "number",
            true to "boolean",
        )) {
            val result = EyesVisionResponse.decode(response(content))
            assertRejected("unsupported_content", result)
            assertEquals(type, result.metadata.getString("content_type"))
        }
    }

    @Test
    fun distinguishesMissingNullAndEmptyContentWithoutUsingReasoningAsFallback() {
        val messages = listOf(
            JSONObject() to "missing",
            JSONObject().put("content", JSONObject.NULL) to "null",
            JSONObject().put("content", " \n\t\uFEFF\u200B") to "string",
            JSONObject().put("content", JSONArray()) to "text_parts",
            JSONObject().put("content", JSONArray().put(textPart(""))) to "text_parts",
        )
        for ((message, type) in messages) {
            message.put("reasoning_content", "SYNTHETIC_REASONING")
            val result = EyesVisionResponse.decode(envelope(message))
            assertRejected("empty_content", result)
            assertEquals(type, result.metadata.getString("content_type"))
            assertEquals("SYNTHETIC_REASONING".length, result.metadata.getInt("reasoning_chars"))
            assertFalse(result.metadata.toString().contains("SYNTHETIC_REASONING"))
        }
    }

    @Test
    fun rejectsIncompleteFilteredAndToolCompletionsEvenWhenTheyContainReadableText() {
        for ((finish, code) in listOf(
            "length" to "truncated_response",
            "content_filter" to "filtered_response",
            "tool_calls" to "tool_calls_response",
            "function_call" to "tool_calls_response",
        )) {
            val result = EyesVisionResponse.decode(response("SYNTHETIC_BODY", finish))
            assertRejected(code, result)
            assertEquals(finish, result.metadata.getString("finish_reason"))
            assertEquals("SYNTHETIC_BODY".length, result.metadata.getInt("content_chars"))
        }
    }

    @Test
    fun detectsRefusalWithoutExposingItsText() {
        for (refusal in listOf("SYNTHETIC_REFUSAL", true, JSONObject())) {
            val result = EyesVisionResponse.decode(envelope(
                JSONObject().put("content", "SYNTHETIC_BODY").put("refusal", refusal),
            ))
            assertRejected("refusal_response", result)
            assertTrue(result.metadata.getBoolean("has_refusal"))
            assertFalse(result.metadata.toString().contains("SYNTHETIC"))
        }
        val refusalPart = JSONArray().put(textPart("partial"))
            .put(JSONObject().put("type", "refusal").put("refusal", "SYNTHETIC_REFUSAL"))
        val result = EyesVisionResponse.decode(response(refusalPart))
        assertRejected("refusal_response", result)
        assertTrue(result.metadata.getBoolean("has_refusal"))
    }

    @Test
    fun rejectsNonemptyToolCallsAndLegacyOrMalformedCallFields() {
        for (field in listOf("tool_calls", "function_call")) {
            for (calls in listOf(
                JSONArray().put(JSONObject().put("arguments", "SYNTHETIC_ARGUMENTS")),
                JSONObject(),
                "",
                false,
            )) {
                val result = EyesVisionResponse.decode(envelope(
                    JSONObject().put("content", "SYNTHETIC_BODY").put(field, calls),
                ))
                assertRejected("tool_calls_response", result)
                assertTrue(result.metadata.getBoolean("has_tool_calls"))
                assertFalse(result.metadata.toString().contains("SYNTHETIC"))
            }
        }
    }

    @Test
    fun emptyToolCallsAndBlankRefusalPlaceholdersKeepNormalContent() {
        for (refusal in listOf("", " \t\r\n ")) {
            val result = EyesVisionResponse.decode(envelope(
                JSONObject().put("content", "合成正文")
                    .put("refusal", refusal)
                    .put("tool_calls", JSONArray())
                    .put("function_call", JSONObject.NULL),
            ))
            assertNull(result.rejectionCode)
            assertEquals("合成正文", result.content)
            assertFalse(result.metadata.getBoolean("has_refusal"))
            assertFalse(result.metadata.getBoolean("has_tool_calls"))
        }
    }

    @Test
    fun standardNullRefusalAndToolFieldsDoNotRejectARegularCompletion() {
        val result = EyesVisionResponse.decode(envelope(
            JSONObject().put("content", "合成正文")
                .put("refusal", JSONObject.NULL)
                .put("tool_calls", JSONObject.NULL)
                .put("function_call", JSONObject.NULL),
        ))
        assertNull(result.rejectionCode)
        assertFalse(result.metadata.getBoolean("has_refusal"))
        assertFalse(result.metadata.getBoolean("has_tool_calls"))
    }

    @Test
    fun acceptsLegacyMissingFinishReasonAndMapsUnknownReasonsToAFixedValue() {
        val missing = EyesVisionResponse.decode(response("合成正文", finish = null))
        assertNull(missing.rejectionCode)
        assertEquals("missing", missing.metadata.getString("finish_reason"))
        for (finish in listOf("SYNTHETIC_UNKNOWN_REASON", 42, JSONObject())) {
            val result = EyesVisionResponse.decode(response("合成正文", finish))
            assertNull(result.rejectionCode)
            assertEquals("other", result.metadata.getString("finish_reason"))
            assertFalse(result.metadata.toString().contains("SYNTHETIC_UNKNOWN_REASON"))
        }
    }

    @Test
    fun metadataContainsOnlyWhitelistedDiagnosticsAndNumericUsage() {
        val message = JSONObject().put("content", "SYNTHETIC_BODY")
            .put("reasoning_content", "SYNTHETIC_REASONING")
            .put("reasoning", "ALIAS")
            .put("url", "https://synthetic.invalid/private")
        val raw = JSONObject(envelope(message, usage = JSONObject()
            .put("completion_tokens", 27)
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 15))))
            .put("model", "SYNTHETIC_MODEL")
            .put("key", "SYNTHETIC_KEY")
            .put("url", "https://synthetic.invalid/private")
            .toString()
        val result = EyesVisionResponse.decode(raw)

        assertNull(result.rejectionCode)
        assertEquals("SYNTHETIC_BODY", result.content)
        assertEquals("SYNTHETIC_REASONING".length + "ALIAS".length, result.metadata.getInt("reasoning_chars"))
        assertEquals(27, result.metadata.getInt("completion_tokens"))
        assertEquals(15, result.metadata.getInt("reasoning_tokens"))
        assertEquals(
            setOf("finish_reason", "content_type", "content_chars", "reasoning_chars",
                "has_refusal", "has_tool_calls", "completion_tokens", "reasoning_tokens"),
            result.metadata.keys().asSequence().toSet(),
        )
        assertFalse(result.metadata.toString().contains("SYNTHETIC"))
        assertFalse(result.metadata.toString().contains("https://"))
    }

    @Test
    fun ignoresNegativeAndNonNumericUsageInsteadOfCoercingIt() {
        for (invalid in listOf(-1, "42", "NaN", true, JSONObject.NULL, JSONObject())) {
            val result = EyesVisionResponse.decode(envelope(
                JSONObject().put("content", "合成正文"),
                usage = JSONObject().put("completion_tokens", invalid)
                    .put("completion_tokens_details", JSONObject().put("reasoning_tokens", invalid)),
            ))
            assertNull(result.rejectionCode)
            assertFalse(result.metadata.has("completion_tokens"))
            assertFalse(result.metadata.has("reasoning_tokens"))
        }
        val result = EyesVisionResponse.decode(envelope(
            JSONObject().put("content", "合成正文"),
            usage = JSONObject().put("completion_tokens", 0).put("reasoning_tokens", 0),
        ))
        assertEquals(0, result.metadata.getInt("completion_tokens"))
        assertEquals(0, result.metadata.getInt("reasoning_tokens"))
    }

    @Test
    fun malformedEnvelopesReturnAFixedCodeWithoutThrowingOrExposingParserMessages() {
        for (invalid in listOf(
            "", "{", "[]", "{}", "null", "{\"choices\":null}", "{\"choices\":[]}",
            "{\"choices\":[42]}", "{\"choices\":[{}]}", "{\"choices\":[{\"message\":null}]}",
            "{\"choices\":[{\"message\":\"SYNTHETIC_SECRET\"}]}",
            response("SYNTHETIC_BODY") + " SYNTHETIC_TRAILING_SECRET",
            response("SYNTHETIC_BODY") + response("second response"),
        )) {
            val result = EyesVisionResponse.decode(invalid)
            assertRejected("invalid_response", result)
            assertFalse(result.metadata.toString().contains("SYNTHETIC"))
            assertEquals("missing", result.metadata.getString("content_type"))
        }
    }

    private fun assertRejected(code: String, result: EyesVisionResult) {
        assertEquals(code, result.rejectionCode)
        assertEquals("", result.content)
    }

    private fun textPart(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

    private fun response(content: Any, finish: Any? = "stop"): String =
        envelope(JSONObject().put("content", content), finish)

    private fun envelope(message: JSONObject, finish: Any? = "stop", usage: JSONObject? = null): String =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).apply {
            if (finish != null) put("finish_reason", finish)
        })).apply {
            if (usage != null) put("usage", usage)
        }.toString()
}
