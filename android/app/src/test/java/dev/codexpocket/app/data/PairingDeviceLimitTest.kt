package dev.codexpocket.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.R
import dev.codexpocket.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the pairing action and its public error, without a real connection or account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PairingDeviceLimitTest {
    private fun fixture() = """{"v":1,"relayUrl":"wss://fixture.invalid/pocket","room":"fixture","phoneToken":"fixture-phone","certificateSha256":"${"ab".repeat(32)}","pairingToken":"fixture-pair","expiresAt":5000000000,"computerName":"Fixture"}"""

    private fun failure(code: String, check: (Application, PocketState) -> Unit) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
        var saves = 0
        var pairRequests = 0
        val storage = object : PairingStorage {
            override fun load(): PairedComputer? = null
            override fun save(pair: PairedComputer) { saves++ }
            override fun forget() = Unit
        }
        val connection = object : PocketConnection {
            override val state = MutableStateFlow(LinkState.OFFLINE)
            override val notifications: Flow<JsonObject> = emptyFlow()
            override suspend fun connect(config: ConnectionConfig) { state.value = LinkState.ONLINE }
            override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
                assertEquals("pair", method)
                pairRequests++
                throw WireException(code, "private fixture diagnostic")
            }
            override fun close() { state.value = LinkState.OFFLINE }
        }
        val repo = PocketRepository(app, { connection }, storage, db, emptyFlow())
        try {
            withTimeout(5_000) { repo.state.first { it.initialized } }
            withTimeout(5_000) { repo.pair(fixture(), "QA phone").join() }
            assertEquals(1, pairRequests)
            assertEquals(0, saves)
            assertEquals(PairingPhase.Failed, repo.state.value.pairingPhase)
            assertFalse(repo.state.value.paired)
            assertFalse(repo.state.value.error.orEmpty().contains("private fixture diagnostic"))
            check(app, repo.state.value)
        } finally {
            val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            connection.close()
            db.close()
        }
    }

    @Test fun structuredDeviceLimitExplainsCapacityAndHowToRetry() = failure("device_limit_reached") { _, state ->
        val message = state.error.orEmpty()
        assertTrue("Capacity must identify the eight-device limit: $message", message.contains("8") && message.contains("设备"))
        assertTrue("Capacity must tell the user to remove an old device: $message",
            message.contains("移除") || message.contains("删除") || message.contains("取消配对"))
    }

    @Test fun legacyUnknownDoesNotInventADeviceLimitDiagnosis() = failure("unknown") { app, state ->
        assertEquals(app.getString(R.string.secure_request_error), state.error)
    }
}
