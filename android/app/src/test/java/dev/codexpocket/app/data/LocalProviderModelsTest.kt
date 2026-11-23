package dev.codexpocket.app.data

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalProviderModelsTest {
    private class Memory : LocationBlob {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private class Server(private val body: String, private val code: Int = 200, private val location: String? = null) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "model-list-test").apply { isDaemon = true } }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        init {
            executor.submit {
                while (!server.isClosed) try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        var authorization: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
                        }
                        requests += path to authorization
                        val bytes = body.toByteArray()
                        val headers = "HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                            (location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().write(bytes)
                    }
                } catch (_: IOException) { /* A bounded reader or cancellation may close the client early. */ }
            }
        }
        val url get() = "http://127.0.0.1:${server.localPort}/v1"
        override fun close() { server.close(); executor.shutdownNow() }
    }
    private fun request(url: String = "https://example.invalid/v1", key: String = "sk-test-only-12345678", id: String? = null) = LocalProviderModelRequest(id, url, key)
    private fun failure(problem: LocalProviderModelProblem) = LocalProviderModelResult.Failed(problem)
    private fun models(vararg ids: String) = LocalProviderModelResult.Models(ids.toList())

    @Test fun standardAndCcsCatalogsRetainExactIdsAndDeduplicate() {
        assertEquals(models("gpt-6.1-sol", "vendor/model:latest"), LocalProviderModelDiscovery.parse("""{"data":[{"id":"gpt-6.1-sol"},{"id":"vendor/model:latest"},{"id":"gpt-6.1-sol"},{"id":7},{"id":" invalid "},{"id":""}]}"""))
        assertEquals(models("ccs-aggit/gpt-6.1-sol", "ccs-other/model"), LocalProviderModelDiscovery.parse("""{"models":[{"slug":"ccs-aggit/gpt-6.1-sol","display_name":"Aggit"},{"slug":"ccs-other/model"}]}"""))
    }

    @Test fun emptyCatalogsAreDifferentFromInvalidResponses() {
        assertEquals(models(), LocalProviderModelDiscovery.parse("""{"data":[]}"""))
        assertEquals(models(), LocalProviderModelDiscovery.parse("""{"models":[]}"""))
        for (body in listOf("not json", "[]", "{}", "{\"data\":{}}", "{\"data\":[{\"id\":false}]}")) {
            assertEquals(failure(LocalProviderModelProblem.Response), LocalProviderModelDiscovery.parse(body))
        }
    }

    @Test fun requestsNormalizeBaseAndSendOneBearerHeaderToTheEnteredEndpoint() = runBlocking {
        Server("""{"data":[{"id":"test-model"}]}""").use { server ->
            val result = LocalProviderModelDiscovery(LocalProviderStore(Memory())).fetch(request(" ${server.url}/// ", " sk-test-only-12345678 "))
            assertEquals(models("test-model"), result)
            assertEquals(listOf("/v1/models" to "Bearer sk-test-only-12345678"), server.requests)
        }
    }

    @Test fun editingAnInactiveProviderUsesItsStoredKeyWithoutSavingOrReturningIt() = runBlocking {
        Server("""{"models":[{"slug":"ccs-aggit/gpt-6.1-sol"}]}""").use { server ->
            val blob = Memory()
            val store = LocalProviderStore(blob)
            val id = store.save(LocalProviderDraft(null, "编辑的服务", server.url, "sk-saved-first-12345678", "old"))!!
            val active = store.save(LocalProviderDraft(null, "使用中的服务", server.url, "sk-other-active-12345678", "other"))!!
            val before = blob.value
            val discovery = LocalProviderModelDiscovery(store)
            val result = discovery.fetch(request(server.url, "", id))
            assertEquals(models("ccs-aggit/gpt-6.1-sol"), result)
            assertEquals("Bearer sk-saved-first-12345678", server.requests.single().second)
            assertFalse(result.toString().contains("sk-saved"))
            assertEquals(active, store.access.value.activeId)
            assertEquals(before, blob.value)
            discovery.fetch(request(server.url, "sk-replacement-12345678", id))
            assertEquals("Bearer sk-replacement-12345678", server.requests.last().second)
            assertEquals(before, blob.value)
        }
    }

    @Test fun invalidInputAndMissingSavedKeyNeverMakeARequest() = runBlocking {
        Server("""{"data":[]}""").use { server ->
            val discovery = LocalProviderModelDiscovery(LocalProviderStore(Memory()))
            assertEquals(failure(LocalProviderModelProblem.Key), discovery.fetch(request(server.url, "", "missing")))
            assertEquals(failure(LocalProviderModelProblem.Key), discovery.fetch(request(server.url, "key\r\nInjected: header")))
            assertEquals(failure(LocalProviderModelProblem.Address), discovery.fetch(request(server.url + "?key=leak")))
            assertEquals(failure(LocalProviderModelProblem.Address), discovery.fetch(request("https://user:secret@example.invalid/v1")))
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun crossOriginRedirectNeverReachesTheOtherServer() = runBlocking {
        Server("""{"data":[]}""").use { target ->
            Server("redirect", 307, target.url + "/models").use { source ->
                assertEquals(failure(LocalProviderModelProblem.Redirect), LocalProviderModelDiscovery(LocalProviderStore(Memory())).fetch(request(source.url)))
                assertEquals(1, source.requests.size)
                assertTrue(target.requests.isEmpty())
            }
        }
    }

    @Test fun httpFailuresHaveUsefulMessagesWithoutEchoingTheResponseBodyOrKey() = runBlocking {
        val cases = mapOf(401 to LocalProviderModelProblem.Authentication, 403 to LocalProviderModelProblem.Authentication,
            404 to LocalProviderModelProblem.Unsupported, 405 to LocalProviderModelProblem.Unsupported,
            429 to LocalProviderModelProblem.RateLimited, 503 to LocalProviderModelProblem.Service)
        for ((code, expected) in cases) Server("SECRET_RESPONSE_BODY sk-test-only-12345678", code).use { server ->
            val result = LocalProviderModelDiscovery(LocalProviderStore(Memory())).fetch(request(server.url))
            assertEquals(failure(expected), result)
            assertFalse(result.toString().contains("SECRET_RESPONSE_BODY"))
            assertFalse(result.toString().contains("sk-test"))
            assertTrue(expected.message.isNotEmpty())
        }
    }

    @Test fun oversizedCatalogFailsWithoutReadingAnUnboundedBody() = runBlocking {
        Server(" ".repeat(2 * 1024 * 1024 + 1)).use { server ->
            assertEquals(failure(LocalProviderModelProblem.Response), LocalProviderModelDiscovery(LocalProviderStore(Memory())).fetch(request(server.url)))
        }
    }

    @Test fun aClosedEndpointReturnsANetworkFailure() = runBlocking {
        val server = Server("{}")
        val url = server.url
        server.close()
        assertEquals(failure(LocalProviderModelProblem.Network), LocalProviderModelDiscovery(LocalProviderStore(Memory())).fetch(request(url)))
    }

    @Test fun changedCredentialsClearResultsAndIgnoreAnOldNonCancellableCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = CompletableDeferred<LocalProviderModelResult>()
            val search = LocalProviderModelSearch(scope) { withContext(NonCancellable) { old.await() } }
            search.fetch(request())
            assertTrue(search.state.value.loading)
            search.clear()
            old.complete(models("stale"))
            yield()
            assertEquals(LocalProviderModelSearchState(), search.state.value)
        } finally { scope.cancel() }
    }

    @Test fun anOlderRequestCannotReplaceANewerCatalog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = CompletableDeferred<LocalProviderModelResult>()
            val calls = AtomicInteger()
            val search = LocalProviderModelSearch(scope) { if (calls.incrementAndGet() == 1) withContext(NonCancellable) { old.await() } else models("current") }
            search.fetch(request())
            search.fetch(request(key = "sk-new-key-12345678"))
            assertEquals(models("current"), search.state.value.result)
            old.complete(models("stale"))
            yield()
            assertEquals(models("current"), search.state.value.result)
        } finally { scope.cancel() }
    }

    @Test fun closingTheFormDropsInflightResultsAndPreventsNewRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = CompletableDeferred<LocalProviderModelResult>()
            var calls = 0
            val search = LocalProviderModelSearch(scope) { calls++; withContext(NonCancellable) { old.await() } }
            search.fetch(request())
            search.close()
            old.complete(models("stale"))
            yield()
            search.fetch(request())
            assertEquals(1, calls)
            assertEquals(LocalProviderModelSearchState(), search.state.value)
        } finally { scope.cancel() }
    }
}
