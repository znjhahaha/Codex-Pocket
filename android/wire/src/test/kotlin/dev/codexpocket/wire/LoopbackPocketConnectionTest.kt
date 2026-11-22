package dev.codexpocket.wire

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class LoopbackPocketConnectionTest {
    @Test fun backendThatIsNotListeningFailsThisConnectionOnly() = runBlocking {
        val escaped = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> escaped += failure }
        try {
            val free = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
            val connection = LoopbackPocketConnection { free }
            val received = async { runCatching { connection.notifications.toList() } }
            val failure = runCatching { withTimeout(30_000) { connection.connect(ConnectionConfig("wss://relay.example", "room", "secret", "0".repeat(64))) } }.exceptionOrNull()
            assertTrue(failure is WireException)
            withTimeout(10_000) { received.await() }
            // The collector that forwards notifications ends on a dispatcher thread; give it its turn.
            delay(500)
            assertEquals(LinkState.OFFLINE, connection.state.value)
            assertNotNull(connection.failureCode)
            assertEquals(emptyList<Throwable>(), escaped.toList())
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }
}
