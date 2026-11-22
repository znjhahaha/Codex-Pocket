package dev.codexpocket.app.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Moves project files between this phone's private Codex workspace and an archive the person picks. */
internal object LocalWorkspace {
    const val MAX_ENTRIES = 20_000
    const val MAX_BYTES = 512L * 1024 * 1024

    /** A single directory name, or null when the text cannot be one. */
    fun projectName(raw: String): String? {
        val name = raw.trim().removeSuffix(".zip").trim()
        return name.takeIf { it.isNotEmpty() && it.length <= 80 && it != "." && it != ".." && !it.startsWith('.') &&
            it.none { c -> c == '/' || c == '\\' || c == ':' || c.isISOControl() } }
    }

    fun projects(root: File): List<String> =
        root.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }.orEmpty().map { it.name }.sorted()

    /** Unpacks into a new project directory. Nothing is left behind unless the whole archive was accepted. */
    fun importZip(input: InputStream, root: File, name: String): File {
        val safe = requireNotNull(projectName(name)) { "Invalid project name" }
        val target = File(root, safe)
        require(!target.exists()) { "A project with this name already exists" }
        val staging = File(root, ".importing-$safe")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Workspace is unavailable" }
        try {
            val base = staging.canonicalFile
            var entries = 0
            var bytes = 0L
            val buffer = ByteArray(65536)
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES) { "Archive has too many files" }
                    val destination = File(base, entry.name).canonicalFile
                    // An entry may not name anything outside the project being created.
                    require(destination.path.startsWith(base.path + File.separator)) { "Archive entry leaves the project" }
                    if (entry.isDirectory) { destination.mkdirs(); continue }
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= MAX_BYTES) { "Archive is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(entries > 0) { "Archive is empty" }
            check(staging.renameTo(target)) { "Project could not be saved" }
            return target
        } finally { staging.deleteRecursively() }
    }

    /** Packs every regular file of the project. Links are left out: they could point outside it. */
    fun exportZip(project: File, output: OutputStream) {
        val base = project.canonicalFile
        require(base.isDirectory) { "Project not found" }
        ZipOutputStream(output).use { zip ->
            base.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
                if (file == base || Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = file.relativeTo(base).invariantSeparatorsPath
                if (file.isDirectory) { zip.putNextEntry(ZipEntry("$relative/")); zip.closeEntry() }
                else if (file.isFile) { zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
            }
        }
    }
}
