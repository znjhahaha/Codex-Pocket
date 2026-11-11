package dev.codexpocket.app.ui

import android.graphics.Color
import android.text.style.ClickableSpan
import dev.codexpocket.app.data.ThreadDeepLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThreadDeepLinkRenderingTest {
    private val url = "codex://threads/01a1c7fb-7bda-7a71-a782-a020229c9573"
    private fun renderer() = MarkdownRenderCache(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, Color.GRAY)

    @Test fun conversationLinksAreNeverFileBadges() {
        assertNotNull("conversation link needs a distinct badge", markdownLinkBadge(url))
        assertTrue(markdownLinkBadge(url)!!.conversation)
        assertFalse(markdownLinkBadge(url)!!.web)
        assertNull(markdownLinkBadge("codex://threads/not-valid"))
        assertEquals("KT", markdownLinkBadge("D:/work/Main.kt:12")?.label)
        assertTrue(markdownLinkBadge("https://example.invalid")!!.web)
    }

    @Test fun markdownLabelStaysReadableAndRawUriGetsConversationLabel() = runBlocking {
        val rendered = renderer().render("thread-links", "[继续测试]($url) · <$url>")
        assertEquals("继续测试 · 打开会话", rendered.spans.toString())
        assertEquals(listOf(url, url), rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkMetadata::class.java).map { it.destination })
        assertEquals(2, rendered.spans.getSpans(0, rendered.spans.length, ClickableSpan::class.java).size)
        assertTrue(rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkBadgeSpan::class.java).all { it.badge.conversation })
    }

    @Test fun plainTextLinksBecomeClickableButCodeAndWebExamplesRemainLiteral() = runBlocking {
        val rendered = renderer().render("plain-links", "继续 $url。\n\n`$url`\n\n```text\n$url\n```\n\nhttps://example.invalid/$url")
        val destinations = rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkMetadata::class.java).map { it.destination }
        assertEquals(listOf(url), destinations)
        assertTrue(rendered.spans.toString().startsWith("继续 打开会话。"))
        assertEquals(listOf("$url\n"), rendered.code)
    }

    @Test fun invalidCodexLinksRemainInspectableAndClickableForClearRejection() = runBlocking {
        val bad = "codex://threads/invalid"
        val rendered = renderer().render("bad", "[目标]($bad)")
        assertEquals("目标", rendered.spans.toString())
        assertEquals(bad, rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkMetadata::class.java).single().destination)
        assertEquals(0, rendered.spans.getSpans(0, rendered.spans.length, MarkdownLinkBadgeSpan::class.java).size)
        assertNull(ThreadDeepLink.parse(bad))
    }
}
