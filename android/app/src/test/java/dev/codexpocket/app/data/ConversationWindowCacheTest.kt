package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonObject

class ConversationWindowCacheTest {
    private val key = ThreadKey("pc", "store", "thread")
    private val chat = ChatEntity(key, "runtime", "desktop", "Chat", "", "", 0, "idle", false)

    @Test fun memoryEvictsLeastRecentlyUsedAndOversizedValues() {
        val cache = BoundedCache<String, String>(2, 8) { it.length.toLong() }
        cache["a"] = "aa"; cache["b"] = "bb"
        assertEquals("aa", cache["a"])
        cache["c"] = "cc"
        assertNull(cache["b"])
        cache["large"] = "123456789"
        assertNull(cache["large"])
        assertNull(cache["a"])
    }

    @Test fun onlyCurrentVerifiedContinuousOwnerCanReuseReadyWindow() {
        val window = ConversationWindow("runtime", false, null, true, ConversationLoadState.Ready)
        assertTrue(window.reusable(chat, true, 4, 4, true))
        assertFalse(window.reusable(chat, false, 4, 4, true))
        assertFalse(window.reusable(chat, true, null, 4, true))
        assertFalse(window.reusable(chat, true, 3, 4, true))
        assertFalse(window.reusable(chat.copy(runtimeId = "other"), true, 4, 4, true))
        assertFalse(window.reusable(chat.copy(conflict = true), true, 4, 4, true))
        assertFalse(window.reusable(chat, true, 4, 4, false))
        assertFalse(window.copy(loadState = ConversationLoadState.Failed).reusable(chat, true, 4, 4, true))
    }

    @Test fun persistenceOnlyUpdatesChangesAndDeletesAbsentItemsOnReplacement() {
        val a = ItemEntity(key, "a", "turn", "a", "agentMessage", "same", "completed", JsonObject(emptyMap()), true, 0)
        val b = a.copy(itemKey = "b", itemId = "b", ordinal = 1)
        val c = b.copy(itemKey = "c", itemId = "c", text = "new")
        val diff = itemPersistenceDiff(listOf(a, b), listOf(a, c), true)
        assertEquals(listOf(c), diff.upserts)
        assertEquals(listOf("b"), diff.removed)
        assertTrue(itemPersistenceDiff(listOf(a, b), listOf(a), false).removed.isEmpty())
        assertTrue(itemPersistenceDiff(listOf(a), listOf(a), true).upserts.isEmpty())
        assertEquals(listOf(a.copy(ordinal = 1)), itemPersistenceDiff(listOf(a), listOf(a.copy(ordinal = 1)), true).upserts)
    }

    @Test fun runtimeCacheExpiresAtSixtySecondsAndNeverVerifiesAnotherLink() {
        val value = CachedExtensions(ExtensionManagementState("pc", "r", ExtensionPhase.Ready), 5, 100)
        assertTrue(value.fresh(5, 60_099))
        assertFalse(value.fresh(5, 60_100))
        assertFalse(value.fresh(6, 101))
        assertFalse(value.fresh(5, 99))
        assertFalse(value.copy(state = value.state.copy(uncertainIds = setOf("row"))).fresh(5, 101))
        assertTrue(extensionUpdateUncertain("disconnected"))
        assertTrue(extensionUpdateUncertain("unknown"))
        assertFalse(extensionUpdateUncertain("conflict"))
    }

    @Test fun liveTextDeltaReusesRetainedRawWeights() {
        var scans = 0
        val cache = TimelineCache(1_000_000) { scans++; 100L }
        val items = (0 until 100).map { TimelineItem("turn", "$it", "agentMessage", "recorded") }
        val before = NativeTimeline(items.associateBy { it.key })
        cache.select(key)
        cache[key] = before
        assertEquals(100, scans)
        val changed = items.last().copy(text = "recorded delta")
        cache.update(key, before.copy(items = before.items + (changed.key to changed)), listOf(changed))
        assertEquals("Unchanged raw trees must not be scanned for text deltas", 100, scans)
        assertEquals("recorded delta", cache[key]!!.items.getValue(changed.key).text)
    }
}
