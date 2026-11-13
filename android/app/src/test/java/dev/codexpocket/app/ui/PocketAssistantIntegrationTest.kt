package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual PocketRoot + repository + in-memory Room. Never connects, launches an emulator, or invokes a model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PocketAssistantIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val runtime=RuntimeInfo("managed","assistant","fixture","store",true,emptySet(),emptySet())
    private val snapshot=AssistantSnapshot(true,"managed","coordinator",false,AssistantSettings("认真协助","简洁",null,"UTC"),ready=true,
        memories=listOf(AssistantMemory("memory","实际快照记忆")))

    private fun field(repo: PocketRepository, name: String): Any? = PocketRepository::class.java.getDeclaredField(name)
        .apply {isAccessible=true}.get(repo)
    private fun assign(repo: PocketRepository,name:String,value:Any?) = PocketRepository::class.java.getDeclaredField(name)
        .apply {isAccessible=true}.set(repo,value)
    @Suppress("UNCHECKED_CAST")
    private fun state(repo:PocketRepository)=field(repo,"_state") as MutableStateFlow<PocketState>

    private fun fixture(enabled:Boolean=false,block:(PocketRepository,PocketDatabase)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(compose.activity.application,PocketDatabase::class.java).build()
        val storage=object:PairingStorage {
            override fun load():PairedComputer?=null
            override fun save(pair:PairedComputer)=Unit
            override fun forget()=Unit
        }
        val repo=PocketRepository(compose.activity.application,{error("Host fixture must never connect")},storage,db,emptyFlow())
        try {
            compose.waitUntil(5_000) {repo.state.value.initialized}
            assign(repo,"assistantComputer","pc")
            assign(repo,"pair",PairedComputer(ConnectionConfig("wss://fixture.invalid","room","fixture-token","00".repeat(32)),"phone","fixture-secret","pc","本地 fixture"))
            state(repo).update {it.copy(initialized=true,paired=true,online=false,computerId="pc",computerName="本地 fixture",glassEffects=false,
                runtimes=listOf(runtime),capabilities=setOf("assistant.v1"),assistant=AssistantUiState(if(enabled) snapshot.copy(ready=true) else AssistantSnapshot(enabled=false)))}
            compose.activity.runOnUiThread {compose.activity.setContent {
                PocketTheme("light") {val current by repo.state.collectAsState();PocketRoot(current,repo)}
            }}
            compose.waitForIdle()
            block(repo,db)
        } finally {
            compose.activity.runOnUiThread {compose.activity.setContent {}}
            compose.waitForIdle()
            runBlocking {(field(repo,"scope") as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()}
            db.close()
        }
    }

    private fun scroll(tag:String):SemanticsNodeInteraction {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun screenshot(name:String) {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val decor=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
        }
        val file=File("build/assistant-fixtures/$name.png");file.parentFile?.mkdirs()
        file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }

    @Test fun realBottomPocketEntryIsSixtyDpAndCloseReturnsToAttentionTab()=fixture {_,_->
        compose.onNodeWithTag("home-tab-attention").performClick().assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(60.dp).assertHasClickAction()
        screenshot("root-attention-before")
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-screen").assertIsDisplayed()
        compose.onNodeWithText("服务已就绪").assertDoesNotExist()
        screenshot("root-assistant-open")
        compose.onNodeWithContentDescription("返回原页面").performClick()
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-attention").assertIsSelected()
        screenshot("root-attention-returned")
    }

    @Test fun actualAndroidBackDispatcherReturnsToManagementTab()=fixture {_,_->
        compose.onNodeWithTag("home-tab-devices").performClick().assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-screen").assertIsDisplayed()
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-devices").assertIsSelected()
    }

    @Test fun offlineEntryKeepsCachedMemoryReadableAndAllSaveControlsDisabled()=fixture(true) {repo,_->
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        // Commit the entry composition so PocketRoot's LaunchedEffect can start its real read.
        compose.waitForIdle()
        compose.waitUntil(5_000) {repo.state.value.assistant.error!=null}
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.openAssistantPage("Memory")
        compose.onNodeWithText("实际快照记忆").assertExists()
        scroll("assistant-memory-add").assertIsNotEnabled()
        scroll("assistant-memory-edit:memory").assertIsNotEnabled()
        scroll("assistant-memory-delete:memory").assertIsNotEnabled()
        compose.openAssistantPage("Schedules")
        scroll("assistant-schedule-add").assertIsNotEnabled()
        compose.openAssistantPage("Settings")
        scroll("assistant-save-settings").assertIsNotEnabled()
        scroll("assistant-global-pause").assertIsNotEnabled()
    }

    @Test fun offlineTransitionDisablesSendEvenBeforeTheNextReadCanFinish()=fixture(true) {repo,db->
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {repo.state.value.assistant.error!=null}
        val readGate=field(repo,"assistantRead") as Mutex
        runBlocking {readGate.lock()}
        try {
            compose.runOnIdle {state(repo).update {it.copy(online=true,assistant=it.assistant.copy(error=null))}}
            compose.onNodeWithTag("assistant-message-input").performTextInput("保留离线草稿")
            compose.onNodeWithTag("assistant-send").assertIsEnabled()
            compose.runOnIdle {state(repo).update {it.copy(online=false)}}
            compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
            compose.onNodeWithTag("assistant-message-input").assertTextEquals("保留离线草稿")
            compose.onNodeWithTag("assistant-message-input").assertIsEnabled().performTextReplacement("离线仍可修改草稿")
            compose.onNodeWithTag("assistant-message-input").assertTextEquals("离线仍可修改草稿")
            compose.onNodeWithTag("assistant-send").assertIsNotEnabled().performTouchInput {click()}
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM commands").use {cursor->cursor.moveToFirst();assertEquals(0,cursor.getInt(0))}
            assertEquals(0L,repo.state.value.assistant.sendAcknowledgement)
        } finally {readGate.unlock()}
    }

    @Test fun childPageBackButtonReturnsToChatBeforeClosingToAttentionTab()=fixture(true) {_,_->
        compose.onNodeWithTag("home-tab-attention").performClick().assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-message-input").performTextInput("往返后继续填写")
        listOf("Memory","Settings").forEach {page->
            compose.openAssistantPage(page)
            compose.onNodeWithContentDescription("返回原页面").assertDoesNotExist()
            compose.returnToAssistantChat()
            compose.onNodeWithTag("assistant-screen").assertIsDisplayed()
            compose.onNodeWithTag("assistant-message-input").assertTextEquals("往返后继续填写")
        }
        compose.onNodeWithContentDescription("返回原页面").performClick()
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-attention").assertIsSelected()
    }

    @Test fun offlineCachedDraftSurvivesClosingAndReopeningTheRealAssistantWithoutDispatch()=fixture(true) {repo,db->
        val transport=RecordingOfflineConnection()
        assign(repo,"client",transport)
        compose.onNodeWithTag("home-tab-attention").performClick().assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {repo.state.value.assistant.error!=null}
        assertFalse(repo.state.value.online)
        assertEquals(snapshot,repo.state.value.assistant.snapshot)

        val draft="离线修改后的草稿\n回到首页后继续写"
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().performTextInput("离线第一稿")
        compose.onNodeWithTag("assistant-message-input").performTextReplacement(draft)
        compose.onNodeWithTag("assistant-message-input").assertTextEquals(draft)
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回原页面").performClick()
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("assistant-message-input").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-attention").assertIsSelected()

        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-screen").assertIsDisplayed()
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().assertTextEquals(draft)
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()

        // Save the latest edit again through Android Back, rather than only retaining the first draft.
        val revised="$draft，再核对一次"
        compose.onNodeWithTag("assistant-message-input").performTextReplacement(revised)
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-attention").assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-message-input").assertIsEnabled().assertTextEquals(revised)
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()

        assertEquals("Editing and reopening an offline draft must not call the transport",emptyList<String>(),transport.calls.toList())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM commands").use {cursor->cursor.moveToFirst();assertEquals(0,cursor.getInt(0))}
        assertTrue(repo.state.value.assistant.pendingActions.isEmpty())
        assertTrue(repo.state.value.assistant.messages.isEmpty())
        assertEquals(0L,repo.state.value.assistant.sendAcknowledgement)
        assertEquals(snapshot,repo.state.value.assistant.snapshot)
    }

    @Test fun androidBackFromChildPagesRetainsDraftThenReturnsToManagementTab()=fixture(true) {_,_->
        compose.onNodeWithTag("home-tab-devices").performClick().assertIsSelected()
        compose.onNodeWithTag("assistant-pocket-button").performClick()
        compose.onNodeWithTag("assistant-message-input").performTextInput("系统返回保留草稿")
        listOf("Memory","Settings").forEach {page->
            compose.openAssistantPage(page)
            compose.onNodeWithContentDescription("返回助理对话").assertIsDisplayed()
            compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
            compose.onNodeWithTag("assistant-screen").assertIsDisplayed()
            compose.onNodeWithTag("assistant-message-input").assertTextEquals("系统返回保留草稿")
            compose.onNodeWithContentDescription("返回原页面").assertIsDisplayed()
        }
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("assistant-screen").assertDoesNotExist()
        compose.onNodeWithTag("home-tab-devices").assertIsSelected()
    }

    @Test fun repositoryRejectsEveryOfflineAssistantMutationWithARetainedTransport()=fixture(true) {repo,db->
        val transport=RecordingOfflineConnection()
        assign(repo,"client",transport)
        val workspace=AssistantWorkspaceChoice("managed","authorized","项目",true)
        val actions=listOf(AssistantActions.enable("managed"),AssistantActions.send("managed","真实 fixture 消息",workspace),
            AssistantActions.pause("managed"),AssistantActions.resume("managed"),AssistantActions.task("managed","task",false),AssistantActions.task("managed","task",true),
            AssistantActions.settings("managed",snapshot.settings!!,emptyList()),AssistantActions.memoryAdd("managed","记忆"),AssistantActions.memoryEdit("managed","memory","更新"),
            AssistantActions.memoryDelete("managed","memory"),AssistantActions.scheduleDelete("managed","schedule"),
            AssistantActions.schedule("managed",workspace,AssistantScheduleDraft("定时事项","Daily","2030-01-02","09:00",0,"60","UTC",false),1_893_456_000_000L),
            AssistantActions.respond("managed","operation","request","回答"),AssistantActions.approve("managed","operation","request",false))
        runBlocking {actions.forEach {repo.assistantAction(it).join()}}
        assertEquals("Offline must be checked before writing a command or calling the retained transport",emptyList<String>(),transport.calls.toList())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM commands").use {cursor->cursor.moveToFirst();assertEquals(0,cursor.getInt(0))}
        assertEquals(0L,repo.state.value.assistant.sendAcknowledgement)
    }

    @Test fun actualManagementUsageTabRendersTheRepositoryRpcPipeline()=fixture {repo,_->
        val calls=CopyOnWriteArrayList<String>()
        val reader=object:PocketConnection {
            override val state=MutableStateFlow(LinkState.ONLINE)
            override val notifications=emptyFlow<JsonObject>()
            override suspend fun connect(config:ConnectionConfig):Unit=error("Host fixture must never connect")
            override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
                assertNull("Management fixture allows reads only",commandId);calls+=method
                return when(method) {
                    "usage/read"->buildJsonObject {
                        assertEquals("cc_switch",params.string("source_id"))
                        put("schema",1);put("query",params.obj("query"));put("result",usageFixtureJson())
                        put("cache",buildJsonObject {put("state","fresh");put("sampled_at",1794268800);put("age_seconds",0);put("ttl_seconds",60);put("stale_reason",JsonNull)})
                    }
                    "devices/list"->JsonArray(emptyList())
                    else->error("Unexpected management write/model call: $method")
                }
            }
            override fun close()=Unit
        }
        assign(repo,"client",reader)
        compose.runOnIdle {state(repo).update {it.copy(online=true,capabilities=setOf("usage/read"))}}
        compose.onNodeWithTag("home-tab-devices").performClick()
        compose.onNodeWithText("用量").performClick()
        compose.waitUntil(5_000) {repo.state.value.usage.visibleSnapshot!=null}
        compose.onNodeWithTag("usage-screen").assertIsDisplayed()
        compose.onNodeWithText("用量统计").assertExists()
        assertTrue(calls.contains("usage/read"))
        assertTrue(calls.all {it in setOf("usage/read","devices/list")})
        assertEquals(24L,repo.state.value.usage.visibleSnapshot!!.response.source.schemaVersion)
        screenshot("root-management-usage")
    }

    private class RecordingOfflineConnection:PocketConnection {
        override val state=MutableStateFlow(LinkState.OFFLINE)
        override val notifications=emptyFlow<JsonObject>()
        val calls=CopyOnWriteArrayList<String>()
        override suspend fun connect(config:ConnectionConfig):Unit=error("Fixture must never connect")
        override suspend fun request(method:String,params:JsonObject,commandId:String?,timeoutMillis:Long):JsonElement {
            calls+=method
            return buildJsonObject {put("status","accepted")}
        }
        override fun close()=Unit
    }
}
