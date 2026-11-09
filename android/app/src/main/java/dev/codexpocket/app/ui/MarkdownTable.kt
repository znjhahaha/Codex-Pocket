package dev.codexpocket.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil

internal data class MarkdownTableData(val rows: List<List<Spanned>>, val headers: Set<Int> = setOf(0)) {
    val columns get() = rows.maxOfOrNull { it.size } ?: 0
    val plainText get() = rows.joinToString("\n") { row -> row.joinToString("\t") { it.toString() } }
}

/** Column minimums depend on actual text/font, so even a three-column table can overflow safely. */
internal fun tableColumnWidths(table: MarkdownTableData, viewport: Int, paint: TextPaint, density: Float): IntArray {
    val widths = IntArray(table.columns) { column ->
        val natural = table.rows.maxOfOrNull { row -> row.getOrNull(column)?.let { Layout.getDesiredWidth(it, paint) } ?: 0f } ?: 0f
        ceil(natural.coerceIn(56*density, 160*density) + 24*density).toInt()
    }
    if (widths.isNotEmpty() && widths.sum() < viewport) {
        val extra = viewport-widths.sum()
        widths.indices.forEach { widths[it] += extra / widths.size + if (it < extra % widths.size) 1 else 0 }
    }
    return widths
}

@Composable
internal fun MarkdownTable(table: MarkdownTableData) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("markdown-table")) {
        val viewportPixels = with(density) { maxWidth.roundToPx() }
        val paint = remember(context, density) { TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ReadingTypography.typeface(context); textSize = ReadingTypography.bodySp * density.density * density.fontScale
        } }
        val widths = remember(table, viewportPixels, density) { tableColumnWidths(table, viewportPixels, paint, density.density) }
        val pixels = widths.sum()
        Column {
            Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
                Box(Modifier.horizontalScroll(scroll)) {
                    AndroidView(factory = { MarkdownTableView(it) }, update = { view ->
                        view.update(table, widths, paint, density.density, colors.onSurface.toArgb(),
                            colors.surfaceContainerLow.toArgb(), colors.outlineVariant.toArgb())
                    }, modifier = Modifier.width(with(density) { pixels.toDp() }).testTag("markdown-table-cells"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (pixels > viewportPixels) Text("左右滑动查看完整表格", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.weight(1f))
                PocketTextButton(onClick = { clipboard.setText(AnnotatedString(table.plainText)) }) { Text("复制表格", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** StaticLayout measures the real styled cells before rows are drawn, with no fixed row heights. */
internal class MarkdownTableView(context: Context) : View(context) {
    private var table: MarkdownTableData? = null
    private var widths = intArrayOf()
    private var cells = emptyList<List<StaticLayout>>()
    private var heights = intArrayOf()
    private var density = 1f
    private var header = 0
    private var border = 0
    private var ink = 0
    private var fontSize = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fun update(value: MarkdownTableData, columns: IntArray, textPaint: TextPaint, density: Float, ink: Int, header: Int, border: Int) {
        val changed = table !== value || !widths.contentEquals(columns) || fontSize != textPaint.textSize || this.ink != ink
        this.header=header;this.border=border;this.density=density;this.ink=ink
        if (changed) {
            table=value;widths=columns.copyOf();fontSize=textPaint.textSize
            cells=value.rows.mapIndexed { rowIndex,row -> row.mapIndexed { column, text ->
                val paint=TextPaint(textPaint).apply { color=ink; if(rowIndex in value.headers) typeface=ReadingTypography.typeface(context,true) }
                StaticLayout.Builder.obtain(text,0,text.length,paint,(widths[column]-24*density).toInt().coerceAtLeast(1))
                    .setIncludePad(false).setLineSpacing(8*density,1f).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
            } }
            heights=IntArray(cells.size) { index -> (cells[index].maxOfOrNull { it.height } ?: 0)+(20*density).toInt() }
            contentDescription=value.plainText
            requestLayout()
        }
        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(widths.sum(),widthMeasureSpec),resolveSize(heights.sum(),heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var y=0f
        cells.forEachIndexed { row,layouts ->
            if(row in table!!.headers) {fill.color=header;canvas.drawRect(0f,y,width.toFloat(),y+heights[row],fill)}
            var x=0f
            layouts.forEachIndexed { column,layout ->
                canvas.save();canvas.translate(x+12*density,y+10*density)
                drawRoundedCode(canvas,layout,table!!.rows[row][column],header,density)
                layout.draw(canvas);canvas.restore()
                x+=widths[column]
                if(column<widths.lastIndex) {fill.color=border;fill.strokeWidth=density;canvas.drawLine(x,y,x,y+heights[row],fill)}
            }
            y+=heights[row]
            if(row<cells.lastIndex) {fill.color=border;fill.strokeWidth=density;canvas.drawLine(0f,y,width.toFloat(),y,fill)}
        }
    }
}
