package dev.codexpocket.app.ui

import androidx.compose.ui.geometry.Rect
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class V04InteractionPolicyTest {
    @Test fun menuStaysAboveCapsuleWithinResizedViewport() {
        val viewport = Rect(12f, 30f, 408f, 490f)
        val anchor = Rect(20f, 360f, 400f, 480f)
        val bounds = modelMenuBounds(viewport, anchor, 1f)
        assertEquals(300f, bounds.width, .01f)
        val start = modelMenuBounds(viewport, anchor, 1f, widthDp = 240f, alignStart = true)
        assertEquals(anchor.left, start.left, .01f)
        assertEquals(240f, start.width, .01f)
        assertTrue(bounds.height <= viewport.height * .55f)
        assertTrue(bounds.bottom <= anchor.top - 8f)
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
    }

    @Test fun toolbarMenuDropsBelowItsButtonAndAlignsTrailingEdge() {
        val viewport = Rect(0f, 24f, 412f, 900f)
        val button = Rect(350f, 40f, 398f, 88f)
        val bounds = modelMenuBounds(viewport, button, 1f, widthDp = 272f, below = true)
        assertEquals(94f, bounds.top, .01f)
        assertEquals(button.right, bounds.right, .01f)
        assertEquals(272f, bounds.width, .01f)
        assertEquals(viewport.height * .65f, bounds.height, .01f)
        assertTrue(bounds.bottom <= viewport.bottom)
    }

    @Test fun largeFontOrShortViewportNeverPlacesMenuUnderInput() {
        val bounds = modelMenuBounds(Rect(12f, 24f, 308f, 260f), Rect(16f, 110f, 304f, 250f), 1f)
        assertEquals(78f, bounds.height, .01f)
        assertEquals(102f, bounds.bottom, .01f)
        assertTrue(bounds.width <= 296f)
    }

    @Test fun searchMatchesNativeIdentifiersAndSelectionRetainsOnlySupportedEffort() {
        val model = NativeModel("native-42", "provider/codex", "Work model", "", listOf("low", "high"), "low", false, true)
        assertTrue(model.matchesModelQuery("  PROVIDER/  "))
        assertTrue(model.matchesModelQuery("native-42"))
        assertFalse(model.matchesModelQuery("missing"))
        assertEquals("high", model.selectionEffort("high"))
        assertNull(model.selectionEffort("max"))
    }

    @Test fun planAndFastRequirePairedNativeCapabilityAndIdleOwnedConversation() {
        val key = ThreadKey("pc", "store", "chat")
        val runtime = RuntimeInfo("r", "desktop", "v", "store", true, emptySet(), setOf("collaborationMode/list", "thread/settings/update"))
        val s = PocketState(paired = true, online = true, selected = ChatEntity(key, "r", "desktop", "Chat", "", "", 0, "", false),
            runtimes = listOf(runtime), modes = listOf(NativeMode("Plan", "plan", null, null)))
        assertTrue(nativeComposerControlsEnabled(s, "collaborationMode/list"))
        assertTrue(nativeComposerControlsEnabled(s, "thread/settings/update"))
        listOf(s.copy(paired = false), s.copy(online = false), s.copy(busy = true),
            s.copy(timeline = NativeTimeline(activeTurn = "turn")), s.copy(selected = s.selected!!.copy(conflict = true)),
            s.copy(runtimes = listOf(runtime.copy(online = false))),
            s.copy(runtimes = listOf(runtime.copy(capabilities = emptySet())))).forEach {
            assertFalse(nativeComposerControlsEnabled(it, "collaborationMode/list"))
            assertFalse(nativeComposerControlsEnabled(it, "thread/settings/update"))
        }
    }
}
