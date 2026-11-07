package dev.codexpocket.app

import dev.codexpocket.app.data.PocketState
import dev.codexpocket.app.data.RuntimeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatusTest {
    @Test fun notificationTracksDisconnectedConnectingAndConnectedStates() {
        val paired = PocketState(paired = true)
        assertEquals(R.string.delivery_watch_unpaired, watchStatusResource(PocketState()))
        assertEquals(R.string.delivery_watch_offline, watchStatusResource(paired))
        assertEquals(R.string.delivery_watch_connecting, watchStatusResource(paired.copy(connection = "Connecting")))
        assertEquals(R.string.delivery_watch_reconnecting, watchStatusResource(paired.copy(connection = "Reconnecting")))
        assertEquals(R.string.delivery_watch_no_runtime, watchStatusResource(paired.copy(online = true)))
        val runtime = RuntimeInfo("r", "desktop", "v", "s", true, emptySet(), null)
        assertEquals(R.string.delivery_watch_connected, watchStatusResource(paired.copy(online = true, runtimes = listOf(runtime))))
    }
}
