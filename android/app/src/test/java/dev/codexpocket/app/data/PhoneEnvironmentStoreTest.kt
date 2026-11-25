package dev.codexpocket.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneEnvironmentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun layout() = LocalRuntimeLayout(temporary.newFolder(), temporary.newFolder())
    private fun bundle(revision: String = "first", extras: Map<String, String> = emptyMap()): Pair<PhoneLinuxManifest, ByteArray> {
        val files = (LocalRuntimeLayout.RUNTIME_FILES.associate { "runtime/$it" to "$revision-$it" } +
            mapOf("rootfs/usr/bin/bash" to "bash", "rootfs/usr/bin/bwrap" to "bwrap")) + extras
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } } }.toByteArray()
        val manifest = PhoneLinuxManifest.parse(buildJsonObject {
            put("schema", 1); put("revision", revision); put("abi", "x86_64")
            put("archiveBytes", bytes.size); put("unpackedBytes", files.values.sumOf { it.toByteArray().size }); put("entryCount", files.size)
            put("archiveSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            putJsonArray("executables") { files.keys.filter { !it.endsWith("manifest.so") && (it.startsWith("runtime/") || it.startsWith("rootfs/")) }.forEach { add(it) } }
            putJsonArray("links") { }
        }.toString())
        return manifest to bytes
    }
    private fun store(layout: LocalRuntimeLayout, bundle: Pair<PhoneLinuxManifest, ByteArray>) =
        PhoneEnvironmentStore(layout, bundle.first) { _, _ -> fail("No symlinks in fixture") }.also {
            it.partial.parentFile.mkdirs(); it.partial.writeBytes(bundle.second)
        }

    @Test fun installsWholeEnvironmentAndSeparatesNativeBootstrapFromDownloadedElf() {
        val layout = layout(); val store = store(layout, bundle())
        assertFalse(store.reuseCurrent(allowOlder = true))
        store.install(PhonePreparationCancellation())
        assertEquals("bash", File(store.image, "usr/bin/bash").readText())
        assertEquals(File(layout.root, "linux/runtimes/first/libcodex.so"), layout.codex)
        assertTrue(File(layout.runtime, "libpocket-runtime-manifest.so").isFile)
        assertEquals("first", File(layout.root, "linux/current-runtime").readText())
        assertFalse(store.partial.exists())
        val command = phoneLinuxCommand(layout, layout.codex, listOf("--version"))
        assertEquals(File(layout.libraries, "libproot.so").path, command.first())
        assertEquals(layout.codex.path, command[command.lastIndex - 1])
        assertTrue(command.contains("${File(layout.runtime, "libcodex-code-mode-host.so")}:${File(layout.runtime, "codex-code-mode-host")}"))
        assertTrue(store.reuseCurrent(allowOlder = false))
        assertFalse(layout.home.exists())
    }
    @Test fun upgradePreservesLinuxPackagesProjectsCredentialsAndOldRuntime() {
        val layout = layout(); val first = store(layout, bundle()); first.install(PhonePreparationCancellation())
        val userTool = File(first.image, "usr/bin/bwrap").apply { writeText("user-tool") }
        val config = File(layout.codexHome, "config.toml").apply { parentFile.mkdirs(); writeText("user-settings") }
        val project = File(layout.projects, "work/main.txt").apply { parentFile.mkdirs(); writeText("work") }
        val oldRuntime = layout.runtime
        val next = store(layout, bundle("second", mapOf("rootfs/usr/lib/x86_64-linux-gnu/libcap.so.2.66" to "cap")))
        assertTrue(next.reuseCurrent(allowOlder = true))
        assertEquals(oldRuntime, layout.runtime)
        assertFalse(next.reuseCurrent(allowOlder = false))
        next.install(PhonePreparationCancellation())
        assertEquals("second", layout.runtime.name)
        assertTrue(oldRuntime.isDirectory)
        assertEquals("cap", File(first.image, "usr/lib/x86_64-linux-gnu/libcap.so.2.66").readText())
        assertEquals("user-tool", userTool.readText()); assertEquals("user-settings", config.readText()); assertEquals("work", project.readText())
    }
    @Test fun corruptArchiveCannotPublishAndIsDiscardedForCleanRetry() {
        val layout = layout(); val store = store(layout, bundle())
        store.partial.writeBytes(store.partial.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() })
        assertThrows(PhoneEnvironmentIntegrityException::class.java) { store.install(PhonePreparationCancellation()) }
        assertEquals(layout.libraries, layout.runtime)
        assertFalse(store.image.exists()); assertFalse(store.partial.exists())
        assertFalse(File(layout.root, "linux/current-runtime").exists())
    }
    @Test fun cancelledExtractionKeepsArchiveAndCurrentInstallation() {
        val layout = layout(); val first = store(layout, bundle()); first.install(PhonePreparationCancellation())
        val second = store(layout, bundle("second")); val cancellation = PhonePreparationCancellation()
        assertThrows(CancellationException::class.java) { second.install(cancellation) { cancellation.cancel() } }
        assertTrue(second.partial.isFile)
        assertEquals("first", layout.runtime.name)
        assertEquals("first", File(layout.root, "linux/current-runtime").readText())
        assertFalse(File(layout.root, "linux/runtimes/second").exists())
        second.install(PhonePreparationCancellation())
        assertEquals("second", layout.runtime.name)
    }
    @Test fun incompleteExistingRootfsIsPreserved() {
        val layout = layout(); val store = store(layout, bundle())
        val retained = File(store.image, "keep").apply { parentFile.mkdirs(); writeText("retained") }
        assertThrows(dev.codexpocket.wire.WireException::class.java) { store.reuseCurrent(true) }
        assertEquals("retained", retained.readText())
    }
    @Test fun archiveCannotInstallUserHomeOrTaskState() {
        val layout = layout(); val store = store(layout, bundle(extras = mapOf("home/overwrite" to "bad")))
        assertThrows(IllegalArgumentException::class.java) { store.install(PhonePreparationCancellation()) }
        assertFalse(layout.home.exists()); assertFalse(store.image.exists())
    }
}
