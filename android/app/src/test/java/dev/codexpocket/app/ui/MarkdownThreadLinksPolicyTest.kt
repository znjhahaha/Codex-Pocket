package dev.codexpocket.app.ui

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownThreadLinksPolicyTest {
    private val url = "codex://threads/01a1c7fb-7bda-7a71-a782-a020229c9573"
    private fun links(source: String): List<Pair<String, String>> {
        val document = Parser.builder().build().parse(source)
        decorateThreadLinks(document)
        val links = mutableListOf<Pair<String, String>>()
        document.accept(object : AbstractVisitor() {
            override fun visit(node: Link) { links += node.destination to ((node.firstChild as? Text)?.literal ?: ""); visitChildren(node) }
        })
        return links
    }
    @Test fun bareConversationUriIsAnUnderstandableClickableEntry() {
        assertEquals(listOf(url to "打开会话"), links("继续 $url。"))
    }
    @Test fun parsedMarkdownAndAutolinksKeepDestinationAndFriendlyLabels() {
        assertEquals(listOf(url to "我的测试", url to "打开会话"), links("[我的测试]($url) <$url>"))
    }
    @Test fun literalCodeAndImageDestinationsNeverBecomeConversationLinks() {
        assertTrue(links("`$url`\n\n```text\n$url\n```\n\n![图片]($url)").isEmpty())
    }
    @Test fun uriEmbeddedInWebAddressAndInvalidPathsAreNotAutoLinked() {
        assertTrue(links("https://example.invalid/$url codex://threads/invalid codex://threads/${url.substringAfterLast('/')}?rpc=x").isEmpty())
    }
    @Test fun fileWebAndAnchorLinksKeepExactTargets() {
        assertEquals(listOf("D:/work/Main.kt:12" to "文件", "https://example.invalid" to "网页", "#title" to "标题"),
            links("[文件](D:/work/Main.kt:12) [网页](https://example.invalid) [标题](#title)"))
    }
}
