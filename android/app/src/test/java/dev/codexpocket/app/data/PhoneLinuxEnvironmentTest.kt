package dev.codexpocket.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneLinuxEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(path: String = "usr/bin/tool", content: String = "tool"): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() }
    }.toByteArray()
    private fun manifest(bytes: ByteArray, links: List<Pair<String,String>> = emptyList()) = PhoneLinuxManifest.parse(buildJsonObject {
        put("schema",1); put("revision","test-1"); put("abi","x86_64"); put("archiveBytes",bytes.size)
        put("archiveSha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        put("unpackedBytes",4); put("entryCount",1); putJsonArray("executables") { add("usr/bin/tool") }
        putJsonArray("links") { links.forEach { (path,target) -> add(buildJsonObject { put("path",path); put("target",target) }) } }
    }.toString())
    @Test fun installedImageHasExactBytesAndCompletionMarker() {
        val bytes=archive(); val root=temporary.newFolder(); var progress=0f
        PhoneLinuxInstaller.install(manifest(bytes),{ByteArrayInputStream(bytes)},root,{_,_->fail("No links expected")},{progress=it})
        assertEquals("tool",File(root,"usr/bin/tool").readText())
        assertTrue(File(root,".pocket-environment.json").isFile)
        assertFalse(File(root,".pocket-install.zip").exists())
        assertEquals(1f,progress)
    }
    @Test fun badArchiveCannotMarkInstallationComplete() {
        val bytes=archive(); val corrupted=bytes.copyOf().also { it[20]=(it[20].toInt() xor 1).toByte() }; val root=temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            PhoneLinuxInstaller.install(manifest(bytes),{ByteArrayInputStream(corrupted)},root,{_,_->})
        }
        assertFalse(File(root,".pocket-environment.json").exists())
    }
    @Test fun archivePathsAndLinksCannotEscape() {
        listOf("../escape","/absolute","a/../../bad","C:/bad","a\\bad").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { PhoneLinuxInstaller.relative(path) }
        }
        assertThrows(IllegalArgumentException::class.java) { PhoneLinuxInstaller.link("bin/bad","../../escape") }
        assertEquals("usr/bin",PhoneLinuxInstaller.link("bin","usr/bin"))
        assertEquals("opt/node/bin/node",PhoneLinuxInstaller.link("usr/bin/node","../../opt/node/bin/node"))
        val bytes=archive("../escape"); val root=temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            PhoneLinuxInstaller.install(manifest(bytes),{ByteArrayInputStream(bytes)},root,{_,_->})
        }
        assertFalse(File(root.parentFile,"escape").exists())
    }
    @Test fun extractionCannotWriteThroughManifestSymlinkParents() {
        val bytes=archive(); val root=temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            PhoneLinuxInstaller.install(manifest(bytes,listOf("usr" to "other")),{ByteArrayInputStream(bytes)},root,{_,_->})
        }
    }
    @Test fun prootKeepsNativeAndWorkspacePathsUnchanged() {
        val layout=LocalRuntimeLayout(File("/data/app/pocket/lib/x86_64"),File("/data/user/0/pocket/files/this-phone"))
        val command=phoneLinuxCommand(layout,layout.agent,listOf("run","--state",layout.state.path))
        assertEquals(File(layout.libraries,"libproot.so").path,command.first())
        assertTrue(command.windowed(2).contains(listOf("-b",layout.libraries.path)))
        assertTrue(command.windowed(2).contains(listOf("-b",
            "${File(layout.libraries,"libcodex-code-mode-host.so").path}:${File(layout.libraries,"codex-code-mode-host").path}")))
        assertTrue(command.windowed(2).contains(listOf("-b",layout.root.path)))
        assertEquals(listOf(layout.agent.path,"run","--state",layout.state.path),command.takeLast(4))
    }
    @Test fun dependencyUpgradeAddsMissingLibrariesAndPreservesExistingPackages() {
        val stage = temporary.newFolder(); val image = temporary.newFolder()
        fun write(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        write(stage, "usr/bin/bwrap", "packaged"); write(image, "usr/bin/bwrap", "user-version")
        write(stage, "usr/lib/x86_64-linux-gnu/libcap.so.2.66", "library")
        write(stage, "usr/bin/python3", "not-an-upgrade-target")
        PhoneLinuxDependencies.merge(stage, image, manifest(archive())) { _, _ -> fail("No links") }
        assertEquals("user-version", File(image, "usr/bin/bwrap").readText())
        assertEquals("library", File(image, "usr/lib/x86_64-linux-gnu/libcap.so.2.66").readText())
        assertFalse(File(image, "usr/bin/python3").exists())
    }
}
