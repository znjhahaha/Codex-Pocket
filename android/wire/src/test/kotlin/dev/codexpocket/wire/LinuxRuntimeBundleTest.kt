package dev.codexpocket.wire

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimeBundleTest {
    private fun bundle(version: String = "1.2.3", architecture: String = "x86_64"): File {
        val directory = Files.createTempDirectory("pocket-bundle").toFile().apply { deleteOnExit() }
        File(directory, "pocket-agent").writeBytes(ByteArray(4096) { 1 })
        File(directory, "pocket-runtime-host").writeBytes(ByteArray(2048) { 2 })
        TestRuntimeSigner.sign(directory, version, architecture)
        return directory
    }
    private fun refused(directory: File) {
        val failure = assertThrows(WireException::class.java) { TestRuntimeSigner.verified(directory) }
        assertEquals("ssh_runtime_invalid", failure.code)
    }

    @Test fun intactBundleNamesItsVersionArchitectureAndEveryFileDigest() {
        val directory = bundle()
        val verified = TestRuntimeSigner.verified(directory)
        assertEquals("1.2.3", verified.version)
        assertEquals("x86_64", verified.architecture)
        assertEquals(TestRuntimeSigner.sha256(File(directory, "runtime-manifest.json")), verified.generation)
        assertEquals(listOf("pocket-agent", "pocket-runtime-host", "runtime-manifest.json", "runtime-manifest.sig"), verified.files)
        for (name in verified.files) assertEquals(TestRuntimeSigner.sha256(File(directory, name)), verified.digests[name])
        assertNotEquals(verified.generation, TestRuntimeSigner.verified(bundle(version = "1.2.4")).generation)
    }

    @Test fun changedExecutableManifestOrSignatureIsRefused() {
        bundle().let { File(it, "pocket-agent").appendBytes(byteArrayOf(0)); refused(it) }
        bundle().let { File(it, "pocket-runtime-host").writeBytes(ByteArray(2048) { 3 }); refused(it) }
        bundle().let { File(it, "runtime-manifest.json").apply { writeText(readText().replace("1.2.3", "9.9.9")) }; refused(it) }
        bundle().let { File(it, "runtime-manifest.sig").writeText(File(bundle("2.0.0"), "runtime-manifest.sig").readText()); refused(it) }
        bundle().let { File(it, "runtime-manifest.sig").writeText("AAAA"); refused(it) }
        bundle().let { File(it, "runtime-manifest.sig").delete(); refused(it) }
        bundle().let { File(it, "pocket-runtime-host").delete(); refused(it) }
        refused(bundle(architecture = "riscv64"))
        refused(bundle(version = "1.0; rm -rf /"))
    }

    @Test fun shippedTrustRefusesABundleSignedByAnyOtherKey() {
        val failure = assertThrows(WireException::class.java) { LinuxRuntimeBundle.verified(bundle()) }
        assertEquals("ssh_runtime_invalid", failure.code)
    }

    /** The trust parameter exists for these fixtures; product code must keep going through the pinned entry point. */
    @Test fun noProductCodeChoosesItsOwnTrustAnchor() {
        val android = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val callers = listOf("wire/src/main", "app/src/main").flatMap { File(android, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { "verifiedAgainst(" in it.readText() }.map { it.name }
        assertEquals(listOf("LinuxRuntimeBundle.kt"), callers)
        assertEquals(3, File(android, "wire/src/main/kotlin/dev/codexpocket/wire/LinuxRuntimeBundle.kt").readText().split("verifiedAgainst(").size)
    }
}
