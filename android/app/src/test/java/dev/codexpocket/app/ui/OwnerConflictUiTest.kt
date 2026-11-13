package dev.codexpocket.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OwnerConflictUiTest {
    private val key = ThreadKey("pc", "store", "t")
    private val chat = ChatEntity(key, "r", "desktop", "", "", "", 0, "", true)
    private fun message(state: PocketState) = ownershipNoticeText(state,
        ApplicationProvider.getApplicationContext<Application>().getString(R.string.owner_conflict))!!

    @Test fun legacyConflictMessageDoesNotAssertMultipleOwners() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val message = app.getString(R.string.owner_conflict)
        assertFalse(message.contains("多个"))
        assertTrue(message.contains("归属"))
    }

    @Test fun onlyTypedMultipleEvidenceAssertsMultipleOwners() {
        val state = PocketState(selected = chat)
        assertFalse(message(state).contains("多个"))
        assertFalse(message(state.copy(threadOwnershipReasons = mapOf(key to OwnershipReason.Uncertain))).contains("多个"))
        assertTrue(message(state.copy(threadOwnershipReasons = mapOf(key to OwnershipReason.Multiple))).contains("多个"))
        val legacy = ThreadAccess(SessionMode.Desktop, null, "desktop", AccessPhase.Blocked, false, "thread_owned_elsewhere")
        assertFalse(message(state.copy(threadOwnershipReasons = mapOf(key to OwnershipReason.Multiple),
            threadAccess = mapOf(key to legacy), accessFresh = setOf(key))).contains("多个"))
    }

    @Test fun recheckShowsPendingBlockedFailureAndVerifiedRecovery() {
        val state = PocketState(selected = chat, online = true, capabilities = setOf(BrokerCapability.AccessRead.method))
        assertTrue(message(state.copy(ownershipChecks = mapOf(key to OwnershipCheck.Checking))).contains("正在"))
        assertTrue(message(state.copy(ownershipChecks = mapOf(key to OwnershipCheck.Blocked))).contains("发送仍暂停"))
        assertTrue(message(state.copy(ownershipChecks = mapOf(key to OwnershipCheck.Failed))).contains("核验失败"))
        val recovered = state.copy(selected = chat.copy(conflict = false), ownershipChecks = mapOf(key to OwnershipCheck.Recovered),
            threadAccess = mapOf(key to ThreadAccess(SessionMode.Desktop, "r", "desktop", AccessPhase.Ready, true)),
            runtimes = listOf(RuntimeInfo("r", "desktop", "", "store", true, emptySet(), emptySet())), accessFresh = setOf(key))
        assertTrue(message(recovered).contains("可以继续发送"))
        assertFalse(message(recovered.copy(accessFresh = emptySet())).contains("可以继续发送"))
        assertFalse(message(recovered.copy(selected = chat)).contains("可以继续发送"))
        assertFalse(message(recovered.copy(capabilities = emptySet())).contains("可以继续发送"))
        assertFalse(message(recovered.copy(runtimes = recovered.runtimes.map { it.copy(online = false) })).contains("可以继续发送"))
        assertFalse(message(recovered.copy(runtimes = recovered.runtimes.map { it.copy(configStoreId = "other") })).contains("可以继续发送"))
    }
}
