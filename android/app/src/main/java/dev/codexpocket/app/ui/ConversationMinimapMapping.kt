package dev.codexpocket.app.ui

import dev.codexpocket.app.data.TranscriptKind
import dev.codexpocket.app.data.TranscriptRow
import dev.codexpocket.app.data.ConversationPresentation
import dev.codexpocket.app.data.NativeTimeline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil

internal data class MinimapTarget(val key: String, val withinRow: Float, val ownerProcessKey: String? = null)
internal data class MinimapVisibleRow(val key: Any, val offset: Int, val size: Int)
internal data class MinimapViewport(val start: Float, val end: Float)
internal data class MinimapThumb(val top: Float, val height: Float)
internal data class MinimapEntry(val key: String, val rowIndex: Int, val kind: TranscriptKind,
    val ownerProcessKey: String? = null)
internal data class MinimapSeekPlan(val scrollKey: String, val expandProcessKey: String? = null)
internal enum class MinimapInk { Text, Strong, Faint, Question, Added, Removed }
internal data class MinimapTick(val key: String, val fraction: Float, val kind: TranscriptKind, val widths: List<Float>,
    val inks: List<MinimapInk> = emptyList())
internal data class MinimapPreview(val key: String, val question: String, val neighbor: String, val position: String)
internal data class MinimapMark(
    val key: String, val kind: TranscriptKind, val left: Float, val top: Float,
    val width: Float, val height: Float, val current: Boolean, val ink: MinimapInk = MinimapInk.Text,
)

private const val MinimapExcerptSourceLimit = 192
private const val MinimapSilhouetteSourceLimit = 1536

internal data class MinimapSample(val weight: Float, val widths: List<Float>, val inks: List<MinimapInk>)

/** Retains immutable item samples across streamed snapshots. No Markdown parse in the draw path. */
internal class MinimapSampleCache {
    private data class Entry(val kind: TranscriptKind, val item: dev.codexpocket.app.data.TimelineItem?,
        val label: String?, val sample: MinimapSample)
    private val values = LinkedHashMap<String, Entry>(128, .75f, true)
    fun get(row: TranscriptRow, create: () -> MinimapSample): MinimapSample {
        val old = values[row.key]
        if (old != null && old.kind == row.kind && old.item === row.items.firstOrNull() && old.label == row.processLabel) return old.sample
        val sample = create()
        values[row.key] = Entry(row.kind, row.items.firstOrNull(), row.processLabel, sample)
        while (values.size > 4096) values.remove(values.keys.first())
        return sample
    }
}

/** Only the immutable loaded/folded snapshot is retained. Chrome and ambiguous keys are excluded. */
internal class ConversationMinimapMap(rows: List<TranscriptRow>, keys: List<String>,
    cache: MinimapSampleCache = MinimapSampleCache()) {
    private val indices = buildMap {
        val duplicate = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        keys.forEachIndexed { index, key -> if (key !in duplicate) put(key, index) }
    }
    private val renderedRows = run {
        val counts = rows.groupingBy { it.key }.eachCount()
        val byKey = buildMap {
            rows.forEachIndexed { index, row ->
                if (row.key in indices && counts[row.key] == 1) put(row.key, MinimapEntry(row.key, index, row.kind))
            }
        }
        keys.mapNotNull(byKey::get)
    }
    private val owners = buildMap {
        rows.filter { it.kind == TranscriptKind.Process }.forEach { process ->
            process.items.forEach { item -> put(item.key, process.key); put("activity:${item.key}", process.key) }
        }
    }
    // This is a navigation projection, independent of LazyColumn rows. Expanded process children
    // already have real row keys and are emitted only once, in their actual rendered order.
    private val navigationRows = buildList {
        val renderedKeys = renderedRows.map { it.key }.toSet()
        val renderedNativeKeys = renderedRows.filter { it.kind != TranscriptKind.Process }
            .flatMap { rows[it.rowIndex].items }.map { it.key }.toSet()
        val emitted = mutableSetOf<String>()
        for (entry in renderedRows) {
            val row = rows[entry.rowIndex]
            if (emitted.add(row.key)) add(row)
            if (row.kind == TranscriptKind.Process) for (item in row.items) {
                if (item.key in renderedNativeKeys) continue
                val children = ConversationPresentation.rows(NativeTimeline(items = linkedMapOf(item.key to item)),
                    foldTasks = false, includeReasoning = true)
                for (child in children) if (child.key !in renderedKeys && child.key !in indices && emitted.add(child.key)) add(child)
            }
        }
    }
    val entries = navigationRows.mapIndexed { index, row -> MinimapEntry(row.key, index, row.kind, owners[row.key]) }
    val navigationKeys = entries.map { it.key }
    private val slots = entries.mapIndexed { slot, row -> row.key to slot }.toMap()
    val userSlots = entries.indices.filter { entries[it].kind == TranscriptKind.User }
    private val questionSlots = IntArray(entries.size).also { questions ->
        var question = -1
        entries.forEachIndexed { slot, entry ->
            if (entry.kind == TranscriptKind.User) question = slot
            questions[slot] = question
        }
    }
    private val samples = entries.mapIndexed { slot, entry -> cache.get(navigationRows[entry.rowIndex]) { sample(slot) } }
    private val starts = FloatArray(entries.size + 1).also { positions ->
        samples.forEachIndexed { index, sample -> positions[index + 1] = positions[index] + sample.weight }
        val total = positions.last().coerceAtLeast(1f)
        for (index in positions.indices) positions[index] /= total
    }
    val ticks = entries.indices.map { slot ->
        MinimapTick(entries[slot].key, (starts[slot] + starts[slot + 1]) / 2f, entries[slot].kind,
            samples[slot].widths, samples[slot].inks)
    }

    private fun prefix(slot: Int, limit: Int = MinimapExcerptSourceLimit): String {
        val row = navigationRows[entries[slot].rowIndex]
        // A process samples its displayed header, never processDetail or its hidden items.
        return (if (row.kind == TranscriptKind.Process) row.processLabel.orEmpty()
            else row.items.firstOrNull()?.text.orEmpty()).take(limit)
    }

    private fun sample(slot: Int): MinimapSample {
        val entry = entries[slot]
        val text = prefix(slot, MinimapSilhouetteSourceLimit)
        val row = navigationRows[entry.rowIndex]
        val first = row.items.firstOrNull()
        // Read only the first loaded file's bounded excerpt. Never build item.diff for the map:
        // that would concatenate every full file on each streamed snapshot.
        val diff = if (row.kind != TranscriptKind.Process && first?.type == "fileChange") {
            val change = (first.raw["changes"] as? JsonArray)?.firstOrNull() as? JsonObject
            (change?.get("diff") as? JsonPrimitive)?.content?.take(MinimapExcerptSourceLimit)
        } else null
        val diffLines = diff?.lineSequence()?.filter { (it.startsWith('+') && !it.startsWith("+++")) ||
            (it.startsWith('-') && !it.startsWith("---")) }?.take(3)?.toList().orEmpty()
        val textWidths = minimapTextWidths(text)
        val widths = when (entry.kind) {
            TranscriptKind.User -> listOf(1f) // A single independent question landmark.
            TranscriptKind.Process -> listOf(.42f)
            TranscriptKind.Activity -> if (diffLines.isEmpty()) listOf((text.length / 72f).coerceIn(.16f, .72f))
                else diffLines.map { (it.length / 64f).coerceIn(.12f, .90f) }
            else -> textWidths
        }
        val inks = if (entry.kind == TranscriptKind.Activity && diffLines.isNotEmpty()) {
            diffLines.map { if (it.startsWith('+')) MinimapInk.Added else MinimapInk.Removed }
        } else List(widths.size) { when (entry.kind) {
            TranscriptKind.User -> MinimapInk.Question
            TranscriptKind.Plan -> MinimapInk.Strong
            TranscriptKind.Error -> MinimapInk.Removed
            TranscriptKind.Activity, TranscriptKind.Process, TranscriptKind.Commentary -> MinimapInk.Faint
            else -> MinimapInk.Text
        } }
        // O(1) length plus a bounded prefix: long messages occupy more rail height without
        // scanning full Markdown, tool output or hidden folded process content.
        val characters = if (entry.kind == TranscriptKind.Process) row.processLabel.orEmpty().length
            else first?.text?.length ?: 0
        val weight = if (entry.kind == TranscriptKind.Process || entry.kind == TranscriptKind.Activity) 1f
            else maxOf(ceil(characters / 64f), textWidths.size.toFloat()).coerceIn(1f, 32f)
        return MinimapSample(weight, widths, inks)
    }

    fun tick(slot: Int): MinimapTick = ticks[slot]
    fun range(slot: Int) = starts[slot]..starts[slot + 1]
    fun currentQuestion(key: String?): String? = key?.let(slots::get)?.let { questionSlots[it] }
        ?.takeIf { it >= 0 }?.let { entries[it].key }
    private val questionIndices = userSlots.mapIndexed { index, slot -> entries[slot].key to index }.toMap()
    fun questionFraction(key: String, maximumGap: Float = 1f): Float? = questionIndices[key]?.let {
        val gap = minOf(1f / userSlots.size, maximumGap.coerceAtLeast(0f))
        .5f + (it - (userSlots.size - 1) / 2f) * gap
    }
    fun userTargetAt(fraction: Float, maximumGap: Float = 1f): MinimapTarget? {
        if (userSlots.isEmpty() || !fraction.isFinite()) return null
        val gap = minOf(1f / userSlots.size, maximumGap.coerceAtLeast(.000001f))
        val index = ((fraction.coerceIn(0f, 1f) - .5f) / gap + userSlots.size / 2f).toInt().coerceIn(0, userSlots.lastIndex)
        return targetForKey(entries[userSlots[index]].key)
    }
    fun slot(key: String): Int? = slots[key]
    fun listIndex(key: String): Int? = if (key in slots) indices[key] else null
    fun targetForKey(key: String, withinRow: Float = 0f): MinimapTarget? = slots[key]?.let {
        MinimapTarget(key, withinRow, entries[it].ownerProcessKey)
    }
    fun seekPlan(target: MinimapTarget, commitHidden: Boolean): MinimapSeekPlan? {
        val entry = slots[target.key]?.let(entries::get) ?: return null
        if (target.ownerProcessKey != null && target.ownerProcessKey != entry.ownerProcessKey) return null
        if (listIndex(target.key) != null) return MinimapSeekPlan(target.key)
        val owner = entry.ownerProcessKey?.takeIf { listIndex(it) != null } ?: return null
        return if (commitHidden) MinimapSeekPlan(target.key, owner) else MinimapSeekPlan(owner)
    }

    private val hiddenEnds = buildMap<String, Int> {
        this@ConversationMinimapMap.entries.forEachIndexed { slot, entry -> if (listIndex(entry.key) == null)
            entry.ownerProcessKey?.let { put(it, slot) } }
    }
    private val renderedRanges = entries.mapIndexed { slot, entry ->
        val last = if (entry.kind == TranscriptKind.Process) hiddenEnds[entry.key] ?: slot else slot
        entry.key to (slot..last)
    }.toMap()

    fun targetAt(fraction: Float): MinimapTarget? {
        if (entries.isEmpty() || !fraction.isFinite()) return null
        val position = fraction.coerceIn(0f, 1f)
        var low = 0; var high = entries.size
        while (low < high) { val mid = (low + high) ushr 1
            if (starts[mid + 1] <= position) low = mid + 1 else high = mid }
        val slot = low.coerceAtMost(entries.lastIndex)
        return targetForKey(entries[slot].key, ((position - starts[slot]) /
            (starts[slot + 1] - starts[slot])).coerceIn(0f, .999999f))
    }

    fun preview(key: String): MinimapPreview? {
        val slot = slots[key] ?: return null
        val question = questionSlots[slot]
        val neighbor = if (entries[slot].kind == TranscriptKind.User) (slot + 1).coerceAtMost(entries.lastIndex) else slot
        return MinimapPreview(key,
            if (question < 0) "暂无已加载提问" else minimapPlainExcerpt(prefix(question)).ifEmpty { "提问" },
            minimapPlainExcerpt(prefix(neighbor)).ifEmpty { "对话片段" },
            "${slot + 1} / ${entries.size} · 已加载")
    }

    fun viewport(visible: List<MinimapVisibleRow>, start: Int, end: Int, reverse: Boolean = false): MinimapViewport? {
        if (entries.isEmpty() || end <= start) return null
        var first = Float.POSITIVE_INFINITY
        var last = Float.NEGATIVE_INFINITY
        for (item in visible) {
            val slots = renderedRanges[item.key] ?: continue
            if (item.size <= 0 || item.offset.toLong() + item.size <= start || item.offset >= end) continue
            val clippedStart = ((start.toDouble() - item.offset) / item.size).toFloat().coerceIn(0f, 1f)
            val clippedEnd = ((end.toDouble() - item.offset) / item.size).toFloat().coerceIn(0f, 1f)
            val from = if (reverse) 1f - clippedEnd else clippedStart
            val to = if (reverse) 1f - clippedStart else clippedEnd
            val length = starts[slots.last + 1] - starts[slots.first]
            first = minOf(first, starts[slots.first] + from * length)
            last = maxOf(last, starts[slots.first] + to * length)
        }
        return if (first.isFinite() && last > first) MinimapViewport(first, last) else null
    }
}

/** Bounded visual line samples, reused by identity; never repeat a short prefix to fill a long row. */
internal fun minimapTextWidths(source: String): List<Float> = buildList {
    for (line in source.take(MinimapSilhouetteSourceLimit).lineSequence()) {
        if (line.isBlank()) continue
        var units = 0
        for (char in line) {
            units += if (char.code in 0x2E80..0xA4CF || char.code in 0xAC00..0xD7A3) 2 else 1
            if (units >= 64) {
                add(.94f)
                units = 0
                if (size >= 24) return@buildList
            }
        }
        if (units > 0) add((units / 64f).coerceIn(.10f, .94f))
        if (size >= 24) break
    }
}.ifEmpty { listOf(.30f) }

/** Whitespace normalization only. Literal Markdown stays literal; no parser, attachments or IO. */
internal fun minimapPlainExcerpt(text: String): String = buildString {
    for (char in text.take(MinimapExcerptSourceLimit)) {
        if (length >= 96) break
        if (char.isWhitespace() || char.isISOControl() || char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069') {
            if (isNotEmpty() && last() != ' ') append(' ')
        } else append(char)
    }
    if (isNotEmpty() && last().isHighSurrogate()) deleteCharAt(lastIndex)
}.trim()

internal fun minimapThumb(viewport: MinimapViewport?, height: Float, inset: Float, minimum: Float): MinimapThumb? {
    if (viewport == null || !height.isFinite() || height <= 0f || !inset.isFinite() || !minimum.isFinite() ||
        !viewport.start.isFinite() || !viewport.end.isFinite() || viewport.end <= viewport.start) return null
    val padding = inset.coerceIn(0f, height / 4f)
    val track = height - padding * 2f
    val span = (viewport.end - viewport.start).coerceIn(0f, 1f)
    val thumbHeight = (span * track).coerceIn(minimum.coerceIn(0f, track), track)
    val progress = if (span >= 1f) 0f else (viewport.start / (1f - span)).coerceIn(0f, 1f)
    return MinimapThumb(padding + progress * (track - thumbHeight), thumbHeight)
}

/** Uses the actual thumb travel, so a minimum-size thumb can still reach either end. */
internal fun minimapDragFraction(viewport: MinimapViewport, thumb: MinimapThumb, height: Float,
    inset: Float, grabOffset: Float, pointerY: Float): Float {
    val padding = inset.coerceIn(0f, height / 4f)
    val travel = height - padding * 2f - thumb.height
    if (travel <= 0f) return 0f
    return ((pointerY - grabOffset - padding) / travel).coerceIn(0f, 1f) *
        (1f - (viewport.end - viewport.start).coerceIn(0f, 1f))
}

internal fun minimapSampleIndices(count: Int, limit: Int): List<Int> {
    if (count <= 0 || limit <= 0) return emptyList()
    val size = minOf(count, limit)
    if (size == 1) return listOf((count - 1) / 2)
    return List(size) { (it.toLong() * (count - 1) / (size - 1)).toInt() }
}

/** Every loaded row contributes; indistinguishable subpixel strokes merge into a pixel band.
 * Geometry is independent of live scroll position, so the host can cache it while the thumb moves.
 */
internal fun minimapMarks(map: ConversationMinimapMap, viewport: MinimapViewport?, width: Float,
    height: Float, density: Float, expanded: Boolean, inspectedKey: String? = null): List<MinimapMark> {
    if (!width.isFinite() || !height.isFinite() || !density.isFinite() || width <= 0 || height <= 0 || density <= 0 || map.entries.isEmpty()) return emptyList()
    val inset = (8f * density).coerceAtMost(height / 4f)
    val track = height - 2 * inset
    val currentRow = viewport?.let { map.targetAt(it.start)?.key }
    val currentKey = if (expanded) currentRow else map.currentQuestion(currentRow)
    val left = ((if (expanded) 8f else 4f) * density).coerceAtMost(width / 4f)
    val room = minOf(if (expanded) 24f * density else width, (width - left * 2f).coerceAtLeast(0f))
    val bands = LinkedHashMap<Int, MinimapMark>()
    fun add(mark: MinimapMark) {
        val band = ((mark.top - inset) / density.coerceAtLeast(1f)).toInt()
        val old = bands[band]
        // A visible/current or touched landmark wins; otherwise retain the longest silhouette.
        if (old == null || mark.key == inspectedKey || mark.current ||
            (old.key != inspectedKey && !old.current && mark.width > old.width)) bands[band] = mark
    }
    val slots = if (expanded) map.entries.indices.toList() else map.userSlots
    for (slot in slots) {
        val tick = map.tick(slot)
        val current = tick.key == currentKey
        val center = inset + (if (expanded) tick.fraction else map.questionFraction(tick.key, 10f * density / track) ?: 0f) * track
        if (!expanded) {
            val length = (if (current || tick.key == inspectedKey) 15f else 7f) * density
            val stroke = (if (current || tick.key == inspectedKey) 1.8f else 1.2f) * density
            val h = stroke.coerceAtMost(track)
            add(MinimapMark(tick.key, tick.kind, left, (center - h / 2).coerceIn(inset, height - inset - h),
                length.coerceAtMost(room), h, current))
        } else {
            val range = map.range(slot)
            val span = (range.endInclusive - range.start) * track
            if (tick.kind == TranscriptKind.User) {
                val h = minOf(2.2f * density, span * .55f).coerceAtMost(track)
                add(MinimapMark(tick.key, tick.kind, left, (center - h / 2).coerceIn(inset, height - inset - h),
                    room, h, current, MinimapInk.Question))
            } else {
                val count = minOf(tick.widths.size, maxOf(1, (span * .80f / (1.3f * density)).toInt()))
                val selected = minimapSampleIndices(tick.widths.size, count)
                val gap = minOf(1.3f * density, span * .80f / count)
                val h = minOf(.60f * density, maxOf(density * .35f, span * .35f)).coerceAtMost(track)
                selected.forEachIndexed { line, source ->
                    val y = center + (line - (count - 1) / 2f) * gap
                    add(MinimapMark(tick.key, tick.kind, left, (y - h / 2).coerceIn(inset, height - inset - h),
                        room * tick.widths[source], h, current, tick.inks.getOrElse(source) { MinimapInk.Text }))
                }
            }
        }
    }
    return bands.values.sortedBy { it.top }
}

internal fun minimapTickAt(map: ConversationMinimapMap, viewport: MinimapViewport?, width: Float,
    height: Float, density: Float, y: Float): MinimapTarget? =
    minimapTickAt(minimapMarks(map, viewport, width, height, density, expanded = true), density, y)

internal fun minimapTickAt(marks: List<MinimapMark>, density: Float, y: Float): MinimapTarget? {
    if (!y.isFinite()) return null
    val nearest = marks.minByOrNull { abs(it.top + it.height / 2 - y) } ?: return null
    return if (abs(nearest.top + nearest.height / 2 - y) <= 6f * density) MinimapTarget(nearest.key, 0f) else null
}
