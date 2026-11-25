package dev.codexpocket.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRuntimeCatalogTest {
    private class Server(body: String, code: Int = 200) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/v1"
        init { thread(isDaemon = true) { runCatching {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val bytes = body.toByteArray()
                client.getOutputStream().write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            }
        } } }
        override fun close() = socket.close()
    }
    private class Memory(var record: String) : LocationBlob {
        override fun read() = record
        override fun write(value: String) { record = value }
    }

    @Test fun discoveryNeverEnablesModelsThatWereNotConfigured() = runBlocking {
        Server("""{"data":[{"id":"chosen"},{"id":"not-added"}]}""").use { server ->
            val store = LocalProviderStore(Memory("""{"version":1,"active":"p","providers":[{"id":"p","name":"Test","baseUrl":"${server.url}","apiKey":"fixture-not-secret","model":"chosen"}]}"""))
            val runtime = LocalRuntime(ApplicationProvider.getApplicationContext<Context>(), providers = store)
            assertEquals(listOf("chosen"), runtime.modelCatalog()!!.load().map { it.model })
        }
    }

    @Test fun configuredAliasEffortsSurviveOfflineCatalogAndStoreReopen() = runBlocking {
        Server("{}", 503).use { server ->
            val blob = Memory("""{"version":2,"active":"p","providers":[{"id":"p","name":"Test","baseUrl":"${server.url}","apiKey":"fixture-not-secret","model":"my-alias","enabledModels":[{"id":"my-alias","efforts":["low","high"],"defaultEffort":"high"}]}]}""")
            val runtime = LocalRuntime(ApplicationProvider.getApplicationContext<Context>(), providers = LocalProviderStore(blob))
            val selected = runtime.modelCatalog()!!.load().single()
            assertEquals("my-alias", selected.model)
            assertEquals(listOf("low", "high"), selected.efforts)
            assertEquals("high", selected.defaultEffort)
        }
    }
}
