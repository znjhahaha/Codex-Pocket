package dev.codexpocket.app.ui

import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MarkdownRenderingTest {
    private fun renderer() = MarkdownRenderCache(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, Color.GRAY)

    @Test fun fileAndWebLinksHaveBadgesWithoutChangingCopyTextOrClickSpans() = runBlocking {
        val rendered = renderer().render("links", "已完成 [升级协调模块](/D:/codex2/upgrade.rs:12) · [官方文档](https://example.invalid) · [标题](#title)")
        assertEquals("已完成 升级协调模块 · 官方文档 · 标题", rendered.spans.toString())
        val badges = rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkBadgeSpan::class.java).sortedBy { rendered.spans.getSpanStart(it) }
        assertEquals(2, badges.size)
        assertEquals("RS", badges.first().badge.label); assertTrue(badges.last().badge.web)
        assertEquals(3, rendered.spans.getSpans(0, rendered.spans.length, android.text.style.ClickableSpan::class.java).size)
        assertEquals(2, rendered.parts.single().spans!!.getSpans(0, rendered.spans.length, MarkdownLinkBadgeSpan::class.java).size)
    }

    @Test fun codeLinkBadgeSharesEdgeSpacingAndKeepsGraphemesIntact() = runBlocking {
        val rendered = renderer().render("code-link", "[`upgrade.rs`](/D:/space%20name/upgrade.rs#L12)")
        val badge = rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkBadgeSpan::class.java).single()
        assertEquals("upgrade.rs", rendered.spans.toString())
        assertEquals("RS", badge.badge.label)
        assertEquals(1, rendered.spans.getSpans(0, 1, android.text.style.ReplacementSpan::class.java).size)
        assertNull(markdownLinkBadge("javascript:alert(1)"))
        assertNull(markdownLinkBadge("#local"))
        assertEquals("KT", markdownLinkBadge("C:\\app\\Main.kt:12:3")?.label)
    }

    @Test fun cacheKeepsUnchangedMessagesAndReplacesOnlyChangedRevision() = runBlocking {
        val renderer = renderer()
        val first = renderer.render("chat-a/item", "**one**")
        val other = renderer.render("chat-b/item", "two")
        assertSame(first, renderer.render("chat-a/item", "**one**"))
        val updated = renderer.render("chat-a/item", "**one updated**")
        assertNotSame(first, updated)
        assertEquals("one updated", updated.spans.toString())
        assertSame(other, renderer.render("chat-b/item", "two"))
    }

    @Test fun copyBlocksFollowParsedFencesAndIndentedCodeIncludingOpenStreamingFence() = runBlocking {
        val renderer = renderer()
        val result = renderer.render("message", "Inline `code`\n\n~~~kotlin\nval x = 1\n~~~\n\n    indented\n\n```text\nstreaming")
        assertEquals(listOf("val x = 1\n", "indented\n", "streaming\n"), result.code)
    }

    @Test fun evictedMessageRendersAgainWithoutReplacingOtherChatsContent() = runBlocking {
        val renderer = renderer()
        val original = renderer.render("first", "original")
        repeat(65) { renderer.render("message-$it", "value $it") }
        val reloaded = renderer.render("first", "original")
        assertNotSame(original, reloaded)
        assertEquals("original", reloaded.spans.toString())
    }
    @Test fun codePartsKeepExactCopyTextAndInlinePosition() = runBlocking {
        val rendered = renderer().render("parts", "Before\n\n```kotlin\nval a = 1\n```\n\nAfter")
        assertEquals(3, rendered.parts.size)
        assertEquals("Before", rendered.parts[0].spans.toString())
        assertEquals("val a = 1\n", rendered.parts[1].code)
        assertEquals("kotlin", rendered.parts[1].language)
        assertEquals("After", rendered.parts[2].spans.toString())
    }
    @Test fun orderedListInPartsStartsAtItsSourceNumber() = runBlocking {
        val rendered = renderer().render("list", "# Use\n\n1. one\n2. two\n3. three")
        val spans = rendered.parts.last().spans!!
        val numbers = spans.getSpans(0, spans.length, io.noties.markwon.core.spans.OrderedListItemSpan::class.java).sortedBy { spans.getSpanStart(it) }.map { span ->
            (io.noties.markwon.core.spans.OrderedListItemSpan::class.java.getDeclaredField("number").apply { isAccessible = true }.get(span) as String).trim(' ', ' ')
        }
        assertEquals(listOf("1.", "2.", "3."), numbers)
    }
    @Test fun remoteImagesRemainReferencesUntilAnExplicitUiGesture() = runBlocking {
        val rendered = renderer().render("images", "![diagram](https://example.invalid/diagram.png)")
        assertEquals(listOf("https://example.invalid/diagram.png"), rendered.images)
    }
}
