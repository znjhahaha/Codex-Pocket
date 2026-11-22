package dev.codexpocket.app.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The tunnel serves only a caller holding this process's token, and only CONNECT. */
class LoopbackConnectProxyTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val upstream = ServerSocket(0, 8, loopback)
    private val resolved = mutableListOf<String>()
    private val proxy = LoopbackConnectProxy(resolve = { name -> resolved += name; if (name == "unreachable.invalid") throw java.net.UnknownHostException(name) else loopback })
    private val authorization = "Basic " + Base64.getEncoder().encodeToString(proxy.url.substringAfter("http://").substringBefore('@').toByteArray())

    @After fun close() { proxy.close(); upstream.close() }

    private fun open(head: String): Socket = Socket(loopback, proxy.port).apply { soTimeout = 5000; getOutputStream().apply { write(head.toByteArray()); flush() } }
    private fun status(socket: Socket): String {
        val bytes = StringBuilder()
        val input = socket.getInputStream()
        while (!bytes.endsWith("\r\n\r\n")) { val next = input.read(); if (next < 0) break; bytes.append(next.toChar()) }
        return bytes.lines().first()
    }

    @Test fun authorizedConnectResolvesTheNameHereAndCarriesBytesBothWays() {
        thread { upstream.accept().use { peer -> val buffer = ByteArray(5); peer.getInputStream().readNBytes(buffer, 0, 5); peer.getOutputStream().apply { write(buffer.reversedArray()); flush() } } }
        open("CONNECT api.example.test:${upstream.localPort} HTTP/1.1\r\nHost: api.example.test\r\nProxy-Authorization: $authorization\r\n\r\n").use { socket ->
            assertEquals("HTTP/1.1 200 Connection established", status(socket))
            socket.getOutputStream().apply { write("hello".toByteArray()); flush() }
            assertEquals("olleh", String(socket.getInputStream().readNBytes(5)))
        }
        assertEquals(listOf("api.example.test"), resolved)
    }

    @Test fun callerWithoutTheTokenIsRefusedBeforeAnyNameIsResolved() {
        for (header in listOf("", "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("pocket:wrong".toByteArray())}\r\n")) {
            open("CONNECT api.example.test:443 HTTP/1.1\r\n$header\r\n").use { assertEquals("HTTP/1.1 407 Proxy Authentication Required", status(it)) }
        }
        assertTrue(resolved.isEmpty())
    }

    @Test fun onlyWellFormedConnectIsServed() {
        open("GET http://api.example.test/ HTTP/1.1\r\nProxy-Authorization: $authorization\r\n\r\n").use { assertEquals("HTTP/1.1 405 Method Not Allowed", status(it)) }
        for (target in listOf("api.example.test", "api.example.test:0", ":443", "api.example.test:70000")) {
            open("CONNECT $target HTTP/1.1\r\nProxy-Authorization: $authorization\r\n\r\n").use { assertEquals(target, "HTTP/1.1 400 Bad Request", status(it)) }
        }
        open("CONNECT unreachable.invalid:443 HTTP/1.1\r\nProxy-Authorization: $authorization\r\n\r\n").use { assertEquals("HTTP/1.1 502 Bad Gateway", status(it)) }
    }

    @Test fun addressGivenToTheChildIsLoopbackAndCarriesTheCredential() {
        assertTrue(Regex("http://pocket:[0-9a-f]{48}@127[.]0[.]0[.]1:${proxy.port}").matches(proxy.url))
    }
}
