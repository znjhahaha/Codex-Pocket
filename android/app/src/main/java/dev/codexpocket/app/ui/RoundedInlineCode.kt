package dev.codexpocket.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.MarkwonTheme
import android.text.TextPaint
import android.text.SpannableString
import android.text.SpannedString
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/** Markwon treats zero theme background as a request for its default rectangle. */
internal class RoundedCodeSpan(theme: MarkwonTheme) : CodeSpan(theme) {
    override fun updateDrawState(paint: TextPaint) {
        super.updateDrawState(paint)
        paint.bgColor = android.graphics.Color.TRANSPARENT
    }
}

/** Reserve real layout space at code edges without inserting characters into the source. */
internal class InlineCodeEdgeSpan(val leading: Float, val trailing: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return ceil(paint.measureText(text, start, end) + leading + trailing).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        canvas.drawText(text, start, end, x + leading, y.toFloat(), paint)
    }
}

internal fun withInlineCodeSpacing(source: Spanned, density: Float): Spanned {
    val spans = source.getSpans(0, source.length, CodeSpan::class.java)
    if (spans.isEmpty()) return source
    val result = SpannableString(source)
    spans.forEach { code ->
        val start = result.getSpanStart(code); val end = result.getSpanEnd(code)
        if (start < 0 || end <= start) return@forEach
        val characters = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(source.subSequence(start, end).toString()) }
        characters.first()
        val firstEnd = start + characters.next()
        characters.last()
        val lastStart = start + characters.previous()
        val padding = 5f * density
        if (firstEnd >= end) result.setSpan(InlineCodeEdgeSpan(padding, padding), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        else {
            result.setSpan(InlineCodeEdgeSpan(padding, 0f), start, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(InlineCodeEdgeSpan(0f, padding), lastStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return SpannedString(result)
}

/** Rounded decoration follows reserved edge space; adjacent punctuation stays outside the fill. */
internal fun drawRoundedCode(canvas: Canvas, layout: Layout, text: Spanned, color: Int, density: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    text.getSpans(0, text.length, CodeSpan::class.java).forEach { span ->
        val start = text.getSpanStart(span).coerceAtLeast(0)
        val end = text.getSpanEnd(span).coerceAtMost(text.length)
        if (end > start) for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            val first = layout.getPrimaryHorizontal(from)
            val last = if (to >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(to)
            val metricsPaint = TextPaint(layout.paint).also(span::updateMeasureState)
            val metrics = metricsPaint.fontMetrics
            val baseline = layout.getLineBaseline(line)
            val top = maxOf(layout.getLineTop(line).toFloat(), baseline + metrics.ascent - 3*density)
            val bottom = minOf(layout.getLineBottom(line).toFloat(), baseline + metrics.descent + 3*density)
            canvas.drawRoundRect(minOf(first,last) - (if (from == start) 0f else 4*density), top,
                maxOf(first,last) + (if (to == end) 0f else 4*density), bottom,
                6*density,6*density,paint)
        }
    }
}
