package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repositories over one shared Room database; no connection is opened because nothing is in the foreground. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExecutionLocationsTest {
    private class Blob(var fail: Boolean = false) : LocationBlob {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { check(!fail); text = value }
    }
    private object NoLegacy : PairingStorage {
        override fun load(): PairedComputer? = null
        override fun save(pair: PairedComputer) = Unit
        override fun forget() = Unit
    }
    private lateinit var app: Application
    private lateinit var db: PocketDatabase
    private val opened = mutableListOf<Any>()

    private fun pair(id: String) = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "secret-$id", id, id)
    private fun twoComputers(blob: Blob = Blob()) = ExecutionLocationStore(blob, NoLegacy).apply {
        register(ExecutionTarget("a", ExecutionTargetKind.Computer, "A"), pair("pc-a"))
        register(ExecutionTarget("b", ExecutionTargetKind.Computer, "B"), pair("pc-b"))
    }
    private fun locations(store: ExecutionLocationStore) = ExecutionLocations(app, store, db).also(opened::add)
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ExecutionLocations.awaitComputer(id: String) = withTimeout(5_000) {
        active.filterNotNull().flatMapLatest { repository -> repository.state.map { repository to it } }
            .first { (_, state) -> state.initialized && state.computerId == id }.first.also(opened::add)
    }

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
    }
    @After fun release() = runBlocking<Unit> {
        opened.distinct().forEach { owner ->
            val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(owner) as CoroutineScope
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        db.close()
    }

    @Test fun theSelectedLocationIsReadyAtOnceAndEachLocationKeepsItsOwnRepository() = runBlocking<Unit> {
        val store = twoComputers()
        val locations = locations(store)
        assertNotNull("The first frame must have a repository", locations.active.value)
        val a = locations.awaitComputer("pc-a")
        locations.select("b").join()
        val b = locations.awaitComputer("pc-b")
        assertNotSame(a, b)
        // The location left behind keeps its own state; nothing of the new one lands in it.
        assertEquals("pc-a", a.state.value.computerId)
        assertEquals("b", store.directory().selectedId)
        locations.select("a").join()
        assertSame("Returning must reuse the repository, with its drafts and pending replies", a, locations.awaitComputer("pc-a"))
        locations.select("b").join()
        assertSame(b, locations.awaitComputer("pc-b"))
        assertNull(locations.error.value)
    }

    @Test fun anUnpairedPhoneOpensItsPairingPageEvenWhenNothingCanBeWritten() = runBlocking<Unit> {
        val locations = locations(ExecutionLocationStore(Blob(fail = true), NoLegacy))
        val repository = checkNotNull(locations.active.value).also(opened::add)
        withTimeout(5_000) { repository.state.first { it.initialized } }
        assertFalse(repository.state.value.paired)
        assertNull(repository.state.value.error)
        assertNull(locations.error.value)
    }

    @Test fun forgettingALocationMovesToAnotherAndForgettingTheLastLeavesAPlaceToPair() = runBlocking<Unit> {
        val store = twoComputers()
        val locations = locations(store)
        val a = locations.awaitComputer("pc-a")
        a.forget().join()
        val b = locations.awaitComputer("pc-b")
        assertEquals(listOf("b"), store.directory().targets.map { it.id })
        assertEquals("secret-pc-b", store.pairing("b").load()!!.deviceSecret)
        b.forget().join()
        val fresh = withTimeout(5_000) { locations.active.first { it != null && it !== a && it !== b }!! }.also(opened::add)
        withTimeout(5_000) { fresh.state.first { it.initialized } }
        assertFalse(fresh.state.value.paired)
        assertNull(locations.error.value)
    }

    @Test fun aPlaceAddedForAnotherComputerIsDroppedWhenLeftUnpaired() = runBlocking<Unit> {
        val store = twoComputers()
        val locations = locations(store)
        val a = locations.awaitComputer("pc-a")
        locations.addComputer().join()
        val added = store.directory().selected!!
        assertNull(added.endpointId)
        val pairing = withTimeout(5_000) { locations.active.first { it != null && it !== a }!! }.also(opened::add)
        withTimeout(5_000) { pairing.state.first { it.initialized } }
        assertFalse(pairing.state.value.paired)
        locations.select("a").join()
        assertSame(a, locations.awaitComputer("pc-a"))
        assertEquals(listOf("a", "b"), store.directory().targets.map { it.id })
        assertEquals("secret-pc-a", store.pairing("a").load()!!.deviceSecret)
    }

    @Test fun appearanceFollowsThePhoneAcrossLocations() = runBlocking<Unit> {
        val locations = locations(twoComputers())
        val a = locations.awaitComputer("pc-a")
        locations.select("b").join()
        val b = locations.awaitComputer("pc-b")
        try {
            b.theme("dark"); b.glassEffects(false)
            locations.select("a").join()
            assertSame(a, locations.awaitComputer("pc-a"))
            assertEquals("dark", a.state.value.theme)
            assertFalse(a.state.value.glassEffects)
        } finally { b.theme("system"); b.glassEffects(true) }
    }
}
