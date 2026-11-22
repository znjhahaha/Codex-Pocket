package dev.codexpocket.app.ui.kit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kit and the shared infrastructure it renders through must not depend on Material. */
class KitNoMaterialTest {
    private val ui = File("src/main/java/dev/codexpocket/app/ui")
    private val infrastructure = listOf("PocketOverlay.kt", "PocketGlass.kt", "PocketProgress.kt", "PocketMotionText.kt",
        "ReasoningSweep.kt", "ActivityMotionIcon.kt", "PocketShapes.kt", "PocketIcons.kt", "ReadingTypography.kt")

    @Test fun kitAndItsInfrastructureNeverReferenceMaterial() {
        val kit = File(ui, "kit").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Kit sources were not found from ${File(".").absolutePath}", kit.size >= 12)
        val shared = infrastructure.map { File(ui, it) }
        shared.forEach { assertTrue("${it.name} is missing", it.isFile) }
        val offenders = (kit + shared).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> if ("androidx.compose.material" in line) "${file.name}:${index + 1}" else null }
        }
        assertEquals("Material references", emptyList<String>(), offenders)
    }

    /** Screens rebuilt on the kit, by file-name prefix. Append a prefix when another screen has been moved over. */
    private val rebuiltScreens = listOf("Usage", "SshLocationSheet")

    @Test fun rebuiltScreensNeverReferenceMaterial() {
        rebuiltScreens.forEach { prefix ->
            val sources = ui.listFiles { file -> file.isFile && file.extension == "kt" && file.name.startsWith(prefix) }.orEmpty().sortedBy { it.name }
            assertTrue("No $prefix*.kt sources were found from ${File(".").absolutePath}", sources.isNotEmpty())
            val offenders = sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line -> if ("androidx.compose.material" in line) "${file.name}:${index + 1}" else null }
            }
            assertEquals("Material references in $prefix*.kt", emptyList<String>(), offenders)
        }
    }
}
