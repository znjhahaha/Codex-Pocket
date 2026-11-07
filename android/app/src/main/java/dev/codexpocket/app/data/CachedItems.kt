package dev.codexpocket.app.data

import android.database.sqlite.SQLiteBlobTooBigException

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
