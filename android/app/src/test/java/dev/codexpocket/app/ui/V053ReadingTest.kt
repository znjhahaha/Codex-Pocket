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
class V053ReadingTest {
    @Test fun wideTableIsItsOwnReadingBlockAndSurroundingProseKeepsOrder() = runBlocking {
        val renderer = MarkdownRenderCache(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, Color.GRAY)
        val text = "Before\n\n| A | B | C | D |\n|---|---|---|---|\n| 中文 | 长路径 | 3 | 4 |\n\nAfter"
        val rendered = renderer.render("wide", text)
        assertEquals(3, rendered.parts.size)
        assertEquals("Before", rendered.parts.first().spans.toString())
        assertEquals(4, rendered.parts[1].tableColumns)
        assertEquals("After", rendered.parts.last().spans.toString())
        assertSame(rendered, renderer.render("wide", text))
    }

    @Test fun wrappedDiffKeepsTrueLineNumbersAndExactSource() {
        assertTrue(DiffReadingState().wrap)
        val diff = "@@ -2,1 +2,2 @@\n-删除\n+" + "很长中文".repeat(150) + "\n+second"
        val lines = parseReviewLines(diff, true)
        assertEquals(listOf(2, 3), lines.filter { it.newLine != null }.map { it.newLine })
        assertTrue(lines.any { it.text.contains("很长中文".repeat(150)) })
    }
}
