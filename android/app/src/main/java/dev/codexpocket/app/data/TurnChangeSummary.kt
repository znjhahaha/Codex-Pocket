package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Native cumulative diffs replace earlier snapshots; file-change records are a fallback. */
data class TurnChangeSummary(val turnId: String, val files: List<ActivityFile>, val cumulative: Boolean) {
    val counted = files.filter(::changeLineCountsKnown)
    val additions = counted.sumOf { it.additions }
    val deletions = counted.sumOf { it.deletions }
    val hasUnknownCounts = counted.size != files.size

    fun review(file: ActivityFile? = null): TimelineItem = TimelineItem(turnId, "pocket-change-summary", "fileChange",
        status = "completed", complete = true, raw = buildJsonObject {
            put("changes", buildJsonArray { (file?.let(::listOf) ?: files).forEach { entry -> add(buildJsonObject {
                put("path", entry.path); put("kind", "update"); put("diff", entry.diff)
            }) } })
        })
}

fun changeLineCountsKnown(file: ActivityFile): Boolean = file.diff.lineSequence().any { it.startsWith("@@ ") } &&
    '\u0000' !in file.diff && !file.diff.lineSequence().any { it == "GIT binary patch" || (it.startsWith("Binary files ") && it.endsWith(" differ")) }

fun turnChangeSummaries(timeline: NativeTimeline): List<TurnChangeSummary> {
    val terminal = timeline.completedTurns + timeline.turns.values.filter { it.status in setOf("completed", "failed", "interrupted") }.map { it.id }
    return timeline.items.values.groupBy { it.turnId }.mapNotNull { (turn, items) ->
        if (turn !in terminal || timeline.activeTurn == turn) return@mapNotNull null
        val cumulative = items.lastOrNull { it.type == "turn/diff/updated" && it.raw.obj("params")["diff"] is JsonPrimitive }
        val records = if (cumulative != null) activityFiles(cumulative) else items
            .filter { it.type == "fileChange" && it.complete && it.status !in setOf("failed", "declined", "interrupted", "inProgress") }
            .flatMap { item -> activityFiles(item).mapIndexed { i, file ->
                val change = item.raw.array("changes").getOrNull(i)?.asObject() ?: buildJsonObject {}
                val move = change.obj("kind").string("movePath").ifBlank { change.string("movePath") }
                val rawDiff = change.string("diff")
                val binary = '\u0000' in rawDiff || rawDiff.lineSequence().any { it == "GIT binary patch" || (it.startsWith("Binary files ") && it.endsWith(" differ")) }
                file.copy(path = move.ifBlank { file.path }, kind = if (move.isBlank()) file.kind else "rename", diff = if (binary) rawDiff else file.diff)
            } }
        val files = records.filter { it.path.isNotBlank() }.groupBy { it.path.replace('\\', '/') }.entries.mapIndexed { index, (_, sameFile) ->
            val last = sameFile.last()
            last.copy(id = "summary:$index", diff = if (cumulative != null) last.diff else sameFile.joinToString("\n\n") { it.diff })
        }
        files.takeIf { it.isNotEmpty() }?.let { TurnChangeSummary(turn, it, cumulative != null) }
    }
}

/** Attach once to the final visible row for that turn, including folded tool-only turns. */
fun changeSummaryRows(timeline: NativeTimeline, rows: List<TranscriptRow>): Map<String, List<TurnChangeSummary>> =
    turnChangeSummaries(timeline).mapNotNull { summary ->
        rows.lastOrNull { row -> row.items.any { it.turnId == summary.turnId } }?.key?.let { it to summary }
    }.groupBy({ it.first }, { it.second })
