package dev.codexpocket.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import dev.codexpocket.app.data.ThreadDeepLink

internal data class MarkdownLinkMetadata(val destination: String)
internal data class LinkBadge(val label: String, val web: Boolean = false, val conversation: Boolean = false)

internal fun markdownLinkBadge(destination: String): LinkBadge? {
    if (ThreadDeepLink.parse(destination) != null) return LinkBadge("", conversation = true)
    if (destination.startsWith("https://", true) || destination.startsWith("http://", true)) return LinkBadge("", web = true)
    if (destination.startsWith('#') || destination.isBlank()) return null
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(destination) &&
        !Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(destination) && !destination.startsWith("file:/")) return null
    val path = runCatching { java.net.URLDecoder.decode(destination.replace("+", "%2B"), "UTF-8") }.getOrDefault(destination)
        .substringBefore('#').substringBefore('?').replace('\\', '/').replace(Regex(":\\d+(?::\\d+)?$"), "")
    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase()
    val label = when (extension) {
        "rs" -> "RS"; "kt", "kts" -> "KT"; "java" -> "J"; "ts", "tsx" -> "TS"
        "js", "jsx", "mjs", "cjs" -> "JS"; "py" -> "PY"; "go" -> "GO"
        "cs" -> "C#"; "c", "cpp", "cc", "h", "hpp" -> "C"; "swift" -> "SW"
        "md", "markdown" -> "M"; "json", "yaml", "yml", "toml" -> "{}"
        "html", "xml", "svg" -> "<>"; "css", "scss" -> "#"; "ps1", "sh", "bash", "bat" -> ">_"
        else -> ""
    }
    return if (label.isNotBlank() || '/' in path || extension.isNotBlank()) LinkBadge(label) else null
}

/** Decorate a glyph rather than inserting text: wrapping, selection and copying stay intact. */
internal class MarkdownLinkBadgeSpan(val badge: LinkBadge, private val leading: Float = 0f, private val trailing: Float = 0f) : ReplacementSpan() {
    private fun width(paint: Paint) = paint.textSize * 1.12f
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return ceil(paint.measureText(text, start, end) + width(paint) + leading + trailing).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val icon = Paint(paint).apply { isAntiAlias = true; color = (paint as? TextPaint)?.linkColor ?: paint.color; isUnderlineText = false }
        val size = paint.textSize * .82f
        val left = x + leading
        val cy = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val rect = RectF(left, cy - size / 2, left + size, cy + size / 2)
        icon.style = Paint.Style.STROKE; icon.strokeWidth = size * .075f
        if (badge.web) {
            canvas.drawOval(rect, icon)
            canvas.drawOval(RectF(left + size * .28f, rect.top, left + size * .72f, rect.bottom), icon)
            canvas.drawLine(left, cy, left + size, cy, icon)
        } else if (badge.conversation) {
            canvas.drawRoundRect(RectF(left, rect.top, left + size, rect.bottom - size * .18f), size * .16f, size * .16f, icon)
            canvas.drawLine(left + size * .2f, rect.bottom - size * .18f, left + size * .2f, rect.bottom, icon)
            canvas.drawLine(left + size * .2f, rect.bottom, left + size * .43f, rect.bottom - size * .18f, icon)
            canvas.drawLine(left + size * .23f, cy - size * .16f, left + size * .77f, cy - size * .16f, icon)
        } else {
            canvas.drawRoundRect(rect, size * .2f, size * .2f, icon)
            if (badge.label.isBlank()) {
                canvas.drawLine(left + size * .25f, cy - size * .15f, left + size * .75f, cy - size * .15f, icon)
                canvas.drawLine(left + size * .25f, cy + size * .15f, left + size * .6f, cy + size * .15f, icon)
            } else {
                icon.style = Paint.Style.FILL; icon.textSize = size * if (badge.label.length > 1) .53f else .64f
                icon.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                canvas.drawText(badge.label, left + (size - icon.measureText(badge.label)) / 2,
                    cy - (icon.fontMetrics.ascent + icon.fontMetrics.descent) / 2, icon)
            }
        }
        canvas.drawText(text, start, end, x + leading + width(paint), y.toFloat(), paint)
    }
}

internal fun withMarkdownDecorations(source: Spanned, density: Float): Spanned {
    val spaced = withInlineCodeSpacing(source, density)
    val result = SpannableString(spaced)
    spaced.getSpans(0, spaced.length, MarkdownLinkMetadata::class.java).forEach { link ->
        val badge = markdownLinkBadge(link.destination) ?: return@forEach
        val start = spaced.getSpanStart(link); val end = spaced.getSpanEnd(link)
        if (start < 0 || end <= start) return@forEach
        val graphemes = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(spaced.subSequence(start, end).toString()) }
        graphemes.first()
        val firstEnd = start + graphemes.next()
        val replacements = result.getSpans(start, firstEnd, ReplacementSpan::class.java)
        if (replacements.any { it !is InlineCodeEdgeSpan }) return@forEach
        val edge = replacements.filterIsInstance<InlineCodeEdgeSpan>().firstOrNull()
        edge?.let(result::removeSpan)
        result.setSpan(MarkdownLinkBadgeSpan(badge, edge?.leading ?: 0f, edge?.trailing ?: 0f), start, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return result
}
