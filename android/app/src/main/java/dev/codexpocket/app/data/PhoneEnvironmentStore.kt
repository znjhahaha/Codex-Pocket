package dev.codexpocket.app.data

import dev.codexpocket.wire.WireException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Owns only linux/{downloads,runtimes,rootfs}; project, account and task paths never enter installation. */
internal class PhoneEnvironmentStore(private val layout: LocalRuntimeLayout, private val manifest: PhoneLinuxManifest,
    private val symlink: (String, File) -> Unit) {
    private val root = File(layout.root, "linux")
    val image = File(root, "rootfs")
    val partial = File(root, "downloads/${manifest.revision}-${manifest.abi}.zip.part")
    private val runtimes = File(root, "runtimes")
    private val runtime = File(runtimes, manifest.revision)
    private val current = File(root, "current-runtime")

    init {
        require(LocalRuntimeLayout.RUNTIME_FILES.all { "runtime/$it" in manifest.executables || it == "libpocket-runtime-manifest.so" })
        require(manifest.executables.all { it.startsWith("rootfs/") || it.startsWith("runtime/") })
        require(manifest.links.all { (path, target) -> path.startsWith("rootfs/") && PhoneLinuxInstaller.link(path, target).startsWith("rootfs/") })
    }

    private fun installed(directory: File, expected: PhoneLinuxManifest? = null): Boolean {
        val saved = runCatching { PhoneLinuxManifest.parse(File(directory, ".pocket-environment.json").readText()) }.getOrNull() ?: return false
        return saved.abi == manifest.abi && saved.revision == directory.name &&
            (expected == null || saved.archiveSha256 == expected.archiveSha256) &&
            LocalRuntimeLayout.RUNTIME_FILES.all { File(directory, it).isFile }
    }

    private fun validateImage(): Boolean {
        if (!image.exists()) return false
        val saved = runCatching { PhoneLinuxManifest.parse(File(image, ".pocket-environment.json").readText()) }.getOrNull()
        if (saved == null || saved.abi != manifest.abi)
            throw WireException("local_environment_invalid", "手机 Linux 环境信息不匹配，原有目录已保留")
        return true
    }

    /** Opening the app or reconnecting can reuse an installation, but cannot start a download. */
    fun reuseCurrent(allowOlder: Boolean): Boolean {
        if (!validateImage()) return false
        val selected = when {
            installed(runtime, manifest) -> runtime
            allowOlder -> {
                val revision = current.takeIf(File::isFile)?.readText()?.trim()
                if (revision == null || !revision.matches(Regex("[A-Za-z0-9._-]{1,96}")) || revision in setOf(".", "..")) return false
                File(runtimes, revision).takeIf { installed(it) } ?: return false
            }
            else -> return false
        }
        layout.useRuntime(selected)
        return true
    }

    fun checkSpace() {
        root.mkdirs()
        val retained = partial.takeIf(File::isFile)?.length()?.coerceAtMost(manifest.archiveBytes) ?: 0
        if (root.usableSpace < manifest.unpackedBytes + manifest.archiveBytes - retained + 64L * 1024 * 1024)
            throw WireException("local_environment_space", "手机空间不足，请腾出空间后重试")
        validateImage()
    }

    fun install(cancellation: PhonePreparationCancellation, progress: (Float) -> Unit = {}) {
        root.mkdirs()
        val stage = File(root, "preparing-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            PhoneLinuxInstaller.installArchive(manifest, partial, stage, symlink, progress, cancellation::check)
            val stagedImage = File(stage, "rootfs")
            val stagedRuntime = File(stage, "runtime")
            require(stagedImage.isDirectory && stagedRuntime.isDirectory)
            require(stage.listFiles().orEmpty().map { it.name }.toSet() == setOf("rootfs", "runtime", ".pocket-environment.json"))
            require(stagedRuntime.listFiles().orEmpty().map { it.name }.toSet() == LocalRuntimeLayout.RUNTIME_FILES.toSet())
            require(LocalRuntimeLayout.RUNTIME_FILES.all { File(stagedRuntime, it).isFile })
            cancellation.check()
            if (!validateImage()) {
                File(stagedImage, ".pocket-environment.json").writeText(manifest.raw)
                Files.move(stagedImage.toPath(), image.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                val rootManifest = manifest.copy(executables = manifest.executables.filter { it.startsWith("rootfs/") }.map { it.removePrefix("rootfs/") },
                    links = manifest.links.map { (path, target) -> path.removePrefix("rootfs/") to target })
                PhoneLinuxDependencies.merge(stagedImage, image, rootManifest, symlink)
                File(image, ".pocket-runtime-dependencies").writeText(manifest.revision)
            }
            // No cancellation between publishing the verified runtime and recording its current revision.
            File(stagedRuntime, ".pocket-environment.json").writeText(manifest.raw)
            runtimes.mkdirs()
            if (!runtime.exists()) Files.move(stagedRuntime.toPath(), runtime.toPath(), StandardCopyOption.ATOMIC_MOVE)
            check(installed(runtime, manifest)) { "A different environment already uses this revision" }
            val pending = File(root, "current-runtime-${UUID.randomUUID()}")
            try {
                pending.writeText(manifest.revision)
                Files.move(pending.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { pending.delete() }
            layout.useRuntime(runtime)
            partial.delete()
        } catch (error: PhoneEnvironmentIntegrityException) {
            // A complete but corrupt part cannot resume; the next attempt must fetch it from byte zero.
            partial.delete()
            throw error
        } finally { stage.deleteRecursively() }
    }
}
