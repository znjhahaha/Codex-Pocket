package dev.codexpocket.app.data

import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRuntimeLayoutTest {
    @get:Rule val folder = TemporaryFolder()
    private fun layout() = LocalRuntimeLayout(folder.newFolder("lib"), File(folder.root, "this-phone"))

    @Test fun runtimeIsOfferedOnlyWhenEveryPackagedExecutableIsInstalled() {
        val layout = layout()
        assertFalse(layout.available)
        for (name in LocalRuntimeLayout.REQUIRED.dropLast(1)) File(layout.libraries, name).writeText("x")
        assertFalse(layout.available)
        File(layout.libraries, LocalRuntimeLayout.REQUIRED.last()).writeText("x")
        assertTrue(layout.available)
    }

    @Test fun childEnvironmentNamesEverythingTheStaticExecutablesCannotFindOnAndroid() {
        val layout = layout()
        val environment = layout.environment("http://pocket:secret@127.0.0.1:4000")
        assertEquals(File(layout.root, "home").path, environment["HOME"])
        assertEquals(File(layout.root, "home/.codex").path, environment["CODEX_HOME"])
        assertEquals(File(layout.libraries, "libcodex.so").path, environment["CODEX_CLI_PATH"])
        assertEquals(layout.trustRoots.path, environment["SSL_CERT_FILE"])
        assertEquals("http://pocket:secret@127.0.0.1:4000", environment["HTTPS_PROXY"])
        assertEquals(environment["HTTPS_PROXY"], environment["https_proxy"])
        // The agent, its host and Codex talk to each other over loopback; that must not go through the tunnel.
        assertTrue(environment.getValue("NO_PROXY").split(',').containsAll(listOf("127.0.0.1", "localhost")))
        assertEquals("/system/bin/sh", environment["SHELL"])
        assertEquals("此手机", environment["COMPUTERNAME"])
        assertFalse(environment.containsKey("HTTP_PROXY"))
    }

    @Test fun firstPreparationWritesTheSandboxDefaultAndLaterOnesKeepWhatIsThere() {
        val layout = layout()
        layout.prepare("first roots")
        val config = File(layout.codexHome, "config.toml")
        assertEquals("sandbox_mode = \"danger-full-access\"\n", config.readText())
        assertTrue(layout.projects.isDirectory && layout.state.isDirectory && layout.temporary.isDirectory)
        config.writeText("model = \"chosen\"\n")
        layout.prepare("second roots")
        assertEquals("model = \"chosen\"\n", config.readText())
        assertEquals("second roots", layout.trustRoots.readText())
    }

    @Test fun trustRootsAreWrittenAsCertificatesTheExecutablesCanRead() {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
        val certificates = factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single().acceptedIssuers.take(3)
        assertEquals(3, certificates.size)
        val pem = LocalRuntimeLayout.pem(certificates)
        assertEquals(3, pem.split("-----BEGIN CERTIFICATE-----").size - 1)
        assertTrue(pem.lines().all { it.length <= 64 })
        val parsed = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
        assertEquals(certificates, parsed.toList())
    }
}
