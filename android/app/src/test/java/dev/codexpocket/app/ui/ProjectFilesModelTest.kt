package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectFilesModelTest {
    @Test fun listingPreservesBoundedResultRootsAndServerPaths() {
        val listing = projectFileListing(Json.parseToJsonElement("""{
          "path":"D:\\repo\\src", "roots":["D:\\repo"], "truncated":true,
          "entries":[
            {"path":"D:\\repo\\src\\a.kt","name":"a.kt","kind":"file","size":123},
            {"path":"D:\\repo\\src\\z","name":"z","kind":"directory"},
            {"path":"D:\\repo\\src\\link","name":"link","kind":"symlink"}
          ]
        }""").jsonObject)
        assertEquals(listOf("D:\\repo"), listing.roots)
        assertTrue(listing.truncated)
        assertEquals(listOf("z", "a.kt"), listing.entries.map { it.name })
        assertEquals(123L, listing.entries.last().size)
        assertEquals("D:/repo", projectParentPath(listing))
        assertNull(projectParentPath(listing.copy(path = "D:/repo")))
        assertNull(projectParentPath(listing.copy(path = "D:/repo-other")))
    }

    @Test fun draftCallbackCarriesOldSideWithoutSendingAnything() {
        val draft = FileCommentDraft("src/App.kt", 42, ReviewSide.OLD, "Keep this check")
        var received: FileCommentDraft? = null
        deliverReadingDraft(draft, { received = it }) { _, _, _ -> fail("Typed handler must be used exclusively") }
        assertEquals(draft, received)
        var legacy = ""
        deliverReadingDraft(draft, null) { path, line, text ->
            assertEquals("src/App.kt", path)
            assertEquals(42, line)
            legacy = text
        }
        assertTrue(legacy.startsWith("[OLD src/App.kt:42]"))
        assertTrue(legacy.endsWith("Keep this check"))
    }
}
