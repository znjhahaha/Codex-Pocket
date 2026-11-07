package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class ActivityFile(val id: String, val path: String, val kind: String, val diff: String) {
    private val stats = diffStats(diff)
    val additions: Int = stats.first
    val deletions: Int = stats.second
}

private val unifiedHunk = Regex("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@.*")

/** Header-looking source lines inside a hunk still count as changes. */
private fun diffStats(diff: String): Pair<Int, Int> {
    var old = 0
    var new = 0
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        val match = unifiedHunk.matchEntire(line)
        when {
            match != null -> {
                old = match.groupValues[1].toIntOrNull() ?: 1
                new = match.groupValues[2].toIntOrNull() ?: 1
            }
            old > 0 || new > 0 -> when (line.firstOrNull()) {
                '+' -> { additions++; new-- }
                '-' -> { deletions++; old-- }
                ' ' -> { old--; new-- }
            }
            line.startsWith("+") && !line.startsWith("+++") -> additions++
            line.startsWith("-") && !line.startsWith("---") -> deletions++
        }
    }
    return additions to deletions
}

/** Codex sends an added/deleted file as its bare content; give it a hunk so it reads as all-added/all-removed lines. */
internal fun wholeFileDiff(kind: String, diff: String): String {
    val sign = when (kind) { "add", "added" -> '+'; "delete", "deleted" -> '-'; else -> return diff }
    if (diff.isEmpty() || diff.lineSequence().any { unifiedHunk.matches(it) }) return diff
    val lines = diff.removeSuffix("\n").split("\n").map { it.removeSuffix("\r") }
    val header = if (sign == '+') "@@ -0,0 +1,${lines.size} @@" else "@@ -1,${lines.size} +0,0 @@"
    return (listOf(header) + lines.map { "$sign$it" }).joinToString("\n")
}

fun activityFiles(item: TimelineItem): List<ActivityFile> {
    if (item.type == "fileChange") return item.raw.array("changes").mapIndexedNotNull { index, element ->
        val change = element as? JsonObject ?: return@mapIndexedNotNull null
        val kind = (change["kind"] as? JsonPrimitive)?.contentOrNull ?: change.obj("kind").string("type")
        ActivityFile("file:$index", change.string("path"), kind, wholeFileDiff(kind, change.string("diff")))
    }
    if (item.type != "turn/diff/updated") return emptyList()
    val source = item.raw.obj("params").string("diff")
    if (source.isBlank()) return emptyList()
    val lines = source.lines()
    val gitStarts = lines.indices.filter { lines[it].startsWith("diff --git ") }
    val starts = gitStarts.ifEmpty {
        var old = 0
        var new = 0
        lines.indices.filter { index ->
            val line = lines[index]
            val match = unifiedHunk.matchEntire(line)
            when {
                match != null -> {
                    old = match.groupValues[1].toIntOrNull() ?: 1
                    new = match.groupValues[2].toIntOrNull() ?: 1
                    false
                }
                old > 0 || new > 0 -> {
                    if (line.startsWith(" ") || line.startsWith("-")) old--
                    if (line.startsWith(" ") || line.startsWith("+")) new--
                    false
                }
                else -> line.startsWith("--- ") && lines.getOrNull(index + 1)?.startsWith("+++ ") == true
            }
        }
    }.ifEmpty { listOf(0) }
    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1) ?: lines.size
        val block = lines.subList(if (index == 0) 0 else start, end)
        val header = block.takeWhile { !it.startsWith("@@") }
        val after = header.firstOrNull { it.startsWith("+++ ") }?.removePrefix("+++ ")?.substringBefore('\t')
        val before = header.firstOrNull { it.startsWith("--- ") }?.removePrefix("--- ")?.substringBefore('\t')
        val path = (after?.takeUnless { it == "/dev/null" } ?: before
            ?: header.firstOrNull { it.startsWith("diff --git ") }?.substringAfter(" b/"))
            .orEmpty().removePrefix("a/").removePrefix("b/")
        val kind = when { before == "/dev/null" -> "add"; after == "/dev/null" -> "delete"; header.any { it.startsWith("rename to ") } -> "rename"; else -> "update" }
        ActivityFile("diff:$index", path, kind, block.joinToString("\n"))
    }
}
