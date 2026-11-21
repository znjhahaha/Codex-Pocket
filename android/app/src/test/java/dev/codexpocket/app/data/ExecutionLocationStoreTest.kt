package dev.codexpocket.app.data

import dev.codexpocket.wire.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionLocationStoreTest {
    private class Blob : LocationBlob {
        var text: String? = null
        var fail = false
        override fun read() = text
        override fun write(value: String) { check(!fail); text = value }
    }
    private fun pair(id: String) = PairedComputer(ConnectionConfig("wss://example.com", "room", "phone-secret", "ab".repeat(32)), "device", "secret-$id", id, id)
    private class Legacy(var pair: PairedComputer?) : PairingStorage {
        override fun load() = pair
        override fun save(pair: PairedComputer) { this.pair = pair }
        override fun forget() { pair = null }
    }

    @Test fun migrationRetainsOriginalPairAndDoesNotRepeatAfterRestart() {
        val blob = Blob(); val legacy = Legacy(pair("computer"))
        val store = ExecutionLocationStore(blob, legacy)
        assertEquals("computer", store.directory().selectedId)
        assertEquals("secret-computer", store.pairing("computer").load()!!.deviceSecret)
        val ssh = ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "开发服务器")
        store.register(ssh, pair("linux"), buildJsonObject { put("host", "server") })
        store.select("ssh")
        val restored = ExecutionLocationStore(blob, legacy)
        assertEquals(2, restored.directory().targets.size)
        assertEquals("ssh", restored.directory().selectedId)
        assertEquals("computer", legacy.load()!!.computerId)
    }

    @Test fun routeUpdatesStayWithTheirTargetAfterSelectionChanges() {
        val store = ExecutionLocationStore(Blob(), Legacy(pair("computer")))
        val computerStore = store.pairing("computer")
        store.register(ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "SSH"), pair("linux"))
        store.select("ssh")
        val old = computerStore.load()!!
        computerStore.save(PairedComputer(old.config.copy(phoneToken = "new-token"), old.deviceId, old.deviceSecret, old.computerId, old.computerName))
        assertEquals("ssh", store.directory().selectedId)
        assertEquals("new-token", store.pairing("computer").load()!!.config.phoneToken)
        assertEquals("phone-secret", store.pairing("ssh").load()!!.config.phoneToken)
        assertEquals(ExecutionTargetKind.LinuxSsh, store.directory().selected!!.kind)
    }

    @Test fun failedDurableWriteDoesNotChangeSelectedTargetOrCredentials() {
        val blob = Blob(); val legacy = Legacy(pair("computer")); val store = ExecutionLocationStore(blob, legacy)
        val before = store.directory(); blob.fail = true
        assertThrows(IllegalStateException::class.java) {
            store.register(ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "SSH"), pair("linux"))
        }
        assertEquals(before, store.directory())
        assertEquals("secret-computer", legacy.load()!!.deviceSecret)
    }

    @Test fun unpairRemovesOnlyThatCredentialAndCannotReimportItFromLegacy() {
        val blob = Blob(); val legacy = Legacy(pair("computer")); val store = ExecutionLocationStore(blob, legacy)
        store.register(ExecutionTarget("ssh", ExecutionTargetKind.LinuxSsh, "SSH"), pair("linux"))
        store.pairing("computer").forget()
        val restored = ExecutionLocationStore(blob, legacy)
        assertEquals(listOf("ssh"), restored.directory().targets.map { it.id })
        assertEquals("secret-linux", restored.pairing("ssh").load()!!.deviceSecret)
    }

    @Test fun mismatchedHostIdentityCannotReplaceAnExistingTarget() {
        val store = ExecutionLocationStore(Blob(), Legacy(pair("computer")))
        assertThrows(IllegalArgumentException::class.java) { store.pairing("computer").save(pair("different")) }
        assertEquals("computer", store.pairing("computer").load()!!.computerId)
    }
}
