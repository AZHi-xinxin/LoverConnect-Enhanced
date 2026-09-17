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
}
