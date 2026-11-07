package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class AnswerDirectivesTest {
    @Test fun fileCitationsBecomeFilesAndLeaveNoRawDirective() {
        val text = """已完成 **10 页实验报告**。

:codex-file-citation{path="C:/Users/me/out/报告.docx" purpose="output"}

::codex-file-citation{path="C:/Users/me/out/图.png" purpose="output"}

[下载工程](https://example.invalid/a.zip)"""
        val parsed = AnswerDirectives.parse(text)
        assertFalse(parsed.text.contains("codex-file-citation"))
        assertTrue(parsed.text.startsWith("已完成 **10 页实验报告**。"))
        assertTrue(parsed.text.endsWith("[下载工程](https://example.invalid/a.zip)"))
        assertEquals(listOf("报告.docx", "图.png"), parsed.files.map { it.name })
        assertEquals(listOf(false, true), parsed.files.map { it.image })
        assertEquals("C:/Users/me/out/报告.docx", parsed.files.first().path)
    }

    @Test fun inlineAndUnknownDirectivesAreStrippedButCodeFencesAreKept() {
        val text = "见文件 :codex-file-citation{path=\"a/b.kt\"} 与 ::codex-git-stage{cwd=\"/x\"}\n```\n:codex-file-citation{path=\"keep\"}\n```"
        val parsed = AnswerDirectives.parse(text)
        assertEquals("见文件  与 \n```\n:codex-file-citation{path=\"keep\"}\n```", parsed.text)
        assertEquals(listOf("b.kt"), parsed.files.map { it.name })
    }

    @Test fun duplicatesFoldAndPlainTextIsUntouched() {
        val dup = ":codex-file-citation{path=\"x.md\"}\n:codex-file-citation{path=\"x.md\"}"
        assertEquals(1, AnswerDirectives.parse(dup).files.size)
        assertEquals("", AnswerDirectives.parse(dup).text)
        val plain = "普通回答，含 {braces} 与 :colon"
        assertSame(plain, AnswerDirectives.parse(plain).text)
    }
}
