package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class ProjectFileEntry(val path: String, val name: String, val directory: Boolean, val size: Long? = null)
data class ProjectFileListing(
    val path: String = "",
    val entries: List<ProjectFileEntry> = emptyList(),
    val truncated: Boolean = false,
    val roots: List<String> = emptyList(),
)
enum class ReviewSide { OLD, NEW }
data class FileCommentDraft(val path: String, val line: Int, val side: ReviewSide, val text: String)

/** Preserve the server's paths; the server remains the authority for workspace containment. */
fun projectFileListing(value: JsonObject): ProjectFileListing = ProjectFileListing(
    path = (value["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    entries = (value["entries"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        val path = (entry["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val kind = (entry["kind"] as? JsonPrimitive)?.contentOrNull
        if (kind != "file" && kind != "directory") return@mapNotNull null
        ProjectFileEntry(path, (entry["name"] as? JsonPrimitive)?.contentOrNull ?: path.replace('\\', '/').substringAfterLast('/'), kind == "directory", (entry["size"] as? JsonPrimitive)?.longOrNull)
    }.distinctBy { it.path }.sortedWith(compareByDescending<ProjectFileEntry> { it.directory }.thenBy { it.name.lowercase() }),
    truncated = (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
    roots = (value["roots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
)

fun projectParentPath(listing: ProjectFileListing): String? {
    val path = listing.path.replace('\\', '/').trimEnd('/')
    val root = listing.roots.map { it.replace('\\', '/').trimEnd('/') }
        .filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
    return path.substringBeforeLast('/', "").takeIf { path != root && (it == root || it.startsWith("$root/")) }
}
