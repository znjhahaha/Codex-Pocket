package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class FileReadingTest {
    @Test fun removedLinesKeepOldSideAndHunkCoordinates() {
        val rows = parseReviewLines("--- a/x\n+++ b/x\n@@ -12,2 +20,2 @@\n-old\n+new\n context\n\\ No newline at end of file", true)
        assertEquals(12, rows[3].oldLine)
        assertNull(rows[3].newLine)
        assertEquals(20, rows[4].newLine)
        assertEquals(13, rows[5].oldLine)
        assertEquals(21, rows[5].newLine)
        assertNull(rows.last().newLine)
        assertEquals(listOf(null, null, null, null, 20, 21, null), diffNewLineNumbers(rows.joinToString("\n") { it.text }))
    }
    @Test fun hunkCountsStopMetadataFromBecomingCommentTargets() {
        val rows = parseReviewLines("@@ -0,0 +1 @@\n+++literal\n+++ b/next\n@@ -999999999999 +1 @@\n+bad", true)
        assertEquals(1, rows[1].newLine)
        assertNull(rows[2].newLine)
        assertNull(rows.last().newLine)
    }
    @Test fun contextFoldPreservesEdgesAndExpandsWithoutLosingRows() {
        val rows = parseReviewLines("@@ -1,12 +1,12 @@\n" + (1..12).joinToString("\n") { " line $it" }, true)
        val folded = foldReviewLines(rows, emptySet())
        val gap = folded.single { it.hiddenCount > 0 }
        assertEquals(6, gap.hiddenCount)
        assertEquals(rows.map { it.index }, foldReviewLines(rows, setOf(gap.row.index)).map { it.row.index })
    }
    @Test fun relativeLinksNormalizeInsideKnownRootOnly() {
        assertEquals("src/App.kt", resolveWorkspaceLink("docs/readme.md", "../src/App.kt"))
        assertEquals("D:/repo/src/A B.kt", resolveWorkspaceLink("D:\\repo\\docs\\readme.md", "../src/A%20B.kt", "D:\\repo"))
        assertEquals("d:/REPO/src/App.kt", resolveWorkspaceLink("D:/repo/docs/readme.md", "../src/App.kt", "d:/REPO"))
        assertNull(resolveWorkspaceLink("D:/repo/readme.md", "src/a.kt"))
        listOf("../../secret", "%2e%2e/%2e%2e/secret", "https://example.com", "file:///etc/passwd", "//host/share", "C:/secret", "a%252fb", "a%00b", "a?x=1", "a%3fb").forEach {
            assertNull(it, resolveWorkspaceLink("docs/readme.md", it))
        }
    }
    @Test fun searchIsLiteralAndLineJumpIsBounded() {
        val index = SourceLineIndex("one\r\n[.*]\r\nONE\n")
        assertEquals("[.*]", index.line(1))
        assertEquals(listOf(1), index.matchingLines("[.*]"))
        assertEquals(listOf(0, 2), index.matchingLines("one"))
        assertEquals(3, index.jumpIndex("4"))
        assertNull(index.jumpIndex("5"))
        assertNull(index.jumpIndex("0"))
    }
    @Test fun unknownLanguageIsNotGuessedAsAnotherGrammar() {
        assertNull(sourceLanguage("archive.unknown"))
        assertNull(normalizeCodeLanguage("typescript"))
        assertEquals("javascript", normalizeCodeLanguage("js"))
        assertEquals("kotlin", sourceLanguage("src/Screen.kt"))
    }
}
