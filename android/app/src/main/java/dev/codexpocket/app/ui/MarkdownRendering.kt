package dev.codexpocket.app.ui

import android.content.Context
import android.text.Spanned
import android.text.SpannedString
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import dev.codexpocket.app.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Document
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Heading
import org.commonmark.node.OrderedList

internal data class MarkdownPart(val spans: Spanned? = null, val code: String? = null, val language: String = "")
internal data class MarkdownHeading(val title: String, val level: Int, val partIndex: Int, val anchor: String)
internal data class RenderedMarkdown(val source: String, val spans: Spanned, val code: List<String>, val parts: List<MarkdownPart> = emptyList(), val images: List<String> = emptyList(), val headings: List<MarkdownHeading> = emptyList())

/** All parser, visitor, highlighter and cache access is confined to this dispatcher. */
internal class MarkdownRenderCache(context: Context, foreground: Int, keyword: Int, literal: Int) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cache = LinkedHashMap<String, RenderedMarkdown>(32, .75f, true)
    private var cachedCharacters = 0
    private val syntax = ReadingSyntax(foreground, keyword, literal)
    val markwon: Markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) { builder.codeTextColor(foreground) }
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.syntaxHighlight { language, code -> syntax.spans(language, code) }
                builder.linkResolver { view, link ->
                    val handler = (view as? MarkdownView)?.onLink
                    if (handler != null) handler(link)
                    else if (link.startsWith("https://") || link.startsWith("http://")) LinkResolverDef().resolve(view, link)
                }
            }
        }).build()

    suspend fun render(messageKey: String, source: String): RenderedMarkdown = withContext(dispatcher) {
        cache[messageKey]?.takeIf { it.source == source }?.let { return@withContext it }
        ensureActive()
        val document = markwon.parse(source)
        val blocks = mutableListOf<String>()
        val images = mutableListOf<String>()
        val lists = mutableListOf<Pair<OrderedList, Int>>()
        document.accept(object : AbstractVisitor() {
            override fun visit(node: FencedCodeBlock) { blocks += node.literal }
            override fun visit(node: IndentedCodeBlock) { blocks += node.literal }
            override fun visit(node: Image) { images += node.destination }
            override fun visit(node: OrderedList) { lists += node to node.startNumber; visitChildren(node) }
        })
        ensureActive()
        // Markwon advances startNumber while numbering items; restore it so the per-part render below starts at 1 again.
        val full = SpannedString(markwon.render(document))
        lists.forEach { (list, start) -> list.startNumber = start }
        val parts = mutableListOf<MarkdownPart>()
        val headings = mutableListOf<MarkdownHeading>()
        val usedAnchors = mutableMapOf<String, Int>()
        var prose = Document()
        fun flush() { if (prose.firstChild != null) { parts += MarkdownPart(spans = SpannedString(markwon.render(prose))); prose = Document() } }
        var node: Node? = document.firstChild
        while (node != null) {
            val next = node.next
            when (val current = node) {
                is FencedCodeBlock -> { flush(); parts += MarkdownPart(code = current.literal, language = current.info.orEmpty().substringBefore(' ')) }
                is IndentedCodeBlock -> { flush(); parts += MarkdownPart(code = current.literal) }
                is Heading -> {
                    flush()
                    val title = markwon.render(current).toString().trim()
                    val base = title.lowercase().replace(Regex("[^\\p{L}\\p{N}_ -]"), "").replace(' ', '-')
                    val occurrence = usedAnchors[base] ?: 0
                    usedAnchors[base] = occurrence + 1
                    headings += MarkdownHeading(title, current.level, parts.size, if (occurrence == 0) base else "$base-$occurrence")
                    prose.appendChild(current)
                }
                else -> prose.appendChild(current)
            }
            node = next
        }
        flush()
        val rendered = RenderedMarkdown(source, full, blocks, parts, images.distinct(), headings)
        ensureActive()
        cache.remove(messageKey)?.let { cachedCharacters -= it.source.length + it.spans.length }
        val size = source.length + rendered.spans.length
        if (size <= 1_000_000) {
            cache[messageKey] = rendered
            cachedCharacters += size
            while (cache.size > 64 || cachedCharacters > 1_000_000) {
                val oldest = cache.entries.iterator().next()
                cachedCharacters -= oldest.value.source.length + oldest.value.spans.length
                cache.remove(oldest.key)
            }
        }
        rendered
    }

    suspend fun highlight(code: String, language: String?): AnnotatedString = withContext(dispatcher) {
        ensureActive()
        syntax.spans(language, code).codeAnnotation()
    }
}

internal val LocalMarkdownCache = staticCompositionLocalOf<MarkdownRenderCache?> { null }

@Composable
internal fun ProvideMarkdownRendering(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val keyword = MaterialTheme.colorScheme.primary.toArgb()
    val literal = MaterialTheme.colorScheme.tertiary.toArgb()
    val renderer = remember(context, density.density, density.fontScale, foreground, keyword, literal) {
        MarkdownRenderCache(context, foreground, keyword, literal)
    }
    CompositionLocalProvider(LocalMarkdownCache provides renderer, content = content)
}

private class MarkdownView(context: Context) : TextView(context) { var onLink: ((String) -> Unit)? = null }

@Composable
internal fun MarkdownText(text: String, messageKey: String) {
    val context = LocalContext.current
    val renderer = checkNotNull(LocalMarkdownCache.current)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val clipboard = LocalClipboardManager.current
    var rendered by remember(renderer, messageKey) { mutableStateOf<RenderedMarkdown?>(null) }
    LaunchedEffect(renderer, messageKey, text) {
        // Cancellation at withContext's return prevents old stream revisions reaching the view.
        rendered = renderer.render(messageKey, text)
    }
    val current = rendered
    val parts = current?.parts.orEmpty()
    // Until the off-thread render lands, show the prose without Markdown punctuation instead of flashing `**`.
    if (current == null) Text(remember(text) { markdownPreview(text) }, style = MaterialTheme.typography.bodyLarge)
    parts.forEachIndexed { index, part ->
        key(messageKey, index) {
            if (part.code != null) CodeBlock(part.code, part.language)
            else MarkdownSpans(part.spans ?: SpannedString(""), renderer, color, link)
        }
    }
    current?.images?.forEach { source -> RemoteTranscriptImage(source) }
    // Nested list/quote code keeps its original structure and still has an exact copy action.
    val topCodes = parts.mapNotNull { it.code }.toMutableList()
    current?.code?.forEachIndexed { index, code ->
        if (topCodes.contains(code)) topCodes.remove(code) else PocketTextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text(context.getString(R.string.copy_code_number, index + 1)) }
    }
}

@Composable
internal fun MarkdownSpans(spans: Spanned, renderer: MarkdownRenderCache, color: Int, link: Int, onLink: ((String) -> Unit)? = null) {
    AndroidView(
        factory = { MarkdownView(it).apply { textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.24f); setPadding(0, 0, 0, 0) } },
        update = { view ->
            view.onLink = onLink
            view.setTextColor(color)
            view.setLinkTextColor(link)
            if (view.tag !== spans) { renderer.markwon.setParsedMarkdown(view, spans); view.tag = spans }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val renderer = LocalMarkdownCache.current
    val highlighted by produceState(AnnotatedString(code.trimEnd('\n')), code, language, renderer) {
        value = renderer?.highlight(code.trimEnd('\n'), language) ?: AnnotatedString(code.trimEnd('\n'))
    }
    val display = remember(highlighted, code) { highlighted.takeIf { it.text == code.trimEnd('\n') } ?: AnnotatedString(code.trimEnd('\n')) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("code-block")) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { stringResource(R.string.v02_code) }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PocketTextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) { Text(stringResource(if (copied) R.string.v02_copy_done else R.string.copy_code)) }
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(display, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
            }
        }
    }
}

private val previewLink = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val previewEmphasis = Regex("""(\*\*|__|~~)(.+?)\1""")
private val previewLinePrefix = Regex("""(?m)^\s{0,3}(?:#{1,6}\s+|>\s?)""")
private val previewBullet = Regex("""(?m)^(\s*)[-*+]\s+(?=\S)""")

/** Plain-text stand-in shown for the few frames before the parsed Markdown arrives. Bounded work on the UI thread. */
internal fun markdownPreview(text: String): String {
    val head = if (text.length > 4_000) text.substring(0, 4_000) else text
    return dev.codexpocket.app.data.AnswerDirectives.parse(head).text.replace(previewLink, "$1").replace(previewEmphasis, "$2").replace(previewLinePrefix, "")
        .replace(previewBullet, "$1• ").replace("`", "") + if (head.length < text.length) "…" else ""
}
