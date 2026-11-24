package dev.codexpocket.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadLinkEditorTest {
    private val url = "codex://threads/01a207c4-9742-7b63-95b7-9c744483d368"
    @Test fun twoLinksAndAFileReferenceKeepMonotonicOffsetsAndTheLiteralWireText() {
        val text = "文🙂 $url 文件 [$url]($url) tail"
        val start = text.indexOf("文件")
        val ref = buildJsonObject { put("type", "file"); put("name", "文件"); put("path", "D:/file.txt") }
        val span = ReferenceSpan(start, start + 2, "文件", ref)
        val links = threadTextLinks(text, listOf(start until start + 2)).map { EditorThreadLink(it, "任务标题") }
        val display = ThreadLinkVisualTransformation(ReferenceVisualTransformation(listOf(span), ReferenceBlue), links, ReferenceBlue)
            .filter(AnnotatedString(text))
        assertFalse(display.text.text.contains("codex://"))
        assertTrue(display.text.text.contains("文件"))
        for (positions in listOf((0..text.length).map(display.offsetMapping::originalToTransformed),
            (0..display.text.length).map(display.offsetMapping::transformedToOriginal))) {
            assertTrue(positions.zipWithNext().all { (a,b) -> a <= b })
        }
        assertEquals(text.length, display.offsetMapping.transformedToOriginal(display.text.length))
        assertEquals(display.text.length, display.offsetMapping.originalToTransformed(text.length))
        assertEquals(text.length, TextFieldValue(text).text.length)
    }
    @Test fun deletingATitleRemovesItsAddressButOrdinaryTypingAndImeStayIntact() {
        val text = "看 $url 后续"
        val link = threadTextLinks(text).single()
        val links = listOf(EditorThreadLink(link, "任务"))
        val before = TextFieldValue(text, TextRange(link.end))
        val after = TextFieldValue(text.removeRange(link.end - 1, link.end), TextRange(link.end - 1))
        assertEquals(TextFieldValue("看  后续", TextRange(link.start)), editThreadLinkToken(before, after, links))
        val composing = before.copy(composition = TextRange(0, 1))
        assertEquals(after, editThreadLinkToken(composing, after, links))
        val typed = TextFieldValue(text + "!", TextRange(text.length + 1))
        assertEquals(typed, editThreadLinkToken(before, typed, links))
    }
    @Test fun aTitleFromAnotherComputerOrProfileCannotReplaceALink() {
        val link = threadTextLinks(url).single()
        val origin = ThreadKey("pc", "store", "origin")
        val chat = ChatEntity(origin.copy(nativeId = link.nativeId), "runtime", "desktop", "正确标题", "", "", 0, "", false)
        assertEquals("正确标题", threadTextTitle(link, origin, listOf(chat)))
        assertEquals("打开会话", threadTextTitle(link, origin.copy(configStoreId = "other"), listOf(chat)))
        assertEquals("打开会话", threadTextTitle(link, origin.copy(computerId = "other"), listOf(chat)))
        assertEquals("链接标题", threadTextTitle(link.copy(label = "链接标题"), origin, listOf(chat)))
    }
    @Test fun selectingEvenASingleLetterTitleSelectsTheFullAddress() {
        for (title in listOf("A", "AB", "任务标题")) {
            val link = threadTextLinks(url).single()
            val display = ThreadLinkVisualTransformation(ReferenceVisualTransformation(emptyList(), ReferenceBlue),
                listOf(EditorThreadLink(link, title)), ReferenceBlue).filter(AnnotatedString(url))
            assertEquals(0, display.offsetMapping.transformedToOriginal(1))
            assertEquals(url.length, display.offsetMapping.transformedToOriginal(1 + title.length))
        }
    }
    @Test fun adjacentConversationAndFileReferencesKeepTheirOwnIconSlots() {
        val first = "[甲任务]($url)"
        val text = first + "[乙任务]($url)文件"
        val start = text.indexOf("文件")
        val ref = buildJsonObject { put("type", "file"); put("name", "文件"); put("path", "D:/file.txt") }
        val span = ReferenceSpan(start, start + 2, "文件", ref)
        val links = threadTextLinks(text, listOf(start until start + 2)).map { EditorThreadLink(it, it.label!!) }
        val display = ThreadLinkVisualTransformation(ReferenceVisualTransformation(listOf(span), ReferenceBlue), links, ReferenceBlue)
            .filter(AnnotatedString(text))
        assertEquals("\u2003甲任务\u2003乙任务\u2003文件", display.text.text)
        assertEquals(5, display.offsetMapping.originalToTransformed(first.length))
        assertEquals(9, display.offsetMapping.originalToTransformed(start))
        assertEquals(0, display.offsetMapping.transformedToOriginal(1))
        assertEquals(first.length, display.offsetMapping.transformedToOriginal(5))
    }
}
