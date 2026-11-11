package dev.codexpocket.app.ui

import dev.codexpocket.app.data.PocketState
import org.junit.Assert.*
import org.junit.Test

class ConnectionPresentationTest {
    @Test fun recoveryIsVisibleAndStopsWhenConnectedOrTerminal() {
        val state = PocketState(paired = true, online = false)
        for (phase in listOf("connecting", "authenticating", "syncing", "recovering"))
            assertTrue(state.copy(connectionPhase = phase).restoringConnection)
        assertFalse(state.copy(online = true, connectionPhase = "ready", directoryLoading = true).restoringConnection)
        assertFalse(state.copy(connectionPhase = "authorization_required").restoringConnection)
        assertFalse(state.copy(paired = false, connectionPhase = "recovering").restoringConnection)
    }
}
