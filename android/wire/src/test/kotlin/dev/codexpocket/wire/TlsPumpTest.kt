package dev.codexpocket.wire

import java.security.MessageDigest
import java.util.ArrayDeque
import javax.net.ssl.SSLContext
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

internal fun certificate(host: String = "pocket.local"): HeldCertificate = HeldCertificate.Builder().ecdsa256()
    .commonName(host).addSubjectAlternativeName(host).build()
internal fun fingerprint(cert: HeldCertificate): String = MessageDigest.getInstance("SHA-256")
    .digest(cert.certificate.encoded).joinToString("") { "%02x".format(it) }
internal fun serverEngine(cert: HeldCertificate, protocol: String? = null) = SSLContext.getInstance("TLS").apply {
    val keys = HandshakeCertificates.Builder().heldCertificate(cert).build()
    init(arrayOf(keys.keyManager), null, null)
}.createSSLEngine().apply { useClientMode = false; if (protocol != null) enabledProtocols = arrayOf(protocol) }

class TlsPumpTest {
    private class Pair(cert: HeldCertificate, pin: String = fingerprint(cert), protocol: String? = null) {
        val toServer = ArrayDeque<ByteArray>(); val toClient = ArrayDeque<ByteArray>()
        val clientData = mutableListOf<Byte>(); val serverData = mutableListOf<Byte>()
        var clientReady = false; var serverReady = false; var clientClosed = false; var serverClosed = false
        val client = TlsPump(pinnedEngine(pin), { require(it.size <= 65536); toServer.add(it); TransportSendResult.ACCEPTED },
            { clientData.addAll(it.toList()) }, { clientReady = true }, { clientClosed = true })
        val server = TlsPump(serverEngine(cert, protocol), { toClient.add(it); TransportSendResult.ACCEPTED },
            { serverData.addAll(it.toList()) }, { serverReady = true }, { serverClosed = true })
        fun drain(fragment: Int) {
            var rounds = 0
            while (toServer.isNotEmpty() || toClient.isNotEmpty()) {
                check(++rounds < 10000)
                while (toServer.isNotEmpty()) toServer.remove().toList().chunked(fragment).forEach { server.receive(it.toByteArray()) }
                while (toClient.isNotEmpty()) toClient.remove().toList().chunked(fragment).forEach { client.receive(it.toByteArray()) }
            }
        }
        fun handshake(fragment: Int) { server.begin(); client.begin(); drain(fragment) }
    }

    @Test fun realTls12And13FragmentedHandshakeDuplexAndCloseNotify() {
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
            val pair = Pair(certificate(), protocol = protocol)
            pair.handshake(7)
            assertTrue(pair.clientReady); assertTrue(pair.serverReady)
            val outgoing = ByteArray(230000) { (it % 251).toByte() }
            val incoming = "private server response 你好".toByteArray()
            pair.client.write(outgoing); pair.server.write(incoming); pair.drain(31)
            assertArrayEquals(outgoing, pair.serverData.toByteArray())
            assertArrayEquals(incoming, pair.clientData.toByteArray())
            pair.client.close(); pair.drain(3)
            assertTrue(pair.serverClosed); assertTrue(pair.clientClosed)
        }
    }

    @Test fun wrongPinAndWrongHostnameAndExpiredCertificateAreRejected() {
        val valid = certificate()
        val expired = HeldCertificate.Builder().ecdsa256().commonName("pocket.local").addSubjectAlternativeName("pocket.local")
            .validityInterval(1000, 2000).build()
        for (pair in listOf(Pair(valid, "00".repeat(32)), Pair(certificate("wrong.local")), Pair(expired))) {
            assertThrows(Exception::class.java) { pair.handshake(13) }
            assertFalse(pair.clientReady)
        }
    }
}
