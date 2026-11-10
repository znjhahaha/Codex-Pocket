package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Access-order, bounded memory only. Eviction never removes durable history or drafts. */
internal class BoundedCache<K, V>(private val capacity: Int, private val weightLimit: Long = Long.MAX_VALUE,
    private val weight: (V) -> Long = { 1L }) {
    private val values = LinkedHashMap<K, V>(16, .75f, true)
    private val weights = mutableMapOf<K, Long>()
    private var total = 0L
    private var pinned: K? = null
    init { require(capacity > 0 && weightLimit > 0) }
    @Synchronized operator fun get(key: K): V? = values[key]
    @Synchronized operator fun set(key: K, value: V) {
        values[key] = value
        total -= weights.put(key, weight(value).coerceAtLeast(0)) ?: 0
        total += weights.getValue(key)
        trim()
    }
    private fun trim() {
        val iterator = values.entries.iterator()
        while (iterator.hasNext() && (values.size > capacity || total > weightLimit)) {
            val key = iterator.next().key
            if (key == pinned) continue
            total -= weights.remove(key) ?: 0
            iterator.remove()
        }
    }
    @Synchronized fun pin(key: K?) { pinned = key; trim() }
    @Synchronized fun remove(key: K): V? {
        total -= weights.remove(key) ?: 0
        return values.remove(key)
    }
    @Synchronized fun clear() { values.clear(); weights.clear(); total = 0; pinned = null }
}

/** Estimate one changed raw payload without serializing it. Unchanged raw trees reuse weights. */
private fun timelineItemWeight(item: TimelineItem): Long {
    var total = item.text.length * 2L + item.key.length * 2L + 128
    val pending = ArrayDeque<JsonElement>()
    pending.addLast(item.raw)
    while (pending.isNotEmpty()) {
        total += 64
        when (val value = pending.removeLast()) {
            is JsonPrimitive -> total += value.content.length * 2L
            is JsonObject -> value.forEach { (key, child) -> total += key.length * 2L; pending.addLast(child) }
            is JsonArray -> value.forEach(pending::addLast)
        }
    }
    return total
}

/** The active window is already retained by PocketState, so never evict its reliable base.
 * Its budget is a soft limit; inactive entries remain bounded. Live updates reuse item weights. */
internal class TimelineCache(budget: Long, private val itemWeight: (TimelineItem) -> Long = ::timelineItemWeight) {
    private data class Entry(val timeline: NativeTimeline, val weights: Map<String, Long>) {
        val weight = timeline.turns.size * 128L + weights.values.sum()
    }
    private val entries = BoundedCache<ThreadKey, Entry>(12, budget) { it.weight }
    @Synchronized operator fun get(key: ThreadKey): NativeTimeline? = entries[key]?.timeline
    @Synchronized operator fun set(key: ThreadKey, timeline: NativeTimeline) = update(key, timeline)
    @Synchronized fun update(key: ThreadKey, timeline: NativeTimeline, changed: List<TimelineItem>? = null) {
        val previous = entries[key]
        fun nextWeight(id: String, item: TimelineItem): Long {
            val old = previous?.timeline?.items?.get(id)
            val retained = previous?.weights?.get(id)
            return if (old != null && retained != null && old.raw === item.raw)
                retained + (item.text.length.toLong() - old.text.length) * 2 else itemWeight(item)
        }
        val weights = if (previous == null || changed == null) timeline.items.mapValues { (id, item) ->
            nextWeight(id, item)
        } else previous.weights.toMutableMap().apply {
            keys.retainAll(timeline.items.keys)
            changed.forEach { put(it.key, nextWeight(it.key, it)) }
            timeline.items.forEach { (id, item) -> if (id !in this) put(id, itemWeight(item)) }
        }
        entries[key] = Entry(timeline, weights)
    }
    @Synchronized fun select(key: ThreadKey?) = entries.pin(key)
    @Synchronized fun clear() = entries.clear()
}

internal data class ConversationWindow(
    val runtimeId: String, val conflict: Boolean, val nextTurns: JsonElement?, val exhausted: Boolean,
    val loadState: ConversationLoadState, val error: String? = null,
) {
    fun reusable(chat: ChatEntity, online: Boolean, verifiedLink: Long?, link: Long, hasTimeline: Boolean): Boolean =
        online && hasTimeline && !chat.conflict && runtimeId == chat.runtimeId && conflict == chat.conflict &&
            verifiedLink == link && loadState == ConversationLoadState.Ready
    companion object {
        fun from(s: PocketState): ConversationWindow? = s.selected?.let {
            ConversationWindow(it.runtimeId, it.conflict, s.nextTurns, s.historyExhausted, s.conversationLoadState, s.conversationLoadError)
        }
    }
}

internal data class ItemPersistenceDiff(val upserts: List<ItemEntity>, val removed: List<String>)
internal fun itemPersistenceDiff(previous: List<ItemEntity>, next: List<ItemEntity>, replace: Boolean): ItemPersistenceDiff {
    val old = previous.associateBy { it.itemKey }
    val keys = next.mapTo(hashSetOf()) { it.itemKey }
    return ItemPersistenceDiff(next.filter { old[it.itemKey] != it },
        if (replace) old.keys.filter { it !in keys } else emptyList())
}
