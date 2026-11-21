package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimelinePersistenceTest {
    private lateinit var db: PocketDatabase
    private val queries = CopyOnWriteArrayList<String>()
    private val key = ThreadKey("pc", "store", "thread")
    private val other = key.copy(nativeId = "other")
    private val a = TimelineItem("turn", "a", "agentMessage", "retained", raw = buildJsonObject { put("text", "retained") })
    private val b = a.copy(itemId = "b", text = "second")
    private fun timeline(vararg items: TimelineItem, status: String = "inProgress") = NativeTimeline(
        items = items.associateByTo(linkedMapOf()) { it.key }, turns = mapOf("turn" to NativeTurnInfo("turn", status = status)))

    @Before fun prepare() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PocketDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
    }
    @After fun close() = db.close()
    private suspend fun save(next: NativeTimeline, old: NativeTimeline? = null, replace: Boolean = true) =
        db.withTransaction { db.dao().persistTimelineItems(key, next, old, replace) }

    @Test fun restoredCacheIsANoopAndPrependingOnlyMovesRetainedPayloads() = runBlocking {
        val first = timeline(a, b)
        save(first)
        val restored = NativeTimeline(items = db.dao().cachedItems(key).associate { it.itemKey to it.item() }).withCachedTurns()
        queries.clear()
        save(first, restored)
        assertFalse(queries.any { it.startsWith("INSERT", true) || it.startsWith("UPDATE", true) })
        assertFalse(queries.any { it.startsWith("SELECT * FROM items", true) })
        queries.clear()
        val added = a.copy(itemId = "older")
        save(timeline(added, a, b), first)
        assertEquals(1, queries.count { it.startsWith("INSERT", true) })
        assertEquals(2, queries.count { it.startsWith("UPDATE items SET ordinal", true) })
        assertEquals(listOf("older", "a", "b"), db.dao().items(key).map { it.itemId })
    }

    @Test fun turnOnlyChangesAndReplacementKeepTheirOriginalMeaning() = runBlocking {
        val first = timeline(a, b)
        save(first)
        db.dao().putItems(listOf(ItemEntity.from(other, a, 0)))
        val completed = timeline(a, b, status = "completed")
        save(completed, first)
        assertTrue(db.dao().items(key).all { it.raw.asObject().obj("_pocketTurn").string("status") == "completed" })
        save(timeline(a), completed, replace = false)
        assertEquals(2, db.dao().items(key).size)
        save(timeline(a), timeline(a), replace = true)
        assertEquals(listOf("a"), db.dao().items(key).map { it.itemId })
        assertEquals(listOf("a"), db.dao().items(other).map { it.itemId })
    }

    @Test fun cacheMissAndMissingRowsNeverMistakeMemoryForDurableHistory() = runBlocking {
        val first = timeline(a, b)
        save(first)
        db.dao().removeItems(key, listOf(b.key))
        save(first, first)
        assertEquals(2, db.dao().items(key).size)
        queries.clear()
        save(first.copy(items = first.items + (b.key to b.copy(text = "updated"))), old = null)
        assertFalse(queries.any { it.startsWith("SELECT * FROM items", true) })
        assertEquals("updated", db.dao().items(key).last().text)
    }
}
