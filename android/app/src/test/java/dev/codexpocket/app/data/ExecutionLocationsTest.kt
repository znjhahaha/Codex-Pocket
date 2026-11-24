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

    private fun pair(id: String, name: String = id) = PairedComputer(ConnectionConfig("wss://fixture.invalid", "room", "fixture-token", "00".repeat(32)), "phone", "secret-$id", id, name)
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

    @Test fun activeLocationBindsTheTargetToTheSameRepositoryUsedByExistingCallers() = runBlocking<Unit> {
        val store = twoComputers().apply {
            register(ExecutionTarget("phone", ExecutionTargetKind.ThisPhone, "此手机"), pair("local"))
            register(ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "开发服务器"), pair("linux"))
        }
        val locations = locations(store)
        assertNotNull("Identity and repository must be ready together on the first frame", locations.activeLocation.value)
        val original = locations.awaitComputer("pc-a")
        for (id in listOf("phone", "ssh", "b", "a")) {
            locations.select(id).join()
            val target = checkNotNull(store.directory().selected)
            val binding = checkNotNull(locations.activeLocation.value)
            assertEquals(target, binding.target)
            assertSame(locations.active.value, binding.repository)
            assertSame(binding.repository, locations.awaitComputer(checkNotNull(target.endpointId)))
        }
        assertSame("Returning keeps the original repository and its pending work", original, locations.activeLocation.value!!.repository)
    }

    @Test fun updatingTheActivePairingLabelKeepsItsRepositoryAndRefreshesThePublishedIdentity() = runBlocking<Unit> {
        val store = twoComputers()
        val locations = locations(store)
        val original = locations.awaitComputer("pc-a")
        store.pairing("a").save(pair("pc-a", "工作电脑新名称"))
        val renamed = withTimeout(5_000) { locations.activeLocation.filterNotNull().first { it.target.label == "工作电脑新名称" } }
        assertEquals("a", renamed.target.id)
        assertEquals("pc-a", renamed.target.endpointId)
        assertSame(original, renamed.repository)
        assertSame(original, locations.active.value)
    }

    @Test fun aBackgroundDirectoryRefreshDoesNotDismissAnActionFailure() = runBlocking<Unit> {
        val store = twoComputers()
        val locations = locations(store)
        locations.awaitComputer("pc-a")
        locations.select("missing").join()
        val message = "暂时无法切换执行位置，请重试。"
        assertEquals(message, locations.error.value)

        store.pairing("a").save(pair("pc-a", "Updated computer"))
        withTimeout(5_000) { locations.activeLocation.filterNotNull().first { it.target.label == "Updated computer" } }
        // Publication happens inside activate's monitor; wait for that refresh to finish.
        synchronized(locations) { assertEquals(message, locations.error.value) }

        locations.select("b").join()
        assertNull("A successful explicit retry dismisses the previous error", locations.error.value)
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
