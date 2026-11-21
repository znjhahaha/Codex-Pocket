package dev.codexpocket.app.data

import android.database.sqlite.SQLiteBlobTooBigException

/** Caller holds the event lock and a Room transaction. The previous timeline is already retained
 * by the reader/cache; never load a second copy of every text/raw payload just to save a change. */
internal suspend fun PocketDao.persistTimelineItems(
    key: ThreadKey, timeline: NativeTimeline, previous: NativeTimeline?, replace: Boolean,
) {
    val positions = itemPositions(key).associate { it.itemKey to it.ordinal }
    val pending = ArrayList<ItemEntity>(32)
    for ((index, entry) in timeline.items.entries.withIndex()) {
        val (id, item) = entry
        val ordinal = index.toLong()
        val old = previous?.items?.get(id)
        val turn = timeline.turns[item.turnId]
        val oldTurn = previous?.turns?.get(item.turnId)
        var row: ItemEntity? = null
        val same = id in positions && old != null &&
            ((old == item && oldTurn == turn) || run {
                row = ItemEntity.from(key, item.withTurnCache(turn), ordinal)
                row == ItemEntity.from(key, old.withTurnCache(oldTurn), ordinal)
            })
        if (same) {
            // Prepending an older page changes positions, not the retained payloads.
            if (positions[id] != ordinal) moveItem(key, id, ordinal)
        } else {
            pending.add(row ?: ItemEntity.from(key, item.withTurnCache(turn), ordinal))
            if (pending.size == 32) { putItems(pending); pending.clear() }
        }
    }
    if (pending.isNotEmpty()) putItems(pending)
    if (replace) positions.keys.filter { it !in timeline.items }.chunked(400).forEach { removeItems(key, it) }
}

/** CursorWindow limits individual result rows, even when the stored SQLite TEXT is valid. */
suspend fun PocketDao.cachedItems(key: ThreadKey): List<ItemEntity> = try { items(key) }
catch (_: SQLiteBlobTooBigException) { chunkedItems(key) }

/** Caller uses a Room read transaction so a concurrent snapshot cannot splice content versions. */
suspend fun PocketDao.chunkedItems(key: ThreadKey): List<ItemEntity> = itemHeaders(key).map { header ->
    suspend fun read(length: Int, raw: Boolean): String {
        require(length in 0..16 * 1024 * 1024) { "Cached item exceeds bounded native frame size" }
        return buildString {
            // SQLite substr/length count Unicode characters, not UTF-16 code units.
            for (start in 1..length step 65_536) {
                val chunk = if (raw) rawChunk(key, header.itemKey, start, 65_536) else textChunk(key, header.itemKey, start, 65_536)
                checkNotNull(chunk) { "Cached snapshot changed" }
                append(chunk)
            }
        }
    }
    ItemEntity(header.chatKey, header.itemKey, header.turnId, header.itemId, header.type,
        read(header.textLength, false), header.status, pocketJson.parseToJsonElement(read(header.rawLength, true)), header.complete, header.ordinal)
}
