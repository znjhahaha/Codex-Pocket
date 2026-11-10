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

    private fun content(deferHistory: Boolean = false, savedDraft: DraftEntity? = null) = runBlocking {
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
                        ChatScreen(current.copy(timeline = timeline, conversationCached = true), repo, {}, {}, {},
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
