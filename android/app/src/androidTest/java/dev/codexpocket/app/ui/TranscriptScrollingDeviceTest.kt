package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.codexpocket.app.PocketApplication
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Assume.assumeTrue
import android.os.SystemClock

/** Real chat/row gestures with an unpaired, in-memory repository: no transport or model calls. */
@RunWith(AndroidJUnit4::class)
class TranscriptScrollingDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Opt in with scrollRegression=true", InstrumentationRegistry.getArguments().getString("scrollRegression") == "true")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as PocketApplication
            val field = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
            val original = field.get(app) as Lazy<*>
            check(!original.isInitialized()) { "Run alone in a fresh instrumentation process" }
            db = Room.inMemoryDatabaseBuilder(app, PocketDatabase::class.java).build()
            instrumentation.runOnMainSync {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                field.set(app, lazy {
                    PocketRepository(app, connectionFactory = { error("Fixture must never connect") },
                        pairStore = object : PairingStorage {
                            override fun load(): PairedComputer? = null
                            override fun save(pair: PairedComputer) = error("Fixture must never change pairing")
                            override fun forget() = error("Fixture must never forget pairing")
                        }, db = db, networkAvailable = emptyFlow()).also { repo = it }
                })
            }
            try { base.evaluate() } finally {
                val until = SystemClock.elapsedRealtime() + 5000
                while (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
                instrumentation.runOnMainSync {
                    field.set(app, original)
                    check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    ProcessLifecycleOwner.get().lifecycle.addObserver(app)
                }
            }
        }
    } }).around(compose)
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private val key = ThreadKey("fixture", "store", "chat")
    private val command = TimelineItem("last", "command", "commandExecution", "echo\n\n" + "recorded output\n".repeat(30),
        status = "completed", raw = buildJsonObject { put("command", "echo"); put("aggregatedOutput", "recorded output\n".repeat(30)) }, complete = true)
    private var timeline by mutableStateOf(NativeTimeline())
    private var headerHeight by mutableIntStateOf(48)

    private fun content(deferHistory: Boolean = false, savedDraft: DraftEntity? = null, online: Boolean = false) = runBlocking {
        withTimeout(5_000) { repo.state.first { it.initialized } }
        val chat = ChatEntity(key, "runtime", "desktop", "Fixture", "", "", 0, "idle", false)
        db.dao().putChat(chat)
        if (savedDraft != null) db.dao().putDraft(savedDraft)
        repo.select(chat).join()
        val items = (0 until 24).map { TimelineItem("turn-$it", "message", "agentMessage", "Recorded paragraph $it\n".repeat(5), complete = true) } + command
        val loaded = NativeTimeline(items.associateBy { it.key }, completedTurns = items.mapTo(hashSetOf()) { it.turnId })
        timeline = if (deferHistory) NativeTimeline() else loaded
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalPocketGlass provides false,
                    LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                    Surface { ProvideMarkdownRendering {
                        val current by repo.state.collectAsState()
                        ChatScreen(current.copy(timeline = timeline, conversationCached = true, online = online), repo, {}, {}, {},
                            header = { Spacer(Modifier.height(headerHeight.dp)) })
                    } }
                }
            }
        } }
        compose.waitForIdle()
        if (!deferHistory) {
            compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("activity-toggle:${command.key}"))
            compose.waitForIdle()
        }
        loaded
    }

    @After fun close() = runBlocking<Unit> {
        if (::repo.isInitialized) {
            val field = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(repo) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
        }
        if (::db.isInitialized) db.close()
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "pocket-regression-$name.png")
        compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun cachedHomeReturnsWithoutDirectorySpinnerAndKeepsConnectionIndicator() {
        content()
        var mounted by mutableStateOf(true)
        val row = repo.state.value.selected!!
        val online = repo.state.value.copy(computerId = row.key.computerId, computerName = "Fixture PC", selected = null,
            chats = listOf(row), online = true, directoryRefreshing = true, directoryLoading = false)
        var forced = 0
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("dark") { PocketBackdrop {
                if (mounted) PocketHomeFrame(online, HomeTab.Chats, 0, false, false, {}, {}, {}, {}) {
                    ChatsHome(online, {}, {}, { _, _, _ -> forced++ }, {}, {}, onEnsureDirectory = { _, _ -> })
                }
            } }
        } }
        val times = mutableListOf<Long>()
        repeat(8) {
            compose.waitForIdle()
            compose.onNodeWithTag("home-chat-${row.key.encoded}").assertIsDisplayed()
            compose.onNodeWithTag("home-sync-state").assertDoesNotExist()
            compose.onNodeWithTag("home-connectivity", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("home-host").assertContentDescriptionEquals("选择电脑：Fixture PC，${compose.activity.stateLabel("online")}")
            compose.runOnIdle { mounted = false }
            val start = SystemClock.elapsedRealtime()
            compose.runOnIdle { mounted = true }
            compose.waitForIdle()
            times += SystemClock.elapsedRealtime() - start
        }
        assertEquals(0, forced)
        println("POCKET_HOME_FIXTURE remounts=${times.size} ms=$times realNetwork=false")
        screenshot("cached-home")
    }

    @Test fun nativeQuestionHistoryKeepsQuestionAndConfirmedAnswerVisible() {
        content()
        val item=TimelineItem.from("question-turn",buildJsonObject {
            put("id","question-item");put("type","dynamicToolCall");put("tool","functions.request_user_input");put("status","completed");put("success",true)
            put("arguments",buildJsonObject {put("questions",buildJsonArray {add(buildJsonObject {
                put("id","choice");put("header","交付顺序");put("question","先修复问答还是重连？");put("isOther",true);put("isSecret",false)
            })})})
            put("contentItems",buildJsonArray {add(buildJsonObject {put("type","text");put("text","""{"answers":{"choice":{"answers":["先修复问答，然后重连"]}}}""")})})
        })
        val history=NativeQuestionHistory.from(item)!!
        compose.activity.runOnUiThread {compose.activity.setContent {
            PocketTheme("dark") {Surface {Column(Modifier.fillMaxSize().padding(20.dp)) {HistoricalQuestionRow(history,item.key)}}}
        }}
        compose.onNodeWithText("先修复问答还是重连？").assertIsDisplayed()
        compose.onNodeWithText("回答：先修复问答，然后重连").assertIsDisplayed()
        compose.onNodeWithText("操作未核对").assertDoesNotExist()
        screenshot("question-history")
    }

    @Test fun turnSummaryAndLanguageBadgesShowActualRecordedChanges() {
        content()
        val edits = TimelineItem("changes", "files", "fileChange", complete = true, status = "completed", raw = buildJsonObject {
            put("changes", buildJsonArray { repeat(5) { index -> add(buildJsonObject {
                put("path", "src/file$index.kt"); put("kind", "update"); put("diff", "@@ -1 +1,2 @@\n-old\n+new\n+line")
            }) } })
        })
        val summary = turnChangeSummaries(NativeTimeline(mapOf(edits.key to edits), completedTurns = setOf("changes"))).single()
        var opened: TimelineItem? = null
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { Surface { ProvideMarkdownRendering {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    MarkdownText("已完成 [升级协调模块](/D:/codex2/upgrade.rs:12) · [连接设置](/D:/codex2/Main.kt) · [文档](https://example.invalid)", "fixture-links")
                    TurnChangesCard(summary) { opened = it }
                }
            } } }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已编辑 5 个文件").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("src/file3.kt").assertDoesNotExist()
        compose.onNodeWithText("+10").assertExists()
        compose.onNodeWithText("−5").assertExists()
        compose.waitForIdle()
        screenshot("links-and-changes")
        compose.onNodeWithText("再显示 2 个文件").performClick()
        compose.onNodeWithText("src/file4.kt").assertIsDisplayed()
        compose.onNodeWithText("查看变更").performClick()
        assertEquals(5, activityFiles(opened!!).size)
        compose.onNodeWithText("src/file4.kt").performClick()
        assertEquals("src/file4.kt", activityFiles(opened!!).single().path)
    }

    @Test fun imageRecoversAfterConnectionReturnsAndOpensInViewer() {
        content()
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(63, 123, 188))
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val ref = ToolMediaRef("b".repeat(32), "fixture-runtime", "fixture-thread", "fixture-turn", "fixture-item", "", 0,
            ToolMediaStatus.Recoverable, connectionId = "fixture-link", contentPart = 0)
        var connection by mutableStateOf(1L to false)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val source = ToolMediaImageSource { owner, offset, _ ->
            assertEquals(ref, owner); assertEquals(0L, offset); calls.incrementAndGet()
            if (!connection.second) throw dev.codexpocket.wire.WireException("offline", "fixture")
            ToolMediaChunk(ref.mediaId, ToolMediaStatus.Ready, "image/png", bytes.size.toLong(), 0, true,
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("dark") { Surface { CompositionLocalProvider(LocalImageConnection provides connection, LocalToolMediaImages provides source) {
                Column(Modifier.fillMaxSize().padding(24.dp)) { ToolMediaGrid(listOf(ref)) }
            } } }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("连接电脑后可读取图片").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { connection = 2L to true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { calls.get() >= 2 && compose.onAllNodesWithText("连接电脑后可读取图片").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("图片 1").performClick()
        compose.waitUntil(5_000) { calls.get() >= 3 }
        compose.onNodeWithContentDescription("关闭", useUnmergedTree = true).assertExists()
        screenshot("image-recovered")
    }

    @Test fun expiredReferenceRefreshesSameImageAndOpensNewReference() {
        content()
        val bitmap=android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(63,123,188))
        val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        bitmap.recycle()
        val old=ToolMediaRef("c".repeat(32),"renew-runtime","renew-thread","renew-turn","renew-item","",0,
            ToolMediaStatus.Recoverable,connectionId="renew-link",contentPart=0)
        val fresh=old.copy(mediaId="d".repeat(32))
        var reference by mutableStateOf(old)
        val renewals=java.util.concurrent.atomic.AtomicInteger()
        val reads=java.util.concurrent.CopyOnWriteArrayList<String>()
        val source=object:ToolMediaImageSource {
            override suspend fun read(media:ToolMediaRef,offset:Long,maxBytes:Int):ToolMediaChunk {
                reads += media.mediaId
                if(media==old) throw dev.codexpocket.wire.WireException("media_expired","fixture")
                assertEquals(fresh,media);assertEquals(0L,offset)
                return ToolMediaChunk(fresh.mediaId,ToolMediaStatus.Ready,"image/png",bytes.size.toLong(),0,true,
                    android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
            }
            override suspend fun refresh(media:ToolMediaRef,manual:Boolean) {
                assertEquals(old,media);assertFalse(manual);renewals.incrementAndGet()
                withContext(Dispatchers.Main) {reference=fresh}
            }
        }
        compose.activity.runOnUiThread {compose.activity.setContent {
            PocketTheme("dark") {Surface {CompositionLocalProvider(LocalImageConnection provides (20L to true),LocalToolMediaImages provides source) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {ToolMediaGrid(listOf(reference))}
            }}}
        }}
        compose.waitUntil(5_000) {fresh.mediaId in reads}
        compose.waitForIdle()
        assertEquals(1,renewals.get())
        compose.onNodeWithText("图片引用已失效，点击重试").assertDoesNotExist()
        compose.onNodeWithContentDescription("图片 1").performClick()
        compose.waitUntil(5_000) {reads.count {it==fresh.mediaId}>=2}
        compose.onNodeWithContentDescription("关闭",useUnmergedTree=true).assertExists()
        screenshot("image-reference-renewed")
    }

    @Test fun mainChatThinkingRequiresActiveNativeWorkAndStopsWhenWaiting() {
        content(online = true)
        compose.runOnIdle { timeline = NativeTimeline(activeTurn = "accepted-turn", status = "active") }
        compose.onNodeWithTag("live-thinking-placeholder").assertTextEquals("正在思考")
        compose.onNodeWithText(compose.activity.getString(dev.codexpocket.app.R.string.what_working)).assertDoesNotExist()
        compose.runOnIdle { timeline = timeline.copy(activeFlags = setOf("waitingOnUserInput")) }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
        compose.runOnIdle { timeline = timeline.copy(activeFlags = emptySet(), status = "idle") }
        compose.onNodeWithTag("live-thinking-placeholder").assertDoesNotExist()
    }

    @Test fun activeAgentsLeadAndFailureRemainsFoldableWithInitialThinkingFeedback() {
        content()
        val failed=TimelineItem("fixture-turn","bad-command","commandExecution","fixture\n\nrecorded failure detail",status="failed",complete=true,
            raw=buildJsonObject {put("command","fixture");put("exitCode",1);put("aggregatedOutput","recorded failure detail")})
        val completed=failed.copy(itemId="good-command",status="completed",raw=buildJsonObject {put("command","fixture-good");put("exitCode",0)})
        val row=TranscriptRow("fixture-failed-group",TranscriptKind.Activity,listOf(failed,completed),completedTurn=true)
        val agents=listOf(AgentRosterEntry("fixture-unknown","旧记录",isCurrent=true),
            AgentRosterEntry("fixture-closed","已关闭代理",ActivityStatus.Interrupted,"shutdown",isCurrent=true),
            AgentRosterEntry("fixture-running","运行中的代理",ActivityStatus.Running,"running",isCurrent=true))
        var others by mutableStateOf(false)
        compose.activity.runOnUiThread {compose.activity.setContent {
            PocketTheme("dark") {Surface {Column(Modifier.fillMaxSize().padding(20.dp)) {
                LiveTurnStatus("fixture-turn",ConversationPresentation.LiveStatus.Thinking)
                NativeActivityTrace(row,"fixture-failed",false,{},{})
                AgentRosterList(agents,emptyMap(),true,false,androidx.compose.foundation.lazy.rememberLazyListState(),
                    others,{others=!others},{},Modifier.weight(1f))
            }}}
        }}
        compose.onNodeWithText("正在思考").assertIsDisplayed()
        compose.onNodeWithTag("activity-group-toggle:${row.key}").assertExists()
        compose.onNodeWithTag("activity-toggle:${failed.key}").assertDoesNotExist()
        compose.onNodeWithTag("agent-roster-open:fixture-running").assertIsDisplayed()
        compose.onNodeWithTag("agent-roster-open:fixture-unknown").assertDoesNotExist()
        compose.onNodeWithTag("agent-roster-open:fixture-closed").assertDoesNotExist()
        screenshot("active-agents-thinking-folded-failure")
        compose.onNodeWithTag("activity-group-toggle:${row.key}").performClick()
        compose.onNodeWithText("退出码 1").assertIsDisplayed()
        compose.onNodeWithTag("activity-toggle:${failed.key}").performClick()
        compose.onNodeWithText("recorded failure detail").assertIsDisplayed()
        compose.onNodeWithTag("activity-group-toggle:${row.key}").performClick()
        compose.onNodeWithText("recorded failure detail").assertDoesNotExist()
        compose.onNodeWithTag("agent-reader-history-toggle").performClick()
        compose.onNodeWithTag("agent-roster-open:fixture-unknown").assertExists()
        compose.onNodeWithTag("agent-roster-open:fixture-closed").assertExists()
    }

    @Test fun firstHistoryProjectionWaitsForContactBeforeRestoringAndPersistingAnchor() {
        val anchor = TimelineItem("turn-12", "message", "agentMessage").key
        val saved = DraftEntity(key, text = "Keep this draft", scrollIndex = 13, scrollOffset = 31,
            followEnd = false, scrollItemKey = anchor)
        val loaded = content(deferHistory = true, savedDraft = saved)
        val transcript = compose.onNodeWithTag("chat-transcript")
        transcript.performTouchInput { down(center) }
        compose.runOnIdle { timeline = loaded }
        compose.waitUntil(5_000) {
            transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 20f
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("A blocked restoration must not persist the temporary first layout", saved.scrollItemKey, repo.state.value.draft?.scrollItemKey)
        assertEquals(saved.scrollOffset, repo.state.value.draft?.scrollOffset)
        transcript.performTouchInput { up() }
        compose.waitUntil(5_000) { compose.onNodeWithTag("transcript-row:$anchor").isDisplayed() }
        compose.waitUntil(5_000) {
            runBlocking { db.dao().draft(key)?.let { it.scrollItemKey == anchor && it.scrollOffset == 31 } == true }
        }
        assertEquals("Keep this draft", repo.state.value.draft?.text)
        assertFalse(repo.state.value.draft!!.followEnd)
    }

    @Test fun expandedLastCommandGestureWinsOverStreamAndBottomLayout() {
        content()
        compose.onNodeWithTag("activity-toggle:${command.key}").assertIsDisplayed().performClick()
        compose.waitForIdle()
        val transcript = compose.onNodeWithTag("chat-transcript")
        val bottom = scrollPosition()
        transcript.performTouchInput { down(center); moveBy(Offset(0f, 80f)) }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == false }
        compose.runOnIdle {
            headerHeight += 48
            timeline = timeline.copy(items = timeline.items + (command.key to command.copy(text = command.text + "\nNew recorded output")))
        }
        transcript.performTouchInput { moveBy(Offset(0f, 150f)); up() }
        compose.waitForIdle()
        assertFalse(repo.state.value.draft!!.followEnd)
        assertTrue("The reader must remain above the tail", scrollPosition() < bottom)
        val reading = scrollPosition()
        compose.runOnIdle { headerHeight -= 48 }
        compose.waitForIdle()
        assertFalse(repo.state.value.draft!!.followEnd)
        assertTrue(scrollPosition() <= reading + 1f)
        transcript.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue("A second swipe must continue moving", scrollPosition() < reading)
    }

    @Test fun mergedToolAnchorWaitsForContactAndNeverPersistsFallbackLayout() {
        val first = TimelineItem("split-turn", "first", "commandExecution", "first command", liveStarted = true,
            raw = buildJsonObject { put("command", "first command"); put("aggregatedOutput", "Recorded first output\n".repeat(30)) })
        val second = TimelineItem("split-turn", "second", "commandExecution", "second command", liveStarted = true,
            raw = buildJsonObject { put("command", "second command"); put("aggregatedOutput", "Recorded second output\n".repeat(30)) })
        val separator = TimelineItem("split-turn", "commentary", "agentMessage", "Recorded commentary",
            raw = buildJsonObject { put("phase", "commentary") })
        val oldAnchor = "activity:${second.key}"
        val saved = DraftEntity(key, scrollIndex = 15, scrollOffset = 17, followEnd = false, scrollItemKey = oldAnchor,
            expansions = JsonArray(listOf("activity-item:${first.key}", "activity-item:${second.key}").map(::JsonPrimitive)).toString())
        val loaded = content(deferHistory = true, savedDraft = saved)
        val items = loaded.items.values.toList()
        val restored = CompletableDeferred<Unit>()
        val positions = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, Int>>()
        val originalDao = db.dao()
        val observingDao = object : PocketDao by originalDao {
            override suspend fun putDraft(draft: DraftEntity) {
                originalDao.putDraft(draft)
                positions += draft.scrollItemKey to draft.scrollOffset
                if (draft.scrollItemKey == oldAnchor) restored.complete(Unit)
            }
        }
        PocketRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }.set(repo, observingDao)
        val split = loaded.copy(items = (items.take(12) + listOf(first, separator, second) + items.drop(12)).associateBy { it.key })
        assertTrue(ConversationPresentation.rows(split).any { it.key == oldAnchor })
        compose.runOnIdle { timeline = split }
        val transcript = compose.onNodeWithTag("chat-transcript")
        compose.waitUntil(5_000) { transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 20f }
        transcript.performScrollToIndex(15)
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { restored.isCompleted }
        // Short tool rows can lie in the content-padding region under the overlaid header.
        // Verify their measured key and the committed pixel anchor, not chrome occlusion.
        compose.onNodeWithTag("transcript-row:$oldAnchor").assertExists()
        compose.waitForIdle()
        assertEquals("The initial anchor must actually be restored before the merge", oldAnchor, repo.state.value.draft?.scrollItemKey)
        transcript.performTouchInput { down(center) }
        val mergedAnchor = "activity:${first.key}"
        val merged = split.copy(items = split.items - separator.key)
        assertFalse("The fixture must actually remove the old row key", ConversationPresentation.rows(merged).any { it.key == oldAnchor })
        compose.runOnIdle { timeline = merged }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("transcript-row:$oldAnchor").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Do not replace a missing native anchor while restoration is blocked", oldAnchor, repo.state.value.draft?.scrollItemKey)
        transcript.performTouchInput { up() }
        // Restoration resumes on the Compose main dispatcher after release. Pump
        // that work before polling Room, whose executor is not the Compose clock.
        compose.waitForIdle()
        try {
            compose.waitUntil(5_000) {
                runBlocking { db.dao().draft(key)?.let { it.scrollItemKey == mergedAnchor && it.scrollOffset == 0 } == true }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Merged anchor did not commit: ${repo.state.value.draft}; writes=$positions; " +
                transcript.printToString().take(1600), failure)
        }
        compose.onNodeWithTag("transcript-row:$mergedAnchor").assertExists()
        assertFalse(repo.state.value.draft!!.followEnd)
    }

    @Test fun idleFollowerTracksNewRowsButBoundaryNeverReenablesReaderIntent() {
        content()
        val next = TimelineItem("new", "message", "agentMessage", "New recorded response\n".repeat(8), complete = true)
        compose.runOnIdle {
            timeline = timeline.copy(items = timeline.items + (next.key to next))
        }
        compose.waitForIdle()
        // Markdown is a native TextView, not a Compose Text semantics node. Check
        // the new keyed row's viewport bounds after the background projection.
        compose.waitUntil(5_000) { compose.onNodeWithTag("transcript-row:${next.key}").isDisplayed() }
        compose.onNodeWithTag("transcript-row:${next.key}").assertIsDisplayed()
        val transcript = compose.onNodeWithTag("chat-transcript")
        // A split Espresso gesture waits for platform overscroll to become idle
        // while still holding the pointer. Deliver the full boundary gesture;
        // held-contact restoration is covered separately above.
        transcript.performTouchInput { down(center); moveBy(Offset(0f, -90f)); up() }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == false }
        compose.runOnIdle { headerHeight += 24 }
        compose.waitForIdle()
        assertFalse("A transient no-scroll boundary is not a request to follow", repo.state.value.draft!!.followEnd)
    }

    @Test fun realDragToTailResumesButMinimapSeekDoesNot() {
        content()
        val transcript = compose.onNodeWithTag("chat-transcript")
        transcript.performTouchInput { swipeDown() }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == false }
        transcript.performScrollToIndex(16)
        compose.waitForIdle()
        assertFalse("Programmatic positioning must not restore follow", repo.state.value.draft!!.followEnd)
        transcript.performTouchInput { swipeUp() }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == true }
        val rail = compose.onNodeWithTag("conversation-minimap")
        rail.performTouchInput { click(center) }
        compose.waitForIdle()
        rail.performTouchInput { click(Offset(centerX, height * .3f)) }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == false }
        compose.runOnIdle { headerHeight += 24 }
        compose.waitForIdle()
        assertFalse(repo.state.value.draft!!.followEnd)
        compose.onNodeWithContentDescription(compose.activity.getString(dev.codexpocket.app.R.string.jump_latest)).performClick()
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == true }
    }
}
