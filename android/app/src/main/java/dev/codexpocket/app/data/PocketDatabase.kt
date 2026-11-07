package dev.codexpocket.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

class RoomConverters {
    @TypeConverter fun threadKeyString(key: ThreadKey?): String? = key?.encoded
    @TypeConverter fun threadKey(value: String?): ThreadKey? = value?.let(ThreadKey::decode)
    @TypeConverter fun jsonString(value: JsonElement): String = value.toString()
    @TypeConverter fun json(value: String): JsonElement = pocketJson.parseToJsonElement(value)
}

@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val key: ThreadKey, val runtimeId: String, val source: String, val title: String, val preview: String, val cwd: String, val updatedAt: Long, val status: String, val conflict: Boolean, val archived: Boolean = false, val pinned: Boolean = false)

@Entity(tableName = "items", primaryKeys = ["chatKey", "itemKey"], indices = [Index("chatKey")])
data class ItemEntity(val chatKey: ThreadKey, val itemKey: String, val turnId: String, val itemId: String, val type: String, val text: String, val status: String, val raw: JsonElement, val complete: Boolean, val ordinal: Long) {
    fun item() = TimelineItem(turnId, itemId, type, text, status, raw.asObject(), complete)
    companion object { fun from(chat: ThreadKey, i: TimelineItem, order: Long) = ItemEntity(chat, i.key, i.turnId, i.itemId, i.type, i.text, i.status, i.raw, i.complete, order) }
}

@Entity(tableName = "drafts")
data class DraftEntity(@PrimaryKey val chatKey: ThreadKey, val text: String = "", val attachments: String = "[]", val scrollIndex: Int = 0, val scrollOffset: Int = 0, val expansions: String = "[]", val followEnd: Boolean = true,
    val scrollItemKey: String? = null, @ColumnInfo(defaultValue = "'[]'") val references: String = "[]")

@Entity(tableName = "cursors", primaryKeys = ["computerId", "runtimeId"])
data class CursorEntity(val computerId: String, val runtimeId: String, val epoch: String, val sequence: Long)

@Entity(tableName = "snapshots")
data class SnapshotEntity(@PrimaryKey val chatKey: ThreadKey, val epoch: String, val sequence: Long)

@Entity(tableName = "commands")
data class CommandEntity(@PrimaryKey val id: String, val computerId: String, val chatKey: ThreadKey?, val method: String, val status: String, val createdAt: Long, val draftText: String = "")

@Dao
interface PocketDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC") fun chats(): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chats") suspend fun allChats(): List<ChatEntity>
    @Query("SELECT * FROM chats WHERE `key` = :key") suspend fun chat(key: ThreadKey): ChatEntity?
    @Upsert suspend fun putChat(chat: ChatEntity)
    @Upsert suspend fun putChats(chats: List<ChatEntity>)
    @Query("SELECT * FROM items WHERE chatKey = :key ORDER BY ordinal ASC") suspend fun items(key: ThreadKey): List<ItemEntity>
    @Upsert suspend fun putItems(items: List<ItemEntity>)
    @Query("DELETE FROM items WHERE chatKey = :key") suspend fun clearItems(key: ThreadKey)
    @Query("SELECT * FROM drafts WHERE chatKey = :key") suspend fun draft(key: ThreadKey): DraftEntity?
    @Upsert suspend fun putDraft(draft: DraftEntity)
    @Query("SELECT * FROM cursors WHERE computerId = :computer") suspend fun cursors(computer: String): List<CursorEntity>
    @Upsert suspend fun putCursor(cursor: CursorEntity)
    @Query("DELETE FROM cursors") suspend fun clearCursors()
    @Query("SELECT * FROM snapshots WHERE chatKey = :key") suspend fun snapshot(key: ThreadKey): SnapshotEntity?
    @Upsert suspend fun putSnapshot(snapshot: SnapshotEntity)
    @Query("DELETE FROM snapshots") suspend fun clearSnapshots()
    @Upsert suspend fun putCommand(command: CommandEntity)
    @Query("UPDATE commands SET status = :status WHERE id = :id AND status IN ('submitted','unknown','not_found')") suspend fun commandStatus(id: String, status: String)
    @Query("SELECT * FROM commands WHERE status IN ('submitted','unknown','not_found') ORDER BY createdAt DESC") fun uncertainCommands(): Flow<List<CommandEntity>>
    @Query("SELECT * FROM commands WHERE computerId = :computer AND status IN ('submitted','unknown','not_found')") suspend fun uncertain(computer: String): List<CommandEntity>
    @Query("UPDATE commands SET status = 'unknown' WHERE status = 'submitted'") suspend fun markInterruptedUnknown()
    @Query("DELETE FROM items") suspend fun clearHistory()
    @Query("DELETE FROM chats") suspend fun clearChats()
    @Query("DELETE FROM drafts") suspend fun clearDrafts()
}

@Database(entities = [ChatEntity::class, ItemEntity::class, DraftEntity::class, CursorEntity::class, SnapshotEntity::class, CommandEntity::class], version = 2, exportSchema = true)
@TypeConverters(RoomConverters::class)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun dao(): PocketDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN scrollItemKey TEXT")
                db.execSQL("ALTER TABLE drafts ADD COLUMN `references` TEXT NOT NULL DEFAULT '[]'")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, PocketDatabase::class.java, "pocket.db").addMigrations(MIGRATION_1_2).build()
    }
}
