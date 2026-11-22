package dev.codexpocket.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkspaceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, String?>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) { zip.putNextEntry(ZipEntry(name)); if (text != null) zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    private fun refused(root: File, name: String, input: ByteArrayInputStream) {
        assertThrows(IllegalArgumentException::class.java) { LocalWorkspace.importZip(input, root, name) }
        // Neither a half-written project nor its staging directory remains.
        assertTrue(root.list().orEmpty().none { it.contains("demo") })
    }

    @Test fun importedArchiveBecomesOneProjectAndExportingItGivesTheSameFilesBack() {
        val root = folder.newFolder("projects")
        val project = LocalWorkspace.importZip(archive("src/" to null, "src/main.rs" to "fn main() {}", "README.md" to "hello"), root, "demo.zip")
        assertEquals(File(root, "demo"), project)
        assertEquals("fn main() {}", File(project, "src/main.rs").readText())
        assertEquals(listOf("demo"), LocalWorkspace.projects(root))
        val exported = ByteArrayOutputStream()
        LocalWorkspace.exportZip(project, exported)
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(exported.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(mapOf("src/main.rs" to "fn main() {}", "README.md" to "hello"), files)
    }

    @Test fun archiveThatWouldWriteOutsideItsProjectLeavesNothingBehind() {
        val root = folder.newFolder("projects")
        val outside = File(folder.root, "escaped.txt")
        refused(root, "demo", archive("ok.txt" to "fine", "../../escaped.txt" to "bad"))
        assertFalse(outside.exists())
        refused(root, "demo", archive())
    }

    @Test fun existingProjectIsNeverOverwrittenAndNamesMustBeOneDirectory() {
        val root = folder.newFolder("projects")
        File(root, "demo").mkdirs()
        File(root, "demo/kept.txt").writeText("mine")
        assertThrows(IllegalArgumentException::class.java) { LocalWorkspace.importZip(archive("a.txt" to "new"), root, "demo") }
        assertEquals("mine", File(root, "demo/kept.txt").readText())
        for (bad in listOf("", "  ", ".", "..", ".hidden", "a/b", "a:b", "x".repeat(81))) assertNull(bad, LocalWorkspace.projectName(bad))
        assertEquals("我的项目", LocalWorkspace.projectName(" 我的项目.zip "))
    }
}
