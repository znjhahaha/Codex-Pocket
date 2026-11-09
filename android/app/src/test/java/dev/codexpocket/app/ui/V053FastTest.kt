package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class V053FastTest {
    private val key = ThreadKey("pc", "store", "thread")
    private val ready = PocketState(paired = true, online = true,
        selected = ChatEntity(key, "runtime", "desktop", "Chat", "", "", 0, "", false),
        model = "m", models = listOf(NativeModel("m", "m", "Model", "", emptyList(), "", false, true, serviceTiers = setOf("fast"))),
        runtimes = listOf(RuntimeInfo("runtime", "desktop", "v", "store", true, emptySet(), setOf("thread/settings/update"),
            capabilityFields = mapOf("thread/settings/update" to setOf("threadId", "serviceTier")))))

    @Test fun pendingRunningAndUnknownDisableFast() {
        assertNull(fastUnavailableReason(ready))
        assertNotNull(fastUnavailableReason(ready.copy(fastPending = setOf(key))))
        assertNull(fastUnavailableReason(ready.copy(fastPending = setOf(key.copy(nativeId = "other")))))
        assertNotNull(fastUnavailableReason(ready.copy(timeline = NativeTimeline(activeTurn = "running"))))
        assertNotNull(fastUnavailableReason(ready.copy(sendBusy = true)))
    }

    @Test fun capabilitiesOfflineAndChildRemainGuarded() {
        assertEquals(PocketFailure.ModelSpeed.label, fastUnavailableReason(ready.copy(models = ready.models.map { it.copy(serviceTiers = emptySet()) })))
        assertEquals(PocketFailure.RuntimeSpeed.label, fastUnavailableReason(ready.copy(runtimes = ready.runtimes.map { it.copy(capabilityFields = emptyMap()) })))
        assertNotNull(fastUnavailableReason(ready.copy(online = false)))
        assertNotNull(fastUnavailableReason(ready.copy(disabledMethods = setOf("runtime:thread/settings/update"))))
        assertNotNull(fastUnavailableReason(ready.copy(selected = ready.selected!!.copy(isSubagent = true))))
        assertNotNull(fastUnavailableReason(ready.copy(selected = ready.selected!!.copy(archived = true))))
        assertNotNull(fastUnavailableReason(ready.copy(selected = ready.selected!!.copy(conflict = true))))
    }
}
