package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EyesResponseParserTest {
    @Test
    fun plainJsonExtractsOnlyDiaryText() {
        assertEquals(
            EyesAnalysisResponse("log", "正在看旅行照片。"),
            EyesResponseParser.parse("""{"action":"log","message":"正在看旅行照片。"}"""),
        )
    }

    @Test
    fun jsonAndUnlabelledFencesKeepPopupAction() {
        for (language in listOf("json", "JSON", "")) {
            assertEquals(
                EyesAnalysisResponse("popup", "该休息一下啦。"),
                EyesResponseParser.parse("```$language\n{\"action\":\"popup\",\"message\":\"该休息一下啦。\"}\n```"),
            )
        }
    }

    @Test
    fun explanatoryTextAroundCommandIsNotWrittenToDiary() {
        assertEquals(
            EyesAnalysisResponse("notify", "记得充电。"),
            EyesResponseParser.parse("分析结果：\n```json\n{\"action\":\"notify\",\"message\":\"记得充电。\"}\n```\n以上是观察结果。"),
        )
    }

    @Test
    fun naturalLanguageKeepsPunctuationAndParagraphs() {
        val text = "她在读一篇故事，写着「晚安」。\n屏幕上还有 {草稿} 标记。"
        assertEquals(EyesAnalysisResponse("log", text), EyesResponseParser.parse("  $text\n"))
    }

    @Test
    fun missingNullNonStringAndBlankMessagesAreSkipped() {
        for (response in listOf(
            "", " \n\t", "\uFEFF\u200B", "{}", "{\"action\":\"log\"}",
            "{\"action\":\"popup\",\"message\":null}",
            "{\"message\":42}", "{\"message\":{\"text\":\"hello\"}}",
            "{\"message\":[]}", "{\"message\":\"\"}", "{\"message\":\"  \\n \\t\"}",
            "{\"action\":\"none\",\"message\":\"\"}", "```json\n\n```", "```\n\n```",
        )) {
            assertNull(response, EyesResponseParser.parse(response))
        }
    }

    @Test
    fun malformedCommandsAreNeverLoggedAsRawText() {
        for (response in listOf(
            "{\"action\":\"log\",\"message\":\"截断的正文",
            "```json\n{\"action\":\"log\",\"message\":\"正文\"",
            "结果：{\"action\":\"popup\",\"message\":\"看到\"未转义\"双引号\"}",
            "\"action\": \"log\", \"message\": \"缺少大括号\"",
            "{\"action\":\"log\",\"message\":\"第一条\"} {\"message\":\"第二条\"}",
            "[{\"action\":\"popup\",\"message\":\"数组不是单个操作\"}]",
        )) {
            assertNull(response, EyesResponseParser.parse(response))
        }
    }

    @Test
    fun escapedQuotesBracesAndNewlinesInMessageAreDecoded() {
        assertEquals(
            EyesAnalysisResponse("log", "她读到 \"{晚安}\"。\n准备睡觉。"),
            EyesResponseParser.parse("""{"action":"log","message":"她读到 \"{晚安}\"。\n准备睡觉。"}"""),
        )
    }

    @Test
    fun actionAndReasonArePreservedForAlertPolicy() {
        for (action in listOf("log", "notify", "popup", "none")) {
            assertEquals(
                EyesAnalysisResponse(action, "休息一下。", "interesting_content"),
                EyesResponseParser.parse("""{"action":" $action ","reason":"interesting_content","message":" 休息一下。 "}"""),
            )
        }
    }

    @Test
    fun missingOrUnknownActionFallsBackToDiaryOnly() {
        for (actionField in listOf("", "\"action\":\"unsupported\",", "\"action\":null,")) {
            assertEquals(
                EyesAnalysisResponse("log", "普通记录。"),
                EyesResponseParser.parse("{$actionField\"message\":\"普通记录。\"}"),
            )
        }
    }

    @Test
    fun writerGuardRejectsWhitespaceAndInvisibleOnlyText() {
        for (content in listOf("", " \r\n\t ", "\uFEFF", "\u200B", " \uFEFF\u200B ")) {
            assertNull(EyesDiaryText.nonBlank(content))
        }
        assertEquals("有效正文。", EyesDiaryText.nonBlank(" \n有效正文。\n "))
    }

    @Test
    fun exactBoxWrapperPreservesActionMessageAndReason() {
        val result = EyesResponseParser.parseDetailed(
            """<|begin_of_box|>{"action":"popup","reason":"interesting_content","message":"她正在看旅行照片。"}<|end_of_box|>""",
        )
        assertEquals(EyesAnalysisResponse("popup", "她正在看旅行照片。", "interesting_content"), result.analysis)
        assertNull(result.rejectionCode)
    }

    @Test
    fun completeThinkingWithJsonExamplesIsRemovedBeforeSelectingTheFinalObject() {
        val response = """<think>格式参考：{"action":"log","message":"示例"}。尚未完成的 { 也只是推理。</think>
            |<|begin_of_box|>{"action":"popup","message":"最终正文。"}<|end_of_box|>
        """.trimMargin()
        assertEquals(EyesAnalysisResponse("popup", "最终正文。"), EyesResponseParser.parse(response))
    }

    @Test
    fun completeThinkingCanPrecedePlainLanguageButIsNeverTheDiary() {
        assertEquals(
            EyesAnalysisResponse("log", "她正在阅读一本书。"),
            EyesResponseParser.parse("<think>先看屏幕。</think><think>决定只记录。</think>她正在阅读一本书。"),
        )
        assertRejected("<think>只有推理，没有最终回答。</think>", "empty_content")
    }

    @Test
    fun incompleteOrUnbalancedThinkingIsNotLoggedOrExecuted() {
        for (response in listOf(
            "<think>还在思考，包含 {\"message\":\"不能执行\"}",
            "<think", "</think>没有开头", "<think>外层<think>内层</think>",
        )) {
            assertRejected(response, "incomplete_reasoning")
        }
    }

    @Test
    fun tagsAndEscapedBracesInsideTheDiaryMessageRemainLiteralText() {
        val response = """{"action":"log","message":"页面写着 <think>这只是文字</think> 和 \"{草稿}\"。","metadata":{"count":2,"items":["}","{"]}}"""
        assertEquals(
            EyesAnalysisResponse("log", "页面写着 <think>这只是文字</think> 和 \"{草稿}\"。"),
            EyesResponseParser.parse(response),
        )
    }

    @Test
    fun multipleFinalContainersAreRejectedInsteadOfGuessingOne() {
        for (response in listOf(
            """{"message":"第一条"} {"message":"第二条"}""",
            """说明：{"format":"example"} 最终：{"message":"正文"}""",
            """[] {"message":"正文"}""",
        )) {
            assertRejected(response, "ambiguous_json")
        }
    }

    @Test
    fun arraysObjectsWithoutMessagesAndNullCannotBecomeRawDiaryEntries() {
        for (response in listOf(
            "[]", "[null,true,42]", "null",
            """[{"type":"text","text":"{\"action\":\"log\",\"message\":\"正文\"}"}]""",
        )) {
            assertRejected(response, "unsupported_structure")
        }
        assertRejected("""{"text":"正文"}""", "missing_message")
        assertRejected("结果：{\"text\":\"正文\"}", "missing_message")
    }

    @Test
    fun rejectionCodesDistinguishEmptyMissingNonStringAndMalformedContent() {
        assertRejected(" \n ", "empty_content")
        assertRejected("{}", "missing_message")
        assertRejected("{\"message\":null}", "non_string_message")
        assertRejected("{\"message\":[]}", "non_string_message")
        assertRejected("{\"message\":\" \"}", "empty_message")
        assertRejected("{\"message\":\"还没输出完", "incomplete_json")
        assertRejected("{\"message\":\"有效\"", "incomplete_json")
        assertRejected("{\"message\"=\"有效\"}", "invalid_json")
        assertRejected("\"message\": \"缺少对象外壳\"", "invalid_json")
    }

    @Test
    fun invalidJsonIsNotRepairedAndTrailingCommasAreRejectedOnEveryRuntime() {
        for (response in listOf(
            """{"message":"正文",}""",
            """{"message":"正文","metadata":{"count":1,}}""",
            """{"message":"正文","items":[1,]}""",
            """{"message":"正文","count":01}""",
            """{'message':'正文'}""",
            """{message:"正文"}""",
            """{"message":"正文";"action":"log"}""",
            """{"message":"正文","count":NaN}""",
        )) {
            assertRejected(response, "invalid_json")
        }
    }

    @Test
    fun ordinaryNaturalLanguageWithNonJsonBracketsRemainsAccepted() {
        val text = "[小L观察] 她在看一页笔记，上面有 {草稿} 标记。"
        val result = EyesResponseParser.parseDetailed(text)
        assertEquals(EyesAnalysisResponse("log", text), result.analysis)
        assertNull(result.rejectionCode)
    }

    private fun assertRejected(response: String, code: String) {
        val result = EyesResponseParser.parseDetailed(response)
        assertNull(response, result.analysis)
        assertEquals(response, code, result.rejectionCode)
        assertNull(response, EyesResponseParser.parse(response))
    }
}
