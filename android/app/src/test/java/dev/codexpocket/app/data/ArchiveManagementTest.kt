package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class ArchiveManagementTest {
    private val key = ThreadKey("pc", "store", "t")
    private val history = ChatEntity(key, "history:store", "cli", "Saved", "", "", 0, "notLoaded", false, archived = true)
    private val runtime = RuntimeInfo("r", "cli", "fixture", "store", true, emptySet(), setOf("thread/unarchive"))
    private val state = PocketState(online = true, computerId = "pc", selected = history, runtimes = listOf(runtime))

    @Test fun historyMenuUsesManagementCapabilityWithoutGrantingSendPermission() {
        assertNull(state.archiveUnavailableReason())
        assertEquals("r", state.archiveManagementRuntime(history)?.id)
        assertFalse(state.ownershipVerifiedFor())
    }
    @Test fun wrongComputerStoreOfflineAndAuxiliaryChannelsStayUnavailable() {
        assertNotNull(state.copy(computerId = "other").archiveUnavailableReason())
        for (candidate in listOf(runtime.copy(configStoreId = "other"), runtime.copy(online = false),
            runtime.copy(role = "managedHost"), runtime.copy(capabilities = emptySet()))) {
            assertNotNull(state.copy(runtimes = listOf(candidate)).archiveUnavailableReason())
        }
    }
    @Test fun conflictingOwnersAndUnresolvedArchiveRemainBlocked() {
        assertNotNull(state.copy(selected = history.copy(conflict = true)).archiveUnavailableReason())
        val owners = listOf(runtime.copy(loadedThreadIds = setOf("t")), runtime.copy(id = "second", loadedThreadIds = setOf("t")))
        assertNull(state.copy(runtimes = owners).archiveManagementRuntime(history))
        val command = CommandEntity("old", "pc", key, "thread/unarchive", "unknown", 0)
        assertNotNull(state.copy(commands = listOf(command)).archiveUnavailableReason())
    }
}
