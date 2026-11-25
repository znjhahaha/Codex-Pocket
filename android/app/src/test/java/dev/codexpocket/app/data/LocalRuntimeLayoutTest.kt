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
        File(layout.libraries,"libcodex-code-mode-host.so").delete()
        assertFalse("Model tools need the code-mode host",layout.available)
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
        assertEquals("/bin/bash", environment["SHELL"])
        assertTrue(environment.getValue("PATH").contains("/opt/node/bin"))
        assertEquals(File(layout.libraries, "libproot-loader.so").path, environment["PROOT_LOADER"])
        assertEquals("此手机", environment["COMPUTERNAME"])
        assertEquals(environment["HTTPS_PROXY"], environment["HTTP_PROXY"])
        assertFalse(environment.containsKey(LocalCodexConfig.KEY_VARIABLE))
    }

    @Test fun keyOfTheServiceInUseReachesCodexThroughItsEnvironmentAndNoFile() {
        val layout = layout()
        val provider = LocalProvider("p", "Relay", "https://relay.example/v1", "sk-secret-0123456789", "gpt-5.5")
        assertEquals("sk-secret-0123456789", layout.environment("http://127.0.0.1:1", provider)[LocalCodexConfig.KEY_VARIABLE])
        layout.prepare("roots", provider)
        val config = File(layout.codexHome, "config.toml").readText()
        assertTrue(config.contains("model_provider = \"pocket\""))
        assertTrue(config.contains("env_key = \"POCKET_PROVIDER_API_KEY\""))
        assertTrue(layout.root.walkTopDown().filter(File::isFile).none { it.readText().contains("sk-secret") })
        // Going back to OpenAI's own sign-in takes the service out again.
        layout.prepare("roots", null)
        assertFalse(File(layout.codexHome, "config.toml").readText().contains("pocket\""))
    }

    @Test fun backendIsReadyOnlyOnceItsListenerIsBound() {
        fun health(ready: Boolean) = """{"available":true,"routeStatus":[{"id":"lan","kind":"lan","listenerReady":$ready},{"id":"mdns-inventory","kind":"discovery","listenerReady":false}]}"""
        assertTrue(LocalRuntimeLayout.listening(health(true)))
        assertTrue(LocalRuntimeLayout.listening("a warning line\n" + health(true) + "\n"))
        assertFalse(LocalRuntimeLayout.listening(health(false)))
        assertFalse(LocalRuntimeLayout.listening("""{"available":true}"""))
        assertFalse(LocalRuntimeLayout.listening(""))
    }

    @Test fun firstPreparationWritesTheDefaultsAndLaterOnesKeepWhatIsThere() {
        val layout = layout()
        layout.prepare("first roots")
        val config = File(layout.codexHome, "config.toml")
        val policy = "\n[shell_environment_policy]\nexclude = [\"POCKET_PROVIDER_API_KEY\"]\n"
        assertEquals("sandbox_mode = \"workspace-write\"\napproval_policy = \"on-request\"\n$policy", config.readText())
        assertTrue(layout.projects.isDirectory && layout.state.isDirectory && layout.temporary.isDirectory)
        config.writeText("model = \"chosen\"\n$policy")
        val written = config.lastModified()
        layout.prepare("second roots")
        assertEquals("model = \"chosen\"\n$policy", config.readText())
        assertEquals(written, config.lastModified())
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
