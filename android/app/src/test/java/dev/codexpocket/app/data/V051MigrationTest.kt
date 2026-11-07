package dev.codexpocket.app.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Upgrade a genuine exported v2 database through PocketDatabase.create's registered migrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V051MigrationTest {
    private lateinit var application: Application
    private lateinit var database: PocketDatabase
    private lateinit var v2: JsonObject
    private lateinit var before: Map<String, List<List<String?>>>
    private val primary = ThreadKey("迁移电脑", "store-with:separator", "原生会话/一")
    private val other = primary.copy(nativeId = "普通分支/二")

    @Before fun createVersionTwoDatabase() {
        application = ApplicationProvider.getApplicationContext()
        v2 = pocketJson.parseToJsonElement(schemaFile().readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, v2["version"]!!.jsonPrimitive.int)
        // This is Robolectric's isolated app directory, never the installed phone database.
        application.deleteDatabase("pocket.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(application).name("pocket.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        entities().forEach { entity ->
                            val table = entity["tableName"]!!.jsonPrimitive.content
                            db.execSQL(expandTable(entity["createSql"]!!.jsonPrimitive.content, table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(expandTable(index.jsonObject["createSql"]!!.jsonPrimitive.content, table))
                            }
                        }
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)",
                            arrayOf(v2["identityHash"]!!.jsonPrimitive.content))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("The v2 seed helper must never perform an upgrade")
                    }
                }).build()
        )
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                seed(db)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            before = legacyRows(db)
            assertEquals(2, db.version)
        } finally { helper.close() }
        database = PocketDatabase.create(application)
        // Force Room's real open/migration/schema-validation path; no test-only migration SQL.
        assertEquals(3, database.openHelper.writableDatabase.version)
    }

    @After fun close() {
        if (::database.isInitialized) database.close()
        if (::application.isInitialized) application.deleteDatabase("pocket.db")
    }

    @Test fun registeredTwoToThreeMigrationPreservesAllVersionTwoKeysAndValues() {
        val db = database.openHelper.writableDatabase
        assertEquals(before, legacyRows(db))
        val columns = columns(db, "chats")
        assertEquals(Column("INTEGER", 1, "0", 0), columns["isSubagent"])
        assertEquals(Column("TEXT", 0, null, 0), columns["parentThreadId"])
        assertEquals(Column("TEXT", 0, null, 0), columns["relationSource"])
        db.query("SELECT isSubagent,parentThreadId,relationSource FROM chats").use {
            assertEquals(2, it.count)
            while (it.moveToNext()) {
                assertEquals(0, it.getInt(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
        }
        assertEquals(mapOf(
            "chatKey" to Column("TEXT", 1, null, 1),
            "snapshot" to Column("TEXT", 1, null, 0)
        ), columns(db, "context_usage"))
        db.query("SELECT count(*) FROM context_usage").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("PRAGMA index_list(items)").use {
            val name = it.getColumnIndexOrThrow("name")
            val names = mutableListOf<String>()
            while (it.moveToNext()) names += it.getString(name)
            assertTrue("Existing history index must survive", "index_items_chatKey" in names)
        }
    }

    @Test fun migratedMetadataAndContextRoundTripLeaveDraftHistoryAndNativeIdentityUntouched() = runBlocking {
        val dao = database.dao()
        val draft = dao.draft(primary)!!
        val snapshot = """{"last":{"inputTokens":120,"outputTokens":15,"totalTokens":135},"window":272000}"""
        dao.putContextUsage(ContextEntity(primary, snapshot))
        dao.markSubagent(other, primary.nativeId, "native-collaboration")
        assertEquals(ContextEntity(primary, snapshot), dao.contextUsage(primary))
        assertEquals(draft, dao.draft(primary))
        assertEquals(other, dao.chat(other)!!.key)
        assertTrue(dao.chat(other)!!.isSubagent)
        assertEquals(primary.nativeId, dao.chat(other)!!.parentThreadId)
        assertEquals("native-collaboration", dao.chat(other)!!.relationSource)
        assertFalse(dao.chat(primary)!!.isSubagent)
        // Compare only the original v2 fields, so metadata changes cannot hide legacy data loss.
        assertEquals(before, legacyRows(database.openHelper.writableDatabase))
        dao.putContextUsage(ContextEntity(primary, """{"last":{"totalTokens":140},"window":272000}"""))
        assertEquals(140, pocketJson.parseToJsonElement(dao.contextUsage(primary)!!.snapshot)
            .jsonObject["last"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.int)
        assertEquals(before, legacyRows(database.openHelper.writableDatabase))
    }

    @Test fun contextUsageRejectsDuplicateKeysAndNullRequiredFields() {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO context_usage(chatKey,snapshot) VALUES(?,?)", arrayOf(primary.encoded, "{}"))
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO context_usage(chatKey,snapshot) VALUES(?,?)", arrayOf(primary.encoded, "{}"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO context_usage(chatKey,snapshot) VALUES(NULL,'{}')")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO context_usage(chatKey,snapshot) VALUES(?,NULL)", arrayOf(other.encoded))
        }
        assertEquals(before, legacyRows(db))
    }

    private fun seed(db: SupportSQLiteDatabase) {
        listOf(primary, other).forEachIndexed { index, key ->
            db.execSQL(
                "INSERT INTO chats VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(key.encoded, "runtime-" + index, "desktop", "迁移标题 " + index,
                    "保留预览😀", "D:/中文项目", 1794040000000L + index,
                    "idle", index, index, 1 - index)
            )
            db.execSQL(
                "INSERT INTO drafts(chatKey,text,attachments,scrollIndex,scrollOffset,expansions,followEnd,scrollItemKey,\"references\") VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(key.encoded, "中文草稿\n第二行😀 " + index,
                    """[{"name":"资料.txt","uri":"content://fixture/attachment"}]""",
                    13 + index, 27 + index, """["task:one","item:two"]""", index,
                    if (index == 0) "turn/item" else null,
                    if (index == 0) """[{"type":"skill","path":"skills/审阅/SKILL.md"}]""" else "[]")
            )
            db.execSQL("INSERT INTO items VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf(key.encoded, "turn:item", "turn", "item", "agentMessage",
                    "原始正文\n😀 " + index, "completed", """{"type":"agentMessage","phase":"final_answer"}""", 1, 21L + index))
            db.execSQL("INSERT INTO snapshots VALUES(?,?,?)", arrayOf(key.encoded, "epoch", 52L + index))
            db.execSQL("INSERT INTO commands VALUES(?,?,?,?,?,?,?)",
                arrayOf("command-" + index, key.computerId, if (index == 0) key.encoded else null,
                    "turn/start", "unknown", 1794040000100L + index, "未知结果不得重发"))
        }
        db.execSQL("INSERT INTO cursors VALUES(?,?,?,?)",
            arrayOf(primary.computerId, "runtime-0", "epoch", 55L))
    }

    private fun entities(): List<JsonObject> = v2["entities"]!!.jsonArray.map { it.jsonObject }

    private fun legacyRows(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        entities().associate { entity ->
            val table = entity["tableName"]!!.jsonPrimitive.content
            val fields = entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            val projection = fields.joinToString(",") { quote(it) }
            val rows = mutableListOf<List<String?>>()
            db.query("SELECT " + projection + " FROM " + quote(table) + " ORDER BY " + projection).use { cursor ->
                while (cursor.moveToNext()) rows += fields.indices.map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                }
            }
            table to rows
        }

    private data class Column(val type: String, val notNull: Int, val default: String?, val primaryKey: Int)

    private fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Column> {
        val result = linkedMapOf<String, Column>()
        db.query("PRAGMA table_info(" + quote(table) + ")").use {
            while (it.moveToNext()) result[it.getString(1)] =
                Column(it.getString(2), it.getInt(3), if (it.isNull(4)) null else it.getString(4), it.getInt(5))
        }
        return result
    }

    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    private fun expandTable(sql: String, table: String) = sql.replace("$" + "{TABLE_NAME}", table)

    private fun schemaFile(): File {
        val relative = "schemas/dev.codexpocket.app.data.PocketDatabase/2.json"
        val candidates = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { root -> sequenceOf(File(root, relative), File(root, "android/app/" + relative)) }
        return candidates.firstOrNull { it.isFile }
            ?: error("Exported v2 Room schema not found from unit-test working directory")
    }
}
