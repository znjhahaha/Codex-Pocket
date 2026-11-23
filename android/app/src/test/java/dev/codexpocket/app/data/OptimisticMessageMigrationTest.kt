package dev.codexpocket.app.data

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OptimisticMessageMigrationTest {
    @Test fun exportedVersionThreeUpgradesWithoutLosingDraftOrNativeKeysAndPersistsDisplayAssociation() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val relative = "schemas/dev.codexpocket.app.data.PocketDatabase/3.json"
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { root -> sequenceOf(File(root, relative), File(root, "android/app/$relative")) }
            .first { it.isFile }
        val schema = pocketJson.parseToJsonElement(source.readText()).jsonObject.obj("database")
        val key = ThreadKey("computer", "store", "native-thread")
        val raw = buildJsonObject {
            put("id", "native-item"); put("type", "userMessage")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "旧消息") }) })
        }
        val native = TimelineItem.from("native-turn", raw)
        app.deleteDatabase("pocket.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name("pocket.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.array("entities").map(JsonElement::asObject).forEach { entity ->
                            val table = entity.string("tableName")
                            fun expand(sql: String) = sql.replace("$" + "{TABLE_NAME}", table)
                            db.execSQL(expand(entity.string("createSql")))
                            entity.array("indices").forEach { db.execSQL(expand(it.asObject().string("createSql"))) }
                        }
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)", arrayOf(schema.string("identityHash")))
                        db.execSQL("INSERT INTO drafts(chatKey,text,attachments,scrollIndex,scrollOffset,expansions,followEnd,scrollItemKey,`references`) VALUES(?,?,?,3,7,?,1,?,?)",
                            arrayOf(key.encoded, "保留草稿", "[]", "[]", native.key, "[]"))
                        db.execSQL("INSERT INTO items(chatKey,itemKey,turnId,itemId,type,text,status,raw,complete,ordinal) VALUES(?,?,?,?,?,?,?,?,1,0)",
                            arrayOf(key.encoded, native.key, native.turnId, native.itemId, native.type, native.text, native.status, raw.toString()))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("seed only")
                }).build())
        helper.writableDatabase
        helper.close()
        var database: PocketDatabase? = null
        try {
            val opened = PocketDatabase.create(app).also { database = it }
            assertEquals(4, opened.openHelper.writableDatabase.version)
            assertEquals(DraftEntity(key, "保留草稿", scrollIndex = 3, scrollOffset = 7, scrollItemKey = native.key), opened.dao().draft(key))
            assertEquals(native.key, opened.dao().items(key).single().itemKey)
            assertEquals(raw, opened.dao().items(key).single().item().raw)
            assertTrue(opened.dao().outgoingMessages().isEmpty())
            val message = OutgoingUserMessage("client", key, "command", 1, "旧消息", "[]", "[]", 0,
                phase = "accepted", nativeKey = native.key)
            opened.dao().putOutgoingMessage(message)
            opened.close(); database = null
            val reopened = PocketDatabase.create(app).also { database = it }
            val restored = reopened.dao().outgoingMessages().single()
            val cached = reopened.dao().items(key).single().item()
            val row = ConversationPresentation.rows(NativeTimeline(items = linkedMapOf(cached.key to cached), outgoingMessages = listOf(restored))).single()
            assertEquals(message, restored)
            assertEquals("pocket-user:client", row.key)
            assertEquals(native.key, row.items.single().key)
            assertEquals("保留草稿", reopened.dao().draft(key)?.text)
        } finally { database?.close(); app.deleteDatabase("pocket.db") }
    }
}
