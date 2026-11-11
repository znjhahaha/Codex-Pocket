package dev.codexpocket.app.ui

import dev.codexpocket.app.data.ThreadDeepLink
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text

/** Work on the parsed tree so inline/fenced code, images and existing URLs keep their meaning. */
internal fun decorateThreadLinks(document: Node) {
    val texts = mutableListOf<Text>()
    document.accept(object : AbstractVisitor() {
        override fun visit(node: Link) {
            if (ThreadDeepLink.parse(node.destination) != null) {
                val text = node.firstChild as? Text
                if (text != null && text.next == null && (text.literal == node.destination || text.literal.isBlank()))
                    text.literal = "打开会话"
            }
        }
        override fun visit(node: Image) = Unit
        override fun visit(node: Text) { texts += node }
    })
    val candidate = Regex("(?i)(?<![\\p{L}\\p{N}_:/?.%=-])codex://[^\\s<>\\\"`\\[\\]()，。；、！？]+")
    texts.forEach { text ->
        val matches = candidate.findAll(text.literal).filter { ThreadDeepLink.parse(it.value) != null }.toList()
        if (matches.isEmpty()) return@forEach
        var start = 0
        matches.forEach { match ->
            if (match.range.first > start) text.insertBefore(Text(text.literal.substring(start, match.range.first)))
            text.insertBefore(Link(match.value, null).apply { appendChild(Text("打开会话")) })
            start = match.range.last + 1
        }
        if (start < text.literal.length) text.insertBefore(Text(text.literal.substring(start)))
        text.unlink()
    }
}
