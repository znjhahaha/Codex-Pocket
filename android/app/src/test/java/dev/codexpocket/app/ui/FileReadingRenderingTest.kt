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
class FileReadingRenderingTest {
    private fun renderer() = MarkdownRenderCache(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, Color.GRAY)

    @Test fun prismHighlightsKnownGrammarAndLeavesUnknownPlain() = runBlocking {
        val renderer = renderer()
        val code = "val answer = 42 // example"
        val kotlin = renderer.highlight(code, "kotlin")
        assertEquals(code, kotlin.text)
        assertTrue(kotlin.spanStyles.any { it.start <= 0 && it.end >= 3 })
        val unknown = renderer.highlight(code, "not-a-language")
        assertEquals(code, unknown.text)
        assertTrue(unknown.spanStyles.isEmpty())
        assertTrue(renderer.highlight(code, "").spanStyles.isEmpty())
    }

    @Test fun tableQuoteFenceAndDuplicateHeadingAnchorsRemainInDocumentOrder() = runBlocking {
        val markdown = """# Start

> A **quote**

| Key | Value |
| --- | --- |
| a | b |

~~~kotlin
val x = 1
~~~

## Start

[Source](../src/App.kt)
"""
        val result = renderer().render("file:readme.md", markdown)
        assertEquals(listOf("start", "start-1"), result.headings.map { it.anchor })
        assertEquals("Start", result.headings.first().title)
        assertTrue("Quote missing: ${result.spans}", result.spans.toString().contains("A quote"))
        // Markwon tables are drawn by ReplacementSpan; their cell text is not
        // part of Spanned.toString(). Verify the rendered rows instead.
        assertEquals(2, result.spans.getSpans(0, result.spans.length, io.noties.markwon.ext.tables.TableRowSpan::class.java).size)
        assertEquals(listOf("val x = 1\n"), result.code)
        assertEquals("kotlin", result.parts.first { it.code != null }.language)
        result.headings.forEach { assertTrue("Heading ${it.title} missing from part ${it.partIndex}", result.parts[it.partIndex].spans.toString().contains(it.title)) }
        assertEquals(markdown, result.source)
    }
}
