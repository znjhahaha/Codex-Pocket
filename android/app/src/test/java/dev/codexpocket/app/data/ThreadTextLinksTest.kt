package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class ThreadTextLinksTest {
    private val id = "01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473"
    @Test fun markdownAndBareIdsShareCanonicalDestinationAndKeepLabels() {
        val text = "查看 [原任务](codex://threads/$id)，以及 $id"
        val links = threadTextLinks(text)
        assertEquals(2, links.size)
        assertEquals("原任务", links.first().label)
        assertEquals(listOf("codex://threads/$id", "codex://threads/$id"), links.map {it.url})
        assertEquals("$id", text.substring(links.last().start,links.last().end))
    }
    @Test fun literalSnippetsAndFilesCannotBecomeConversationNavigation() {
        val text = "`$id`\n```\ncodex://threads/$id\n```\n[文件](D:/project/$id) D:/project/$id codex://threads/$id?runtimeId=other"
        assertTrue(threadTextLinks(text).isEmpty())
        assertTrue(threadTextLinks(id, listOf(0 until id.length)).isEmpty())
        assertTrue(threadTextLinks("```snippet\n$id").isEmpty())
        assertTrue(threadTextLinks("~~~\n$id\n~~~").isEmpty())
    }
}
