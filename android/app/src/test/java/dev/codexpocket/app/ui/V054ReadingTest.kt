package dev.codexpocket.app.ui

import android.app.Application
import android.graphics.Color
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class V054ReadingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Test fun threeColumnsPreserveEveryCellAndWrapWithoutClipping()= runBlocking {
        val cache=MarkdownRenderCache(context,Color.BLACK,Color.BLUE,Color.GREEN)
        val source="""Before

| 实测项目 | AWS | 华为 |
| --- | --- | --- |
| 很长的中文测试说明必须换行，不应截断 | **0.40秒** | 2.71秒 |
| D:/work/example/long-file.md | 第二行 | 终点 |

After"""
        val parsed=cache.render("table",source)
        assertEquals(3,parsed.parts.size)
        val table=parsed.parts[1].table!!
        assertEquals(3,table.columns);assertEquals(3,table.rows.size)
        assertEquals("终点",table.rows.last().last().toString())
        assertEquals(setOf(0),table.headers)
        val paint=TextPaint().apply { textSize=16f;typeface=ReadingTypography.typeface(context) }
        val widths=tableColumnWidths(table,240,paint,1f)
        assertTrue(widths.sum()>240)
        val view=MarkdownTableView(context)
        view.update(table,widths,paint,1f,Color.BLACK,Color.LTGRAY,Color.GRAY)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(widths.sum(),android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
        assertTrue(view.measuredHeight>3*36)
        assertEquals(source,parsed.source)
        assertSame(parsed,cache.render("table",source))
    }
    @Test fun roundedInlineCodeRetainsExactTextAndNativeOffsets()= runBlocking {
        val cache=MarkdownRenderCache(context,Color.BLACK,Color.BLUE,Color.GREEN)
        val parsed=cache.render("inline","中文 `aggit` 与 `long_code_identifier` 后文")
        val spans=parsed.parts.single().spans!!
        val code=spans.getSpans(0,spans.length,io.noties.markwon.core.spans.CodeSpan::class.java).sortedBy { spans.getSpanStart(it) }
        assertEquals(2,code.size)
        assertEquals("aggit",spans.subSequence(spans.getSpanStart(code[0]),spans.getSpanEnd(code[0])).toString())
        assertEquals("中文 aggit 与 long_code_identifier 后文",spans.toString())
        code.forEach { span ->
            val paint = TextPaint().apply { color = Color.BLACK; bgColor = Color.RED }
            span.updateDrawState(paint)
            assertEquals("Rounded code must not retain Markwon's rectangular background", Color.TRANSPARENT, paint.bgColor)
        }
        assertNotNull(ReadingTypography.typeface(context))
    }

    @Test fun codeSpacingReservesWidthWithoutSwallowingPunctuationOrSplittingGraphemes()= runBlocking {
        val cache=MarkdownRenderCache(context,Color.BLACK,Color.BLUE,Color.GREEN)
        for (code in listOf("aggit", "a", "中文", "👩‍💻")) {
            val parsed=cache.render("punctuation-$code", "前 `$code`，后；结束。")
            val text=parsed.parts.single().spans!!
            assertEquals("前 $code，后；结束。",text.toString())
            val codeSpan=text.getSpans(0,text.length,io.noties.markwon.core.spans.CodeSpan::class.java).single()
            val start=text.getSpanStart(codeSpan);val end=text.getSpanEnd(codeSpan)
            val edges=text.getSpans(start,end,InlineCodeEdgeSpan::class.java)
            assertTrue(edges.isNotEmpty())
            assertEquals('，',text[end])
            edges.forEach { edge ->
                assertTrue(text.getSpanStart(edge)>=start);assertTrue(text.getSpanEnd(edge)<=end)
                val paint=TextPaint().apply { textSize=16f }
                codeSpan.updateMeasureState(paint)
                val measured=edge.getSize(paint,text,text.getSpanStart(edge),text.getSpanEnd(edge),null)
                val glyph=paint.measureText(text,text.getSpanStart(edge),text.getSpanEnd(edge))
                assertTrue(measured>=glyph+edge.leading+edge.trailing)
            }
            if(code == "👩‍💻") { assertEquals(1,edges.size); assertEquals(code,text.subSequence(text.getSpanStart(edges[0]),text.getSpanEnd(edges[0])).toString()) }
        }
    }
}
