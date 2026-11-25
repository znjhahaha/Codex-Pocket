package dev.codexpocket.app.data

import android.content.res.AssetManager
import android.os.Build
import android.system.Os
import dev.codexpocket.wire.WireException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*

internal data class PhoneLinuxProgress(val message: String, val fraction: Float? = null, val failed: Boolean = false)

/** An installed Linux home is kept across APK updates, including packages added by the user. */
internal class PhoneLinuxEnvironment(private val layout: LocalRuntimeLayout,
    private val assets: AssetManager, private val progress: (PhoneLinuxProgress?) -> Unit) {
    fun prepare(): File {
        val root = File(layout.root, "linux")
        root.mkdirs()
        val image = File(root, "rootfs")
        val installed = File(image, ".pocket-environment.json")
        if (installed.isFile) {
            val manifest = runCatching { PhoneLinuxManifest.parse(installed.readText()) }.getOrNull()
            if (manifest == null || manifest.abi !in Build.SUPPORTED_ABIS)
                throw WireException("local_environment_invalid", "手机 Linux 环境信息不匹配，原有项目已保留")
            upgradeDependencies(image, manifest)
            mountPoints(image)
            return image
        }
        if (image.exists()) throw WireException("local_environment_invalid", "手机 Linux 环境未完成安装，原有目录已保留")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
            runCatching { assets.open("phone-linux/$candidate/manifest.json").close() }.isSuccess
        } ?: throw WireException("local_environment_missing", "安装包缺少适合此手机的 Linux 工具环境")
        val prefix = "phone-linux/$abi"
        val manifest = assets.open("$prefix/manifest.json").bufferedReader().use { PhoneLinuxManifest.parse(it.readText()) }
        require(manifest.abi == abi)
        if (root.usableSpace < manifest.unpackedBytes + manifest.archiveBytes + 64L * 1024 * 1024)
            throw WireException("local_environment_space", "手机空间不足，请腾出空间后重试")
        val staging = File(root, "preparing-${UUID.randomUUID()}")
        staging.mkdir()
        try {
            progress(PhoneLinuxProgress("首次准备 Linux 工具环境", 0f))
            PhoneLinuxInstaller.install(manifest, { assets.open("$prefix/rootfs.zip") }, staging,
                symlink = { target, link -> Os.symlink(target, link.path) },
                progress = { progress(PhoneLinuxProgress("正在准备 Bash、Git、Python 和 Node", it)) })
            Files.move(staging.toPath(), image.toPath())
            mountPoints(image)
            progress(null)
            return image
        } catch (error: Exception) {
            // Only this invocation's fresh staging directory is disposable.
            staging.deleteRecursively()
            throw error
        }
    }
    private fun upgradeDependencies(image: File, installed: PhoneLinuxManifest) {
        val prefix = "phone-linux/${installed.abi}"
        val packaged = assets.open("$prefix/manifest.json").bufferedReader().use { PhoneLinuxManifest.parse(it.readText()) }
        val marker = File(image, ".pocket-runtime-dependencies")
        if (installed.revision == packaged.revision || marker.takeIf(File::isFile)?.readText() == packaged.revision) return
        val stage = File(image.parentFile, "dependencies-${UUID.randomUUID()}").apply { mkdir() }
        try {
            progress(PhoneLinuxProgress("正在补齐 Linux 运行依赖"))
            PhoneLinuxInstaller.install(packaged, { assets.open("$prefix/rootfs.zip") }, stage, { target, link -> Os.symlink(target, link.path) })
            PhoneLinuxDependencies.merge(stage, image, packaged) { target, link -> Os.symlink(target, link.path) }
            marker.writeText(packaged.revision)
        } finally { stage.deleteRecursively(); progress(null) }
    }
    private fun mountPoints(image: File) {
        for (path in listOf("dev", "proc", "sys", "system", "apex", "tmp", "root", "run", "mnt",
            layout.libraries.path.removePrefix("/"), layout.root.path.removePrefix("/"))) {
            File(image, path).mkdirs()
        }
    }
}

/** Add shipped runtime dependencies without replacing packages the person installed. */
internal object PhoneLinuxDependencies {
    private fun required(path: String) = path == "usr/bin/bwrap" ||
        Regex("usr/lib/(x86_64|aarch64)-linux-gnu/lib(cap|seccomp)\\.so\\.[0-9.]+").matches(path)
    fun merge(stage: File, image: File, manifest: PhoneLinuxManifest, symlink: (String, File) -> Unit) {
        fun target(path: String): File {
            PhoneLinuxInstaller.relative(path)
            val file = File(image, path)
            val parent = requireNotNull(file.parentFile)
            require(parent.canonicalFile.toPath().startsWith(image.canonicalFile.toPath())) { "Dependency directory escapes Linux root" }
            parent.mkdirs()
            return file
        }
        stage.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { source ->
            val path = source.relativeTo(stage).invariantSeparatorsPath
            if (required(path)) {
                val destination = target(path)
                if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    val pending = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}")
                    try {
                        source.copyTo(pending)
                        pending.setExecutable(path in manifest.executables, false)
                        Files.move(pending.toPath(), destination.toPath())
                    } finally { pending.delete() }
                }
            }
        }
        manifest.links.filter { required(it.first) }.forEach { (path, link) ->
            val destination = target(path)
            if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) symlink(link, destination)
        }
    }
}

internal data class PhoneLinuxManifest(val schema: Int, val revision: String, val abi: String,
    val archiveBytes: Long, val archiveSha256: String, val unpackedBytes: Long, val entryCount: Int,
    val executables: List<String>, val links: List<Pair<String, String>>, val raw: String) {
    companion object {
        fun parse(text: String): PhoneLinuxManifest {
            require(text.length <= 4 * 1024 * 1024)
            val value = Json.parseToJsonElement(text).jsonObject
            fun string(key: String) = value.getValue(key).jsonPrimitive.content
            fun number(key: String) = value.getValue(key).jsonPrimitive.long
            require(number("schema") == 1L)
            val executables = value.getValue("executables").jsonArray.map { it.jsonPrimitive.content }
            val links = value.getValue("links").jsonArray.map { it.jsonObject.let { link ->
                link.getValue("path").jsonPrimitive.content to link.getValue("target").jsonPrimitive.content
            } }
            val count = number("entryCount")
            require(count in 1..100_000 && executables.size <= count && links.size <= 50_000)
            require(executables.distinct().size == executables.size && links.map { it.first }.distinct().size == links.size)
            executables.forEach(PhoneLinuxInstaller::relative)
            links.forEach { (path, target) -> PhoneLinuxInstaller.relative(path); PhoneLinuxInstaller.link(path, target) }
            val revision = string("revision"); require(revision.matches(Regex("[A-Za-z0-9._-]{1,96}")))
            val abi = string("abi"); require(abi in setOf("arm64-v8a", "x86_64"))
            val bytes = number("archiveBytes"); val unpacked = number("unpackedBytes")
            require(bytes in 1..2_147_483_648L && unpacked in 1..4_294_967_296L)
            val hash = string("archiveSha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
            return PhoneLinuxManifest(1, revision, abi, bytes, hash, unpacked, count.toInt(), executables, links, text)
        }
    }
}

/** The manifest and archive are from the signed APK; lengths and digest detect interrupted/corrupt inputs. */
internal object PhoneLinuxInstaller {
    fun relative(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && path.getOrNull(1) != ':' && !path.contains('\u0000'))
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
        return path
    }
    fun link(path: String, target: String): String {
        relative(path)
        require(target.isNotBlank() && !target.startsWith('/') && !target.contains('\\') && target.getOrNull(1) != ':' && !target.contains('\u0000'))
        val parts = path.split('/').dropLast(1).toMutableList()
        for (part in target.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }
            else -> parts.add(part)
        }
        require(parts.isNotEmpty())
        return parts.joinToString("/")
    }
    fun install(manifest: PhoneLinuxManifest, source: () -> InputStream, destination: File,
        symlink: (String, File) -> Unit, progress: (Float) -> Unit = {}) {
        require(destination.isDirectory && destination.listFiles().orEmpty().isEmpty())
        val archive = File(destination, ".pocket-install.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        source().use { input -> archive.outputStream().buffered().use { output ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes); if (count < 0) break
                copied += count; require(copied <= manifest.archiveBytes)
                digest.update(bytes, 0, count); output.write(bytes, 0, count)
            }
        } }
        require(copied == manifest.archiveBytes && digest.digest().joinToString("") { "%02x".format(it) } == manifest.archiveSha256)
        val names = mutableSetOf<String>(); var unpacked = 0L
        val linkPaths = manifest.links.map { it.first }.toSet()
        fun child(path: String): File {
            relative(path)
            require(path != ".pocket-install.zip" && path != ".pocket-environment.json")
            val parts = path.split('/')
            require(parts.indices.drop(1).none { parts.take(it).joinToString("/") in linkPaths })
            return File(destination, path)
        }
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.removeSuffix("/"); val file = child(path)
                require(names.add(path) && names.size <= manifest.entryCount && path !in linkPaths)
                require(!entry.isDirectory) // The builder emits files only; parents are implicit.
                file.parentFile!!.mkdirs()
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = zip.read(buffer); if (count < 0) break
                        unpacked += count; require(unpacked <= manifest.unpackedBytes)
                        output.write(buffer, 0, count)
                    }
                }
                progress(unpacked.toFloat() / manifest.unpackedBytes)
            }
        }
        require(names.size == manifest.entryCount && unpacked == manifest.unpackedBytes)
        manifest.executables.forEach { path -> require(path in names); check(child(path).setExecutable(true, true)) }
        manifest.links.forEach { (path, target) ->
            val file = child(path); file.parentFile!!.mkdirs()
            require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)); symlink(target, file)
        }
        archive.delete()
        File(destination, ".pocket-environment.json").writeText(manifest.raw)
    }
}

internal fun phoneLinuxCommand(layout: LocalRuntimeLayout, executable: File, arguments: List<String>): List<String> =
    listOf(File(layout.libraries, "libproot.so").path, "--kill-on-exit", "-0", "-r", File(layout.root, "linux/rootfs").path,
        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
        "-b", layout.libraries.path,
        // The unmodified Linux Codex resolves its helper beside itself by this filename.
        // Android installs only lib*.so files; PRoot supplies the Linux name without copying it.
        "-b", "${File(layout.libraries, "libcodex-code-mode-host.so").path}:${File(layout.libraries, "codex-code-mode-host").path}",
        "-b", layout.root.path, "-w", layout.home.path, executable.path) + arguments
