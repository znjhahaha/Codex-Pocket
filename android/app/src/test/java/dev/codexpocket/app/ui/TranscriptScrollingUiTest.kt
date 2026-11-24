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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real chat/row gestures with an unpaired, in-memory repository: no transport or model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class TranscriptScrollingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repo: PocketRepository
    private lateinit var db: PocketDatabase
    private val key = ThreadKey("fixture", "store", "chat")
    private val command = TimelineItem("last", "command", "commandExecution", "echo\n\n" + "recorded output\n".repeat(30),
        status = "completed", raw = buildJsonObject { put("command", "echo"); put("aggregatedOutput", "recorded output\n".repeat(30)) }, complete = true)
    private var timeline by mutableStateOf(NativeTimeline())
    private var headerHeight by mutableIntStateOf(48)
    private var edit by mutableStateOf<MessageEditState?>(null)

    private fun content(deferHistory: Boolean = false, savedDraft: DraftEntity? = null, historyNotice: Boolean = false) = runBlocking {
        db = Room.inMemoryDatabaseBuilder(compose.activity, PocketDatabase::class.java).build()
        val storage = object : PairingStorage {
            override fun load(): PairedComputer? = null
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        repo = PocketRepository(compose.activity, pairStore = storage, db = db, networkAvailable = emptyFlow())
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
                        val displayed=if(historyNotice) current.copy(selected=current.selected?.copy(runtimeId="history:store"),
                            online=true,error="所选运行环境尚未就绪，请稍后重试",conversationReadSource="persisted",conversationLoadState=ConversationLoadState.Ready) else current
                        ChatScreen(displayed.copy(timeline = timeline, conversationCached = true, messageEdit = edit), repo, {}, {}, {},
                            header = { Spacer(Modifier.height(headerHeight.dp)) })
                    } }
                }
            }
        } }
        compose.waitForIdle()
        if (!deferHistory) {
            val transcript = compose.onNodeWithTag("chat-transcript")
            // Row projection runs on Dispatchers.Default, outside Compose idling.
            // Wait for the fixture rows to reach the list before seeking its tail.
            compose.waitUntil(5_000) {
                transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 20f
            }
            transcript.performScrollToNode(hasTestTag("activity-toggle:${command.key}"))
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

    /** Counts only the sampled hotspot; background projection and other readers remain real. */
    private class LiveStatusReads(private val source: Map<String, TimelineItem>) : Map<String, TimelineItem> by source {
        var mainThreadReads = 0
        override val values: Collection<TimelineItem>
            get() {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                    Thread.currentThread().stackTrace.any {
                        it.className == ConversationPresentation::class.java.name && it.methodName == "liveStatus"
                    }) mainThreadReads++
                return source.values
            }
    }

    @Test fun scrollingDoesNotRescanUnchangedLiveStatusButNewTimelineStillUpdates() {
        val loaded = content()
        val reads = LiveStatusReads(loaded.items)
        compose.runOnIdle {
            timeline = loaded.copy(items = reads, activeTurn = "last", completedTurns = loaded.completedTurns - "last")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The initial active snapshot must compute its status", reads.mainThreadReads > 0)
            reads.mainThreadReads = 0
        }
        compose.onNodeWithTag("chat-transcript").performTouchInput { swipeDown(durationMillis = 400) }
        // A measured chrome change must also leave the same timeline's result reusable.
        compose.runOnIdle { headerHeight += 16 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Scrolling/layout must not repeat the main-thread history scan", 0, reads.mainThreadReads)
        }
        val newWork = TimelineItem("last", "new-work", "commandExecution", "New native work", complete = false)
        val next = LiveStatusReads(loaded.items + (newWork.key to newWork))
        compose.runOnIdle { timeline = timeline.copy(items = next) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("A new native snapshot must recompute live status", next.mainThreadReads > 0)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun historyConnectionKeepsTranscriptAndFloatingComposerAvailable() {
        content(historyNotice=true)
        compose.onNodeWithTag("conversation-recovery").assertDoesNotExist()
        compose.onNodeWithTag("chat-transcript").assertIsDisplayed()
        compose.onNodeWithTag("composer-surface").assertIsDisplayed()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file=java.io.File("build/reports/history-status/history-status.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        }
    }

    @Test fun readingHistoryShrinksTheActualChatComposerAndKeepsItsDraft() {
        content(savedDraft=DraftEntity(key,text="中文草稿\n第二行",followEnd=true))
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        compose.onNodeWithTag("chat-transcript").performTouchInput {swipeDown()}
        compose.waitUntil(5_000) {repo.state.value.draft?.followEnd==false}
        compose.onNodeWithTag("composer-history-preview").assertIsDisplayed()
        assertEquals("中文草稿\n第二行",repo.state.value.draft?.text)
        val position=scrollPosition()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(position,scrollPosition(),.1f)
    }

    @Test fun aConversationOpensAtItsEndAndIsNeverSeenAtItsHeadFirst() {
        val loaded = content(deferHistory = true)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { timeline = loaded }
        var frames = 0
        // Rows are projected on another thread; from the frame they first exist, the reader is at the end.
        val deadline = System.currentTimeMillis() + 10_000
        while (frames < 4 && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
            if (compose.onAllNodes(hasText("Recorded paragraph", substring = true)).fetchSemanticsNodes().isEmpty()) { Thread.sleep(10); continue }
            frames++
            assertTrue("Frame $frames after the rows arrived shows the head of the conversation",
                compose.onAllNodes(hasText("Recorded paragraph 0", substring = true)).fetchSemanticsNodes().isEmpty())
            assertFalse("Frame $frames after the rows arrived is not at the end",
                compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() < it.maxValue() - 1f })
        }
        assertEquals("The rows never arrived", 4, frames)
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
        transcript.performTouchInput { down(center); moveBy(Offset(0f, -90f)) }
        compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == false }
        transcript.performTouchInput { up() }
        compose.runOnIdle { headerHeight += 24 }
        compose.waitForIdle()
        assertFalse("A transient no-scroll boundary is not a request to follow", repo.state.value.draft!!.followEnd)
    }

    @Test fun theTranscriptFollowsItsEndAgainOnceAnEditInABubbleIsOver() {
        content()
        val sent = TimelineItem.from("turn-sent", Json.parseToJsonElement("""{"id":"sent","type":"userMessage","content":[{"type":"text","text":"Make the button blue"}]}""").jsonObject, true)
        compose.runOnIdle { timeline = timeline.copy(items = timeline.items + (sent.key to sent)) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("transcript-row:${sent.key}").isDisplayed() }
        // Editing holds the bubble where it stands; that is the editor's doing, not the reader's.
        compose.runOnIdle { edit = MessageEditState(key, "runtime", sent.turnId, sent.itemId, "Make the button blue", "Make the button blue", MessageEditPhase.Ready, version = "v1", token = "t1") }
        compose.waitForIdle()
        compose.onNodeWithTag("message-edit-bubble").assertIsDisplayed()
        compose.runOnIdle { edit = null }
        compose.waitForIdle()
        val next = TimelineItem("new", "message", "agentMessage", "New recorded response\n".repeat(40), complete = true)
        compose.runOnIdle { timeline = timeline.copy(items = timeline.items + (next.key to next)) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("transcript-row:${next.key}").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("What arrives after the edit is followed to its end", list.maxValue(), list.value(), 1f)
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
        // The button first resumes local follow and scrolls. Its settled position is persisted
        // by the 200 ms debounce, unlike the immediate intent write from a real drag to tail.
        compose.waitForIdle()
        repeat(16) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        try { compose.waitUntil(2_000) { repo.state.value.draft?.followEnd == true } }
        catch (failure: Throwable) {
            throw AssertionError("Jump follow did not persist: follow=${repo.state.value.draft?.followEnd}; " +
                "historyPreview=${compose.onAllNodesWithTag("composer-history-preview").fetchSemanticsNodes().size}; " +
                "position=${scrollPosition()}; " + transcript.printToString().take(1200), failure)
        }
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        // The expanded minimap deliberately holds automatic follow while the composer
        // changes height. Let its existing 2.5 s idle collapse finish before checking
        // the settled end position; the follow intent above must already be saved.
        repeat(180) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        val tail = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Jump to latest must reach the tail as well as persist follow", tail.maxValue(), tail.value(), 1f)
    }

    @Test fun fastFlingToTailExpandsTheComposerWithoutAnotherGesture() {
        content(savedDraft=DraftEntity(key,text="快速滑动后保留草稿",followEnd=true))
        val transcript=compose.onNodeWithTag("chat-transcript")
        transcript.performTouchInput { swipeDown() }
        compose.waitUntil(5_000) {repo.state.value.draft?.followEnd==false}
        // Robolectric's text layout can fit the last ten rows in one viewport.
        // Start above the boundary so the fling consumes real transcript movement.
        transcript.performScrollToIndex(8)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-history-preview").assertIsDisplayed()
        val before=transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val beforePosition=before.value()
        assertTrue("The fling must start above the tail",beforePosition < before.maxValue())
        transcript.performTouchInput { swipeUp(durationMillis=60) }
        try { compose.waitUntil(5_000) {repo.state.value.draft?.followEnd==true} }
        catch(failure:Throwable) {
            throw AssertionError("Fast fling stopped at ${scrollPosition()}; draft=${repo.state.value.draft}; " +
                "transcript=${transcript.fetchSemanticsNode().boundsInRoot}; composer=${compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot}; " +
                transcript.printToString().take(1800),failure)
        }
        assertTrue("The fling must advance the transcript",scrollPosition() > beforePosition)
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertTextEquals("快速滑动后保留草稿")
    }
}
