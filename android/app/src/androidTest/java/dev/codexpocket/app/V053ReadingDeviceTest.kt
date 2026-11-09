package dev.codexpocket.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt in, in a fresh instrumentation process:
 * -e v053Reading true -e class dev.codexpocket.app.V053ReadingDeviceTest
 * Individual methods may be selected with class#method. No live pairing, draft or model service.
 * PNGs, stages.jsonl and report.json are written under externalFilesDir/v053-reading-proof/.
 * Frame timings are observations, not a performance acceptance threshold or physical-device claim.
 */
@RunWith(AndroidJUnit4::class)
class V053ReadingDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val connections = AtomicInteger()
    private val pairingWrites = AtomicInteger()

    // The opt-in and isolation MUST wrap Activity launch, including for a skipped test.
    // Same lifecycle/lazy binding protocol as V052ControlsDeviceTest.
    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Opt in with v053Reading=true",
                    InstrumentationRegistry.getArguments().getString("v053Reading") == "true")
                val application = instrumentation.targetContext.applicationContext as PocketApplication
                val field = PocketApplication::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }
                val original = field.get(application) as Lazy<*>
                check(!original.isInitialized()) { "Run V053 alone in a fresh instrumentation process" }
                val database = Room.inMemoryDatabaseBuilder(application, PocketDatabase::class.java).build()
                var isolated: PocketRepository? = null
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(application)
                    field.set(application, lazy {
                        PocketRepository(application,
                            connectionFactory = { connections.incrementAndGet(); error("Reading fixture attempted a connection") },
                            pairStore = object : PairingStorage {
                                override fun load(): PairedComputer? = null
                                override fun save(pair: PairedComputer) { pairingWrites.incrementAndGet(); error("Reading fixture attempted pairing") }
                                override fun forget() { pairingWrites.incrementAndGet(); error("Reading fixture attempted to clear pairing") }
                            }, db = database, networkAvailable = emptyFlow()
                        ).also { isolated = it }
                    })
                }
                try { base.evaluate() }
                finally {
                    try {
                        isolated?.let { repository ->
                            val scopeField = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
                            val scope = scopeField.get(repository) as CoroutineScope
                            runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
                        }
                    } finally {
                        database.close()
                        val deadline = SystemClock.elapsedRealtime() + 5_000
                        while (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                        instrumentation.runOnMainSync {
                            field.set(application, original)
                            check(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                "Fixture Activity did not stop; cannot restore reconnect observer"
                            }
                            ProcessLifecycleOwner.get().lifecycle.addObserver(application)
                        }
                    }
                }
            }
        }
    }).around(compose)

    private data class Variant(val id: String, val theme: String, val fontScale: Float)
    private val light = Variant("light", "light", 1f)
    private val fixtureKey = ThreadKey("v053-fixture-pc", "v053-fixture-store", "reading")
    private val draftText = "本地保留草稿 · 不提交消息"
    private val bodyPrefix = "中文正文与 Inter Latin"
    private var variant by mutableStateOf(light)
    private var state by mutableStateOf(PocketState())
    private var readingHistory = false
    private var fixtureDensity = 1f
    private lateinit var directory: File
    private var stage = "setup"
    private val stages = mutableListOf<JsonObject>()
    private val screenshots = mutableListOf<JsonObject>()
    private val observations = linkedMapOf<String, JsonElement>()

    private fun fixtureTimeline(turnCount: Int, tools: Int = 0): NativeTimeline {
        val items = linkedMapOf<String, TimelineItem>()
        val turns = linkedMapOf<String, NativeTurnInfo>()
        // Same bounded 500-turn / 1,200-item workload as V05PerformanceDeviceTest.
        repeat(turnCount) { n ->
            val turn = "v053-$n"
            turns[turn] = NativeTurnInfo(turn, status = "completed", durationMs = 1_000)
            fun add(item: TimelineItem) { items[item.key] = item }
            add(TimelineItem(turn, "user", "userMessage", "检查项目文件 $n，保留中文与代码阅读。", complete = true))
            if (n < tools) add(TimelineItem.from(turn, buildJsonObject {
                put("id", "tool"); put("type", "commandExecution"); put("status", "completed")
                put("command", "Get-Content src/Fixture$n.kt"); put("exitCode", 0)
                put("aggregatedOutput", "本地工具记录 $n；此命令仅是文本，不执行。")
            }))
            val text = if (n == turnCount - 1 && turnCount < 100)
                "# 阅读验收\n\n$bodyPrefix，字号与行距使用真实组件。第二行文字检查窄屏换行与标点。\n\n" +
                    "## 独立标题\n\n段落之间保留间距。\n\n```kotlin\nval reading = true\n```\n\n末尾标记 $n。"
            else "已检查第 $n 项。\n\n$bodyPrefix，保持原生记录和阅读位置。"
            add(TimelineItem(turn, "final", "agentMessage", text, complete = true))
        }
        return NativeTimeline(items, completedTurns = turns.keys.toSet(), turns = turns)
    }

    private fun fixtureState(timeline: NativeTimeline) = PocketState(initialized = true,
        selected = ChatEntity(fixtureKey, "v053-fixture-runtime", "desktop", "本地阅读样本", "", "D:/fixture", 0, "idle", false),
        timeline = timeline, draft = DraftEntity(fixtureKey, text = draftText),
        conversationLoadState = ConversationLoadState.Ready, historyExhausted = true, pushConfigured = false)

    @Composable private fun FixtureHost(content: @Composable () -> Unit) {
        val physical = LocalDensity.current
        val context = LocalContext.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // Context and Compose density agree; changing LocalDensity alone leaves native Markdown
        // TextViews at the system font size. No global/device font or theme setting is changed.
        val configured = remember(context, configuration, variant.fontScale) {
            context.createConfigurationContext(Configuration(configuration).apply { fontScale = variant.fontScale })
        }
        CompositionLocalProvider(LocalContext provides configured, LocalDensity provides Density(physical.density, variant.fontScale),
            androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity,
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides compose.activity) {
            SideEffect { fixtureDensity = physical.density }
            PocketTheme(variant.theme) {
                PocketBackdrop { ProvideMarkdownRendering {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = 360.dp).fillMaxHeight().fillMaxWidth().safeDrawingPadding().testTag("v053-viewport")) {
                            content()
                        }
                    }
                } }
            }
        }
    }

    private fun mountChat() {
        instrumentation.runOnMainSync { compose.activity.setContent {
            FixtureHost { key(variant.id) {
                ChatScreen(state, (compose.activity.application as PocketApplication).repository,
                    onModels = { error("Unexpected model picker") }, onDetail = { error("Unexpected detail request") },
                    onReferences = { error("Unexpected references request") }, onReadingHistory = { readingHistory = it },
                    header = { ConversationHeader(state, {}, {}, {}, compact = readingHistory) })
            } }
        } }
    }

    @Test(timeout = 180_000) fun mainTranscriptTypographyPlanAndMinimap() = proof("main-reading") {
        val original = fixtureTimeline(24)
        state = fixtureState(original)
        mountChat()
        listOf(light, Variant("dark", "dark", 1f), Variant("dark-large", "dark", 1.4f)).forEach { v ->
            compose.runOnIdle { variant = v; state = fixtureState(original) }
            record("${v.id}-main-typography") {
                compose.onNodeWithTag("thread-ownership").assertDoesNotExist()
                waitNative("末尾标记 23。")
                waitEnd()
                compose.onNodeWithTag("composer-surface").assertIsDisplayed()
                assertNativeTypography(v)
                assertStableScroll("${v.id}-end", requireEnd = true)
                assertMinimapBounds("composer-surface")
                capture("${v.id}-main")
            }
            record("${v.id}-history-stream-and-minimap") {
                swipeTranscript(transcript(), towardHistory = true, durationMillis = 600)
                compose.waitUntil(5_000) { readingHistory }
                // Inspect a real user row only after dragging has suspended automatic follow.
                // A row outside the lazy viewport is not guaranteed to have a TextLayoutResult.
                val userText = "检查项目文件 23，保留中文与代码阅读。"
                transcript().performScrollToNode(hasText(userText))
                assertUserTypography(v, userText)
                settle()
                assertStableScroll("${v.id}-history", requireEnd = false) { sample ->
                    if (sample in listOf(3, 6, 9)) compose.runOnIdle {
                        state = state.copy(timeline = growLastAnswer(state.timeline, "\n\n本地流式增量 $sample。"))
                    }
                }
            }
            verifyMinimapGestures(v)
            record("${v.id}-minimap-accessible-seek") {
                val before = scrollValue()
                compose.onNodeWithTag("conversation-minimap").performSemanticsAction(SemanticsActions.SetProgress) { it(.35f) }
                compose.waitUntil(5_000) { kotlin.math.abs(scrollValue() - before) > .1f }
                settle()
                assertEquals("Minimap viewport reflects the requested loaded position", .35f, minimapProgress(), .04f)
                assertTrue("Minimap seek must retain history intent", readingHistory)
                assertStableScroll("${v.id}-minimap-seek", requireEnd = false)
                compose.onNodeWithTag("composer-history-preview").assertIsDisplayed().assertTextEquals(draftText)
            }
            record("${v.id}-bottom-plan-replaces-composer") {
                val planItem = TimelineItem("v053-plan", "proposal", "plan",
                    "# 本地计划\n\n1. 核对字号与正文。\n2. 检查底部计划和阅读位置。", complete = true)
                val planTimeline = original.copy(items = original.items + (planItem.key to planItem),
                    completedTurns = original.completedTurns + planItem.turnId,
                    turns = original.turns + (planItem.turnId to NativeTurnInfo(planItem.turnId, status = "completed")))
                compose.runOnIdle { state = fixtureState(planTimeline) }
                waitTag("plan-confirmation")
                // Assert actual ChatScreen composition, without invoking evolving Plan callbacks.
                compose.onNodeWithTag("composer-surface").assertDoesNotExist()
                compose.onNodeWithTag("composer-input").assertDoesNotExist()
                compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
                compose.onNodeWithTag("plan-implement").assertIsDisplayed()
                compose.onNodeWithTag("plan-modify").assertIsDisplayed()
                val dock = bounds("plan-confirmation")
                val viewport = bounds("v053-viewport")
                assertTrue("Plan is in bottom chrome", dock.top > viewport.center.y)
                assertTrue("Plan stays inside the safe viewport", dock.bottom <= viewport.bottom + 2f)
                assertMinimapBounds("plan-confirmation")
                settle()
                assertStableScroll("${v.id}-plan", requireEnd = false, requireHistory = false)
                // Exact reported regression: click "jump to latest" after the plan dock appears.
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.jump_latest)).performClick()
                waitEnd()
                settle()
                assertStableScroll("${v.id}-plan-jump-end", requireEnd = true, requireHistory = false)
                compose.onNodeWithTag("proposed-plan").assertIsDisplayed()
                assertEquals(draftText, state.draft?.text)
                capture("${v.id}-plan")
            }
        }
    }

    private fun verifyMinimapGestures(v: Variant) {
        assertMinimapReservation(expanded = false)
        assertCollapsedUserTickSemantics("${v.id}-collapsed-user-ticks")
        record("${v.id}-minimap-equal-user-mapping") {
            assertEqualUserMinimapMapping("${v.id}-equal-user-mapping")
        }

        record("${v.id}-minimap-collapsed-hold") {
            val beforeTranscript = bounds("chat-transcript")
            val beforeScroll = scrollValue()
            val selectedUser = loadedUserTexts()[4]
            val downPoint = minimapTouchPoint(collapsedUserFraction(4))
            compose.onNodeWithTag("conversation-minimap").performTouchInput { down(downPoint) }
            try {
                // Separate injection calls retain the down pointer while assertions and PixelCopy run.
                // Check before advancing time: preview is a down response, not a long-press response.
                assertCollapsedMinimap(beforeScroll, beforeTranscript, preview = true)
                assertCollapsedUserPreview(selectedUser)
                val initialPreview = minimapPreviewText()
                val holdMillis = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100L
                SystemClock.sleep(holdMillis)
                compose.onNodeWithTag("conversation-minimap").performTouchInput { advanceEventTime(holdMillis) }
                assertCollapsedMinimap(beforeScroll, beforeTranscript, preview = true)
                assertEquals("Holding does not change the preview target", initialPreview, minimapPreviewText())
                observeMinimap("${v.id}-collapsed-hold")
                // Capture the held preview separately from expanded ink and the active glass thumb.
                if (v.fontScale == 1f) capture("${v.id}-minimap-collapsed-hold", includeMinimapPreview = true)
                assertCollapsedMinimap(beforeScroll, beforeTranscript, preview = true)
            } finally {
                // Keep the injected UP timestamp in this batch after the DOWN by a real
                // long-press interval; idling/PixelCopy do not advance Android input time.
                compose.onNodeWithTag("conversation-minimap").performTouchInput {
                    advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100L)
                    up()
                }
            }
            assertCollapsedReleaseJump("${v.id}-hold-release-jump", selectedUser, beforeScroll, beforeTranscript)
            observeMinimap("${v.id}-hold-release")
        }

        record("${v.id}-minimap-collapsed-scrub") {
            // The hold release has already jumped; take a new anchor for this gesture.
            val beforeTranscript = bounds("chat-transcript")
            val beforeScroll = scrollValue()
            val users = loadedUserTexts()
            val downPoint = minimapTouchPoint(collapsedUserFraction(4))
            var selectedUser = users[4]
            compose.onNodeWithTag("conversation-minimap").performTouchInput { down(downPoint) }
            try {
                assertCollapsedMinimap(beforeScroll, beforeTranscript, preview = true)
                // The same equal user interval selects the same question after a prior jump.
                var previousUser = assertCollapsedUserPreview(selectedUser)
                listOf(13, 19).forEach { userIndex ->
                    val fraction = collapsedUserFraction(userIndex)
                    val point = minimapTouchPoint(fraction)
                    compose.onNodeWithTag("conversation-minimap").performTouchInput { moveTo(point, delayMillis = 80L) }
                    assertCollapsedMinimap(beforeScroll, beforeTranscript, preview = true)
                    selectedUser = users[userIndex]
                    val currentUser = assertCollapsedUserPreview(selectedUser)
                    assertNotEquals("Collapsed scrub selects another equally spaced user message", previousUser, currentUser)
                    previousUser = currentUser
                    observeMinimap("${v.id}-collapsed-scrub-$fraction")
                }
            } finally {
                compose.onNodeWithTag("conversation-minimap").performTouchInput { up() }
            }
            assertCollapsedReleaseJump("${v.id}-scrub-release-jump", selectedUser, beforeScroll, beforeTranscript)
            observeMinimap("${v.id}-scrub-release")
        }

        record("${v.id}-minimap-short-tap-expands") {
            // Both release jumps are complete. Reflow must compare the current visible prose.
            waitNative(bodyPrefix)
            val proseText = nativeReadingText()
            val beforeProseWidth = nativeReadingWidth(proseText)
            val beforeTranscript = bounds("chat-transcript")
            val beforeScroll = scrollValue()
            val downPoint = minimapTouchPoint(collapsedUserFraction(4))
            // Down-only state is checked above. Batch this tap so idling/assertions/capture cannot
            // turn it into a legitimate hold under max(real uptime elapsed, input event delta).
            compose.onNodeWithTag("conversation-minimap").performTouchInput {
                down(downPoint)
                advanceEventTime(80L)
                up()
            }
            compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("conversation-minimap")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            settle()
            assertMinimapReservation(expanded = true)
            assertEquals("Short tap release expands without seeking", beforeScroll, scrollValue(), .00005f)
            val afterTranscript = bounds("chat-transcript")
            val reflowPx = 24f * fixtureDensity
            assertEquals("Expansion moves the measured transcript edge by 24dp", reflowPx,
                afterTranscript.left - beforeTranscript.left, 2f)
            assertEquals("Expansion narrows the measured transcript by 24dp", reflowPx,
                beforeTranscript.width - afterTranscript.width, 2f)
            assertEquals("Native Markdown receives narrower constraints and reflows", reflowPx,
                (beforeProseWidth - nativeReadingWidth(proseText)).toFloat(), 2f)
            assertTrue("Expansion retains history intent", readingHistory)
            observeMinimap("${v.id}-expanded-reflow")
            if (v.fontScale == 1f) capture("${v.id}-minimap-expanded")
        }

        record("${v.id}-minimap-expanded-drag") {
            assertMinimapReservation(expanded = true)
            val downPoint = minimapTouchPoint(.2f)
            val railBefore = bounds("conversation-minimap")
            val thumbBefore = bounds("conversation-minimap-thumb")
            observations["${v.id}-track-drag-origin"] = buildJsonObject {
                put("railTop", railBefore.top); put("railHeight", railBefore.height)
                put("downY", downPoint.y); put("thumbTop", thumbBefore.top - railBefore.top)
                put("thumbBottom", thumbBefore.bottom - railBefore.top)
            }
            assertFalse("Track drag fixture must start outside the actual thumb",
                downPoint.y in (thumbBefore.top - railBefore.top)..(thumbBefore.bottom - railBefore.top))
            compose.onNodeWithTag("conversation-minimap").performTouchInput { down(downPoint) }
            try {
                listOf(.35f, .65f).forEach { fraction ->
                    val before = scrollValue()
                    val point = minimapTouchPoint(fraction)
                    compose.onNodeWithTag("conversation-minimap").performTouchInput { moveTo(point, delayMillis = 80L) }
                    // Seeking first reveals a lazy row, waits for its native Markdown measurement,
                    // then applies the within-row offset. A first scroll delta is not completion,
                    // particularly while PixelCopy is recording. Keep the finger held and require
                    // the actual requested destination, without widening its final tolerance.
                    compose.waitUntil(5_000) {
                        kotlin.math.abs(scrollValue() - before) > .1f &&
                            kotlin.math.abs(minimapProgress() - fraction) <= .04f
                    }
                    settle()
                    observeMinimap("${v.id}-track-drag-observed-$fraction")
                    assertEquals("Expanded drag seeks while held at $fraction", fraction, minimapProgress(), .04f)
                    assertMinimapReservation(expanded = true)
                    assertTrue("Expanded drag retains history intent", readingHistory)
                    observeMinimap("${v.id}-expanded-drag-$fraction")
                }
            } finally {
                compose.onNodeWithTag("conversation-minimap").performTouchInput { up() }
            }
            settle()
            assertEquals("Expanded release flushes the last drag target", .65f, minimapProgress(), .04f)
            compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
            assertStableScroll("${v.id}-expanded-drag-release", requireEnd = false)
        }

        record("${v.id}-minimap-expanded-thumb-drag") {
            val rail = bounds("conversation-minimap")
            val thumb = bounds("conversation-minimap-thumb")
            val beforeScroll = scrollValue()
            val grabPoint = Offset(thumb.center.x - rail.left, thumb.center.y - rail.top)
            // Grab the actual slider, then move it above its current position while it stays held.
            val movedPoint = grabPoint.copy(y = (grabPoint.y - rail.height * .2f).coerceAtLeast(8f * fixtureDensity))
            var heldScroll = beforeScroll
            var heldFraction = 0f
            var heldUser: String? = null
            var heldUserTop = 0f
            compose.onNodeWithTag("conversation-minimap").performTouchInput { down(grabPoint) }
            try {
                compose.onNodeWithTag("conversation-minimap").performTouchInput { moveTo(movedPoint, delayMillis = 80L) }
                compose.waitUntil(5_000) { kotlin.math.abs(scrollValue() - beforeScroll) > .1f }
                settle()
                assertMinimapReservation(expanded = true)
                compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
                heldScroll = scrollValue()
                heldFraction = minimapProgress()
                assertTrue("Dragging the expanded glass thumb retains history intent", readingHistory)
                // Stop moving but retain the actual grabbed pointer through the held spring.
                // A dragged thumb has no tick preview; a fresh stationary long press may show one.
                val holdMillis = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100L
                SystemClock.sleep(holdMillis)
                compose.onNodeWithTag("conversation-minimap").performTouchInput { advanceEventTime(holdMillis) }
                assertMinimapReservation(expanded = true)
                compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertDoesNotExist()
                assertEquals("Holding the grabbed glass thumb does not seek again", heldScroll, scrollValue(), .00005f)
                observeMinimap("${v.id}-expanded-thumb-held")
                heldUser = loadedUserTexts().firstOrNull { text ->
                    compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any {
                        val box = it.boundsInRoot
                        box.height > 0f && box.top >= bounds("chat-transcript").top && box.bottom <= bounds("chat-transcript").bottom
                    }
                }
                heldUser?.let { heldUserTop = compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
                // Raw Activity PixelCopy of the held glass; no preview composite or screenshot overlay.
                if (v.fontScale == 1f) capture("${v.id}-minimap-expanded-thumb-active")
                assertEquals("A stationary held thumb keeps its selected position", heldScroll, scrollValue(), .00005f)
            } finally {
                compose.onNodeWithTag("conversation-minimap").performTouchInput { up() }
            }
            settle()
            assertMinimapReservation(expanded = true)
            assertEquals("Expanded release retains the logical loaded position", heldFraction, minimapProgress(), .002f)
            heldUser?.let { text ->
                assertEquals("Expanded release retains the visible text anchor", heldUserTop,
                    compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, fixtureDensity * 1.5f)
            }
            assertStableScroll("${v.id}-expanded-thumb-release", requireEnd = false)
        }
    }

    private fun collapsedUserFraction(index: Int): Float {
        val users = loadedUserTexts()
        require(index in users.indices)
        val rail = bounds("conversation-minimap")
        val track = (rail.height - 16f * fixtureDensity).coerceAtLeast(1f)
        val span = minOf(1f, users.size * 10f * fixtureDensity / track)
        return (1f - span) / 2f + span * (index + .5f) / users.size
    }

    private fun assertEqualUserMinimapMapping(name: String) {
        val rows = ConversationPresentation.rows(state.timeline)
        val users = rows.filter { it.kind == TranscriptKind.User }
        assertTrue("Equal-spacing fixture needs multiple users", users.size > 2)
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val rail = bounds("conversation-minimap")
        val marks = minimapMarks(map, null, rail.width, rail.height, fixtureDensity, expanded = false)
        assertEquals("Collapsed marks contain every loaded user in order", users.map { it.key }, marks.map { it.key })
        assertTrue("Collapsed marks contain only users", marks.all { it.kind == TranscriptKind.User })
        val centers = marks.map { it.top + it.height / 2f }
        val gap = (centers.last() - centers.first()) / (users.size - 1)
        assertTrue("Collapsed user intervals must be positive", gap > 0f)
        centers.zipWithNext().forEach { (first, second) ->
            // Allow sub-dp edge clamping of a stroke, but never content-weighted intervals.
            assertEquals("Collapsed user tick intervals are equal regardless of answer length", gap, second - first, fixtureDensity)
        }
        users.forEachIndexed { index, user ->
            assertEquals("Unbounded logical question center follows (userIndex + .5) / users", (index + .5f) / users.size,
                requireNotNull(map.questionFraction(user.key)), .000001f)
            listOf(.25f, .5f, .75f).forEach { withinInterval ->
                val fraction = (index + withinInterval) / users.size
                val target = requireNotNull(map.userTargetAt(fraction))
                assertEquals("Floor selection throughout equal user interval $index selects that user", user.key, target.key)
                assertEquals("Collapsed selection targets the start of a user row", 0f, target.withinRow, 0f)
            }
        }
        assertEquals("Collapsed track start selects the first user", users.first().key, map.userTargetAt(0f)?.key)
        assertEquals("Collapsed track end selects the last user", users.last().key, map.userTargetAt(1f)?.key)
        observations[name] = buildJsonObject {
            put("loadedUserMessages", users.size); put("loadedRows", rows.size)
            put("userOnlyTickGeometryVerified", true); put("equalUserTickSpacingVerified", true)
            put("equalUserTargetMappingVerified", true); put("tickIntervalDp", gap / fixtureDensity)
            put("tickCentersDp", JsonArray(centers.map { JsonPrimitive(it / fixtureDensity) }))
        }
    }

    private fun assertCollapsedReleaseJump(name: String, selectedUser: String, beforeScroll: Float, beforeTranscript: Rect) {
        val rows = ConversationPresentation.rows(state.timeline)
        val rowIndex = rows.indexOfFirst { it.kind == TranscriptKind.User && it.items.first().text == selectedUser }
        assertTrue("Release target is a loaded user row", rowIndex >= 0)
        val targetRow = rows[rowIndex]
        val map = ConversationMinimapMap(rows, rows.map { it.key })
        val expectedFraction = map.range(rowIndex).start
        // LazyColumn semantics is a synthetic pixel axis, not an item index. Verify the native
        // row via the same key mapping and visible text instead of comparing it with rowIndex.
        compose.waitUntil(5_000) { kotlin.math.abs(minimapProgress() - expectedFraction) < .002f }
        settle()
        assertTrue("Collapsed release must jump from the previous reading position", kotlin.math.abs(scrollValue() - beforeScroll) > .1f)
        assertEquals("Collapsed release lands at the selected user row start", expectedFraction, minimapProgress(), .002f)
        compose.onNodeWithText(selectedUser, useUnmergedTree = true).assertIsDisplayed()
        assertCollapsedMinimap(scrollValue(), beforeTranscript, preview = false)
        observations[name] = buildJsonObject {
            put("selectedUser", selectedUser); put("expectedKey", targetRow.key); put("expectedFraction", expectedFraction)
            put("beforeAxis", beforeScroll); put("releasedAxis", scrollValue())
            put("releaseJumpVerified", true)
        }
        assertStableScroll("$name-stable", requireEnd = false)
    }

    private fun minimapTouchPoint(fraction: Float): Offset {
        val rail = bounds("conversation-minimap")
        val inset = (8f * fixtureDensity).coerceAtMost(rail.height / 4f)
        return Offset(rail.width / 2f, inset + (rail.height - 2f * inset) * fraction)
    }

    private fun minimapPreviewText(): String {
        val text = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("conversation-minimap-preview")), useUnmergedTree = true
        ).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString("\n") { it.text }
        assertTrue("Held preview exposes loaded text", text.isNotBlank())
        return text
    }

    private fun loadedUserTexts() = ConversationPresentation.rows(state.timeline)
        .filter { it.kind == TranscriptKind.User }.map { it.items.first().text }

    private fun assertCollapsedUserPreview(expectedUser: String? = null): String {
        val lines = minimapPreviewText().lineSequence().toList()
        val question = lines.single { it.startsWith("提问：") }.removePrefix("提问：")
        val rows = ConversationPresentation.rows(state.timeline)
        val users = rows.filter { it.kind == TranscriptKind.User }
        assertTrue("Collapsed preview must identify a loaded user message", users.any { it.items.first().text == question })
        val position = lines.mapNotNull { Regex("^(\\d+) / (\\d+) · 已加载$").matchEntire(it) }.single()
        val index = position.groupValues[1].toInt() - 1
        val total = position.groupValues[2].toInt()
        // Read the preview's own loaded-position metadata; do not infer a row from touch fraction.
        // Both an all-message position and a user-only position can identify the snapped question.
        val positionedRows = when (total) {
            rows.size -> rows
            users.size -> users
            else -> error("Unexpected collapsed preview position: ${position.value}")
        }
        assertTrue("Preview position is inside its loaded message set", index in positionedRows.indices)
        val selected = positionedRows[index]
        assertEquals("Collapsed preview target must be a user, not its neighboring answer", TranscriptKind.User, selected.kind)
        assertEquals("Preview question matches its selected user", selected.items.first().text, question)
        if (expectedUser != null) assertEquals("Touch in an equal user interval previews that exact user", expectedUser, question)
        return question
    }

    private fun assertCollapsedUserTickSemantics(name: String) {
        val users = loadedUserTexts()
        val labels = mutableListOf<String>()
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.children.forEach { child ->
                val config = child.config
                val text = if (config.contains(SemanticsProperties.Text)) config[SemanticsProperties.Text].map { it.text } else emptyList()
                val descriptions = if (config.contains(SemanticsProperties.ContentDescription)) config[SemanticsProperties.ContentDescription] else emptyList()
                labels += text + descriptions
                collect(child)
            }
        }
        // Canvas marks currently have no separate semantics. Inspect only actual accessible
        // descendants; the equal user geometry and target mapping are checked separately.
        val rail = compose.onNodeWithTag("conversation-minimap", useUnmergedTree = true).fetchSemanticsNode()
        collect(rail)
        val railDescriptions = if (rail.config.contains(SemanticsProperties.ContentDescription))
            rail.config[SemanticsProperties.ContentDescription] else emptyList()
        // Unmerged semantics may synthesize a child carrying the rail's own description.
        labels.removeAll { it.isBlank() || it in railDescriptions }
        labels.forEach { label ->
            assertTrue("Collapsed tick semantics must identify users only: $label", users.any { it in label })
        }
        observations[name] = buildJsonObject {
            put("tickSemanticsAccessible", labels.isNotEmpty())
            put("tickLabels", JsonArray(labels.map(::JsonPrimitive)))
            put("userOnlyTicksVerifiedViaSemantics", labels.isNotEmpty())
            put("loadedUserMessages", users.size)
        }
    }

    private fun nativeReadingText() = compose.runOnIdle {
        nativeViews(compose.activity.window.decorView)
            .first { it.isShown && bodyPrefix in it.text.toString() && it.layout != null }.text.toString()
    }

    private fun nativeReadingWidth(text: String) = compose.runOnIdle {
        // Several loaded answers share this paragraph. They must all have the same constraints;
        // do not require a unique TextView or a final answer that has left the lazy viewport.
        val widths = nativeViews(compose.activity.window.decorView)
            .filter { it.isShown && it.text.toString() == text }.mapNotNull { it.layout?.width }.distinct()
        assertEquals("Current native prose has one measured constraint width", 1, widths.size)
        widths.single()
    }

    private fun assertCollapsedMinimap(beforeScroll: Float, beforeTranscript: Rect, preview: Boolean) {
        assertMinimapReservation(expanded = false)
        val node = compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true)
        if (preview) {
            node.assertIsDisplayed()
            assertCollapsedUserPreview()
        } else node.assertDoesNotExist()
        assertEquals(if (preview) "Collapsed down/hold/scrub must not seek before release" else
            "Collapsed release retains the selected user anchor", beforeScroll, scrollValue(), .00005f)
        val transcript = bounds("chat-transcript")
        assertEquals("Collapsed preview keeps the transcript reservation", beforeTranscript.left, transcript.left, 2f)
        assertEquals("Collapsed preview does not reflow the transcript", beforeTranscript.width, transcript.width, 2f)
        assertTrue("Collapsed gesture preserves history intent", readingHistory)
    }

    private fun assertMinimapReservation(expanded: Boolean) {
        compose.onNodeWithTag("conversation-minimap").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (expanded) "已展开" else "已收起"))
        assertMinimapBounds("composer-surface")
        val rail = bounds("conversation-minimap")
        assertEquals("Rail reserves ${if (expanded) 48 else 24}dp", (if (expanded) 48f else 24f) * fixtureDensity, rail.width, 2f)
        assertEquals("Transcript constraints begin after the rail", rail.right, bounds("chat-transcript").left, 2f)
        val thumb = compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true)
        if (expanded) thumb.assertIsDisplayed() else thumb.assertDoesNotExist()
    }

    private fun observeMinimap(name: String) {
        val rail = bounds("conversation-minimap")
        val transcript = bounds("chat-transcript")
        observations[name] = buildJsonObject {
            put("railWidthDp", rail.width / fixtureDensity)
            put("transcriptLeftDp", transcript.left / fixtureDensity)
            put("transcriptWidthDp", transcript.width / fixtureDensity)
            put("axis", scrollValue()); put("viewportFraction", minimapProgress()); put("readingHistory", readingHistory)
            put("state", compose.onNodeWithTag("conversation-minimap").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            put("previewVisible", compose.onAllNodesWithTag("conversation-minimap-preview", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            if (compose.onNodeWithTag("conversation-minimap").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已收起" &&
                compose.onAllNodesWithTag("conversation-minimap-preview", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                put("collapsedPreviewUser", assertCollapsedUserPreview())
            }
        }
    }

    private class ScrollFixture {
        lateinit var list: LazyListState
        var following by mutableStateOf(true)
        var blocked by mutableStateOf(false)
        var bottomPadding by mutableIntStateOf(20)
        var rows by mutableStateOf<List<TranscriptRow>>(emptyList())
    }

    @Test(timeout = 90_000) fun followEndHonorsDragAndBlockedLayoutChanges() = proof("follow-end") {
        val f = ScrollFixture().apply { rows = ConversationPresentation.rows(fixtureTimeline(24)) }
        instrumentation.runOnMainSync { compose.activity.setContent {
            FixtureHost {
                val list = rememberLazyListState()
                SideEffect { f.list = list }
                ObserveTranscriptDrag(list) { f.following = it }
                FollowTranscriptEnd(list, f.following, blocked = f.blocked)
                LazyColumn(Modifier.fillMaxSize().testTag("v053-follow-list"), state = list,
                    contentPadding = PaddingValues(bottom = f.bottomPadding.dp)) {
                    items(f.rows, key = { it.key }, contentType = { it.kind }) { row ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)) {
                            TranscriptRowView(row, "v053-follow", false, {}, { error("Unexpected detail") })
                        }
                    }
                    item(key = "v053-follow-end") { Spacer(Modifier.height(1.dp)) }
                }
            }
        } }
        record("follow-end-initial-layout") {
            waitNative("末尾标记 23。")
            compose.waitUntil(5_000) { listAtEnd(f) }
            assertStableList(f, "initial")
        }
        record("blocked-growth-and-bottom-inset") {
            compose.runOnIdle { f.blocked = true }
            val before = listPosition(f)
            compose.runOnIdle {
                f.bottomPadding = 140
                f.rows = f.rows + TranscriptRow("v053-added-row", TranscriptKind.Answer,
                    listOf(TimelineItem("added", "answer", "agentMessage", "追加的真实正文组件。", complete = true)))
            }
            settle()
            assertEquals("Blocked follow must retain anchor and offset", before, listPosition(f))
            assertFalse("New content remains below while blocked", listAtEnd(f))
            assertStableList(f, "blocked")
            capture("follow-blocked")
        }
        record("unblocked-follow-and-user-drag") {
            compose.runOnIdle { f.blocked = false }
            compose.waitUntil(5_000) { listAtEnd(f) }
            assertStableList(f, "unblocked")
            swipeTranscript(compose.onNodeWithTag("v053-follow-list"), towardHistory = true, durationMillis = 600)
            compose.waitUntil(5_000) { !f.following && !f.list.isScrollInProgress }
            settle()
            val before = listPosition(f)
            compose.runOnIdle { f.bottomPadding = 200 }
            assertStableList(f, "drag-stops-follow")
            assertEquals("User history survives bottom chrome growth", before, listPosition(f))
            assertFalse(listAtEnd(f))
        }
    }

    @Test(timeout = 120_000) fun thousandMessagesTwoHundredToolsFrameMetrics() = proof("performance") {
        val timeline = fixtureTimeline(500, 200)
        val source = timeline.items.values
        assertEquals(1_000, source.count { it.type == "userMessage" || it.type == "agentMessage" })
        assertEquals(200, source.count { it.type == "commandExecution" })
        val projectionMs = (0..20).map { iteration ->
            val start = System.nanoTime()
            val rows = ConversationPresentation.rows(timeline)
            // Folding intentionally retains tools inside Process rows; count source identities.
            assertEquals(1_200, rows.flatMap { it.items }.map { it.key }.distinct().size)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            iteration to elapsed
        }.drop(1).map { it.second }.sorted()
        observations["workload"] = buildJsonObject {
            put("messages", 1_000); put("tools", 200); put("sourceItems", source.size)
            put("projectedRows", ConversationPresentation.rows(timeline).size)
            put("projectionSamples", JsonArray(projectionMs.map(::JsonPrimitive)))
            put("projectionP95Ms", percentile(projectionMs, .95))
        }
        record("large-fixture-local-minimap-timings") { observeLocalMinimapTimings(timeline) }
        val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frames += buildJsonObject {
                put("totalNs", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                put("layoutMeasureNs", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                put("drawNs", metrics.getMetric(FrameMetrics.DRAW_DURATION))
                put("droppedMetricCallbacks", dropped)
            }
        }
        val start = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        try {
            record("large-fixture-first-render") {
                state = fixtureState(timeline)
                mountChat()
                waitTag("conversation-minimap")
                waitNative("已检查第 499 项。")
                waitEnd()
                observations["firstRenderAndFollowMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - start)
                assertStableScroll("large-end", requireEnd = true)
            }
            record("large-fixture-seek-and-drag") {
                val scrollStart = SystemClock.elapsedRealtime()
                val seeks = mutableListOf<JsonObject>()
                listOf(.05f, .5f, .9f).forEach { fraction ->
                    val before = scrollValue()
                    val seekStart = SystemClock.elapsedRealtime()
                    compose.onNodeWithTag("conversation-minimap").performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
                    compose.waitUntil(5_000) { kotlin.math.abs(scrollValue() - before) > 1f }
                    settle()
                    assertEquals("Seek should land at the requested loaded fraction", fraction, minimapProgress(), .04f)
                    seeks += buildJsonObject {
                        put("requestedFraction", fraction); put("elapsedMs", SystemClock.elapsedRealtime() - seekStart)
                        put("scrollAxisValue", scrollValue()); put("viewportFraction", minimapProgress()); put("readingHistory", readingHistory)
                    }
                }
                repeat(6) { swipeTranscript(transcript(), towardHistory = false, durationMillis = 250) }
                settle()
                observations["scrollElapsedMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - scrollStart)
                observations["seeks"] = JsonArray(seeks)
                assertTrue(readingHistory)
                assertStableScroll("large-history", requireEnd = false)
                capture("large-transcript")
            }
        } finally {
            instrumentation.runOnMainSync { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            val samples = synchronized(frames) { frames.toList() }
            val totals = samples.map { it.getValue("totalNs").jsonPrimitive.long }.filter { it >= 0 }
                .map { it / 1_000_000.0 }.sorted()
            val refresh = compose.activity.display?.refreshRate?.takeIf { it > 0f }
            observations["frameMetrics"] = buildJsonObject {
                put("frames", samples.size); put("validDurationFrames", totals.size); put("sample", JsonArray(samples.take(12)))
                put("measurementScope", "first-render-follow-minimap-seeks-six-drags")
                put("performanceAcceptanceEvaluated", false)
                if (refresh != null) put("refreshHz", refresh)
                if (totals.isNotEmpty()) {
                    put("p50Ms", percentile(totals, .5)); put("p95Ms", percentile(totals, .95)); put("maxMs", totals.last())
                    if (refresh != null) put("overRefreshBudgetFraction", totals.count { it > 1_000.0 / refresh }.toDouble() / totals.size)
                }
                put("droppedMetricCallbacks", samples.sumOf { it.getValue("droppedMetricCallbacks").jsonPrimitive.int })
            }
        }
        assertTrue("FrameMetrics produced no samples; cannot claim a device measurement",
            observations.getValue("frameMetrics").jsonObject.getValue("validDurationFrames").jsonPrimitive.int > 0)
    }

    private fun observeLocalMinimapTimings(timeline: NativeTimeline) {
        // Run before attaching FrameMetrics: these synchronous local operations measure neither
        // Activity rendering nor per-frame behavior. Projection and proof JSON are outside timing.
        val rows = ConversationPresentation.rows(timeline)
        val keys = listOf("history-control") + rows.map { it.key } + listOf("agent-working", "timeline-end")
        val density = compose.activity.resources.displayMetrics.density
        val heightDp = 600f
        val warmups = 3
        val sampleCount = 20
        fun measure(operation: () -> Int): JsonObject {
            repeat(warmups) { operation() }
            var resultCount = 0
            val times = List(sampleCount) {
                val started = System.nanoTime()
                resultCount = operation()
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            return buildJsonObject {
                put("samplesMs", JsonArray(times.map(::JsonPrimitive)))
                put("p95Ms", percentile(times, .95))
                put("resultCount", resultCount)
            }
        }
        val coldMap = measure {
            ConversationMinimapMap(rows, keys, MinimapSampleCache()).ticks.size
        }
        val cache = MinimapSampleCache()
        val map = ConversationMinimapMap(rows, keys, cache)
        val cachedMap = measure {
            // Keep the same immutable row/item identities and sample cache; rebuild the map.
            ConversationMinimapMap(rows, keys, cache).ticks.size
        }
        val collapsedGeometry = measure {
            minimapMarks(map, null, 24f * density, heightDp * density, density, expanded = false).size
        }
        val expandedGeometry = measure {
            minimapMarks(map, null, 48f * density, heightDp * density, density, expanded = true).size
        }
        observations["minimapLocalTimings"] = buildJsonObject {
            put("measurementScope", "synchronous-local-map-construction-and-mark-geometry")
            put("clock", "System.nanoTime elapsed wall time")
            put("executionThread", "instrumentation-test-thread")
            put("warmupsPerOperation", warmups); put("samplesPerOperation", sampleCount)
            put("rows", rows.size); put("loadedUserRows", rows.count { it.kind == TranscriptKind.User })
            put("lazyKeys", keys.size); put("density", density)
            put("syntheticRailHeightDp", heightDp); put("collapsedWidthDp", 24); put("expandedWidthDp", 48)
            put("viewportAndInspectionApplied", false)
            put("sampleCacheReusesIdenticalItems", true)
            put("performanceAcceptanceEvaluated", false); put("perFrameTracing", false)
            put("freshSampleCacheMapConstruction", coldMap)
            put("reusedSampleCacheMapConstruction", cachedMap)
            put("collapsedMarkGeometry", collapsedGeometry)
            put("expandedMarkGeometry", expandedGeometry)
        }
    }

    private fun growLastAnswer(timeline: NativeTimeline, suffix: String): NativeTimeline {
        val last = timeline.items.values.last { it.type == "agentMessage" }
        return timeline.copy(items = timeline.items + (last.key to last.copy(text = last.text + suffix)))
    }

    private fun transcript() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private fun swipeTranscript(node: SemanticsNodeInteraction, towardHistory: Boolean, durationMillis: Long) {
        node.performTouchInput {
            // The right transcript margin avoids native selectable Markdown and the left rail.
            // Keep both endpoints above the bottom overlay, which is a sibling of the LazyColumn.
            val upper = Offset(width * .95f, height * .15f)
            val lower = Offset(width * .95f, height * .62f)
            swipe(if (towardHistory) upper else lower, if (towardHistory) lower else upper, durationMillis)
        }
    }
    private fun scrollRange() = transcript().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun scrollValue() = scrollRange().value()
    private fun minimapProgress() = compose.onNodeWithTag("conversation-minimap").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun atEnd(): Boolean { val range = scrollRange(); return range.maxValue() - range.value() < .00005f }
    private fun waitEnd() = compose.waitUntil(5_000) { atEnd() }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(350); compose.waitForIdle() }

    private fun assertStableScroll(name: String, requireEnd: Boolean, requireHistory: Boolean = !requireEnd, during: (Int) -> Unit = {}) {
        val start = SystemClock.elapsedRealtime()
        val samples = (0 until 12).map { n ->
            during(n)
            SystemClock.sleep(75)
            buildJsonObject {
                put("elapsedMs", SystemClock.elapsedRealtime() - start)
                put("axis", scrollValue()); put("atEnd", atEnd()); put("readingHistory", readingHistory)
            }
        }
        observations[name] = JsonArray(samples)
        val values = samples.map { it.getValue("axis").jsonPrimitive.float }
        assertTrue("$name scroll bounced: $values", values.max() - values.min() < .00005f)
        if (requireEnd) {
            assertTrue("$name failed to remain at the end", samples.all { it.getValue("atEnd").jsonPrimitive.boolean })
            assertFalse("$name flashed history chrome while following", samples.any { it.getValue("readingHistory").jsonPrimitive.boolean })
        }
        if (requireHistory) {
            assertTrue("$name unexpectedly followed new content", samples.none { it.getValue("atEnd").jsonPrimitive.boolean })
            assertTrue("$name lost the user's history intent", samples.all { it.getValue("readingHistory").jsonPrimitive.boolean })
        }
    }

    private fun listPosition(f: ScrollFixture) = compose.runOnIdle { f.list.firstVisibleItemIndex to f.list.firstVisibleItemScrollOffset }
    private fun listAtEnd(f: ScrollFixture) = compose.runOnIdle { !f.list.canScrollForward && f.list.layoutInfo.totalItemsCount > 0 }
    private fun assertStableList(f: ScrollFixture, name: String) {
        val positions = (0 until 12).map { SystemClock.sleep(75); listPosition(f) }
        observations[name] = buildJsonArray { positions.forEach { (index, offset) -> add(buildJsonObject { put("index", index); put("offsetPx", offset) }) } }
        assertEquals("$name bounced between anchors or offsets: $positions", 1, positions.distinct().size)
    }

    private fun assertMinimapBounds(bottomTag: String) {
        compose.onNodeWithTag("conversation-minimap").assertIsDisplayed()
        val rail = bounds("conversation-minimap")
        val viewport = bounds("v053-viewport")
        val bottom = bounds(bottomTag)
        assertTrue("Minimap stays on the left", rail.right < viewport.center.x)
        assertTrue("Minimap clears the bottom chrome", rail.bottom <= bottom.top + 2f)
        assertTrue(rail.top >= viewport.top - 2f)
        val expanded = compose.onNodeWithTag("conversation-minimap").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription] == "已展开"
        assertTrue("Reserved rail cannot overlap the transcript", rail.right <= bounds("chat-transcript").left + 2f)
        if (expanded) {
            compose.onNodeWithTag("conversation-minimap-thumb", useUnmergedTree = true).assertExists()
            val thumb = bounds("conversation-minimap-thumb")
            assertTrue(thumb.top >= rail.top - 2f && thumb.bottom <= rail.bottom + 2f)
        }
    }

    private fun nativeViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { nativeViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun waitNative(text: String) = compose.waitUntil(5_000) {
        compose.runOnIdle { nativeViews(compose.activity.window.decorView).any { text in it.text.toString() && it.layout != null } }
    }

    private fun assertUserTypography(v: Variant, text: String) {
        val user = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        user.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("User prose must expose real TextLayoutResult", layouts.isNotEmpty())
        assertEquals(16f, layouts.first().layoutInput.style.fontSize.value, .01f)
        assertEquals(26f, layouts.first().layoutInput.style.lineHeight.value, .01f)
        assertEquals(v.fontScale, layouts.first().layoutInput.density.fontScale, .01f)
        assertEquals(PocketReadingFont, layouts.first().layoutInput.style.fontFamily)
        observations["${v.id}-user-typography"] = buildJsonObject {
            put("userFontSp", layouts.first().layoutInput.style.fontSize.value)
            put("userLineHeightSp", layouts.first().layoutInput.style.lineHeight.value)
            put("userLines", layouts.first().lineCount)
        }
    }

    private fun assertNativeTypography(v: Variant) {
        compose.runOnIdle {
            val views = nativeViews(compose.activity.window.decorView)
            val body = views.single { it.text.startsWith("$bodyPrefix，字号") }
            val layout = requireNotNull(body.layout)
            val metrics = body.resources.displayMetrics
            val expectedSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
            assertEquals(v.fontScale, body.resources.configuration.fontScale, .01f)
            assertEquals("Native prose honors the fixture font scale", expectedSize, body.textSize, 1f)
            assertEquals(ReadingTypography.typeface(body.context), body.typeface)
            assertTrue("Native prose has additional line spacing", body.lineSpacingExtra > 0f)
            assertFalse(body.includeFontPadding)
            assertTrue("Wrapped CJK prose should span multiple lines", layout.lineCount >= 2)
            assertTrue("Text is not vertically clipped", layout.height <= body.height - body.totalPaddingTop - body.totalPaddingBottom + 2)
            repeat(layout.lineCount) { line ->
                assertTrue("Body line $line is not horizontally clipped", layout.getLineLeft(line) >= -2f && layout.getLineRight(line) <= layout.width + 2f)
            }
            val headings = listOf("阅读验收", "独立标题").map { title -> views.single { it.text.toString().trim() == title } }
            val positions = headings.map { view -> IntArray(2).also { view.getLocationInWindow(it) }[1] to view.height }
            assertTrue("Native Markdown headings must not overlap", positions[0].first + positions[0].second <= positions[1].first)
            observations["${v.id}-typography"] = buildJsonObject {
                put("fontScale", v.fontScale); put("density", fixtureDensity); put("bodyTextPx", body.textSize)
                put("lineSpacingExtraPx", body.lineSpacingExtra); put("bodyLines", layout.lineCount)
                put("nativeLineStepPx", layout.getLineTop(1) - layout.getLineTop(0))
            }
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double) = sorted[(kotlin.math.ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]

    private fun record(name: String, action: () -> Unit) {
        stage = name
        val start = SystemClock.elapsedRealtime()
        var failure: Throwable? = null
        try { action() } catch (error: Throwable) { failure = error; throw error }
        finally {
            val entry = buildJsonObject {
                put("stage", name); put("passed", failure == null); put("variant", variant.id)
                put("fontScale", variant.fontScale); put("elapsedMs", SystemClock.elapsedRealtime() - start)
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
            stages += entry
            File(directory, "stages.jsonl").appendText("$entry\n")
            instrumentation.sendStatus(0, Bundle().apply { putString("v053ReadingStage", entry.toString()) })
        }
    }

    private fun proof(name: String, action: () -> Unit) {
        directory = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "v053-reading-proof/${System.currentTimeMillis()}-$name")
        check(directory.mkdirs())
        val recording = if (name == "main-reading" && InstrumentationRegistry.getArguments().getString("v053Record") == "true")
            NativeWindowRecording(compose.activity, directory) else null
        val start = SystemClock.elapsedRealtime()
        var failure: Throwable? = null
        try {
            action()
            assertEquals("No connection attempts", 0, connections.get())
            assertEquals("No pairing writes", 0, pairingWrites.get())
        } catch (error: Throwable) {
            failure = error
            runCatching { capture("failure") }
            throw error
        } finally {
            recording?.close()
            val report = buildJsonObject {
                put("schemaVersion", 1); put("fixture", "CodexPocket0.5.3-reading"); put("case", name)
                put("passed", failure == null); put("stage", stage); put("fixtureOnly", true); put("optIn", "v053Reading=true")
                put("repositoryIsolation", "in-memory-unpaired-before-activity-launch")
                put("modelServiceConfigured", false); put("connectionAttempts", connections.get()); put("pairingWrites", pairingWrites.get())
                put("deviceModel", Build.MODEL); put("deviceFingerprint", Build.FINGERPRINT); put("sdk", Build.VERSION.SDK_INT)
                put("physicalDeviceVerified", false); put("systemFontSettingChanged", false)
                put("visualAcceptanceReviewed", false); put("performanceAcceptanceEvaluated", false)
                put("elapsedMs", SystemClock.elapsedRealtime() - start); put("screenshots", JsonArray(screenshots))
                put("stages", JsonArray(stages)); put("observations", JsonObject(observations))
                failure?.let { put("failure", "${it.javaClass.simpleName}: ${it.message}") }
            }
            val file = File(directory, "report.json").apply { writeText(report.toString()) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("v053ReadingProof", directory.absolutePath); putString("v053ReadingReportPath", file.absolutePath)
                putString("v053ReadingReport", report.toString())
            })
        }
    }

    private fun capture(name: String, includeMinimapPreview: Boolean = false) {
        // Six main/plan captures + six light/dark hold/expanded/active-thumb captures + failure headroom.
        check(screenshots.size < 14) { "Screenshot budget exhausted" }
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.invalidate()
            // V052 PixelCopy route: captures native Markdown on the Activity's own display.
            view.postOnAnimation { view.postOnAnimation {
                PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            } }
        }
        try {
            check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "PixelCopy failed at $stage: $result" }
            val previewCapture = if (includeMinimapPreview) compositeMinimapPreview(bitmap) else null
            val file = File(directory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 0)
            screenshots += buildJsonObject {
                put("path", file.absolutePath); put("stage", stage); put("variant", variant.id)
                put("width", bitmap.width); put("height", bitmap.height); put("bytes", file.length())
                previewCapture?.let { put("previewCapture", it) }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("v053ReadingScreenshot", file.absolutePath) })
        } finally {
            if (latch.count == 0L) bitmap.recycle() // Timed-out PixelCopy still owns its destination.
        }
    }

    @OptIn(InternalComposeUiApi::class)
    private fun compositeMinimapPreview(destination: Bitmap): String {
        val node = compose.onNodeWithTag("conversation-minimap-preview", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val previewView = (node.root as ViewRootForTest).view
        val activityRoot = (compose.onNodeWithTag("v053-viewport").fetchSemanticsNode().root as ViewRootForTest).view
        if (previewView === activityRoot) return "activity-window"

        // Popup windows are not part of the Activity PixelCopy. Copy their own surface on the
        // Activity's display and composite at screen coordinates; do not use a default-display shot.
        val latch = CountDownLatch(1)
        var result = -1
        val position = IntArray(2)
        val activityPosition = IntArray(2)
        lateinit var preview: Bitmap
        instrumentation.runOnMainSync {
            check(previewView.isAttachedToWindow && previewView.width > 0 && previewView.height > 0)
            previewView.getLocationOnScreen(position)
            compose.activity.window.decorView.getLocationOnScreen(activityPosition)
            preview = Bitmap.createBitmap(previewView.width, previewView.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= 34) {
                // ofWindow(View) copies the window surface, including shadow insets, by default.
                // Source coordinates are window-relative; PixelCopy adds its surface insets.
                val windowPosition = IntArray(2)
                previewView.getLocationInWindow(windowPosition)
                val source = android.graphics.Rect(windowPosition[0], windowPosition[1],
                    windowPosition[0] + previewView.width, windowPosition[1] + previewView.height)
                val request = PixelCopy.Request.Builder.ofWindow(previewView)
                    .setSourceRect(source).setDestinationBitmap(preview).build()
                PixelCopy.request(request, compose.activity.mainExecutor) { copy -> result = copy.status; latch.countDown() }
            } else {
                // API 28-33 lacks view-window PixelCopy; retain text proof and disclose the draw route.
                previewView.draw(Canvas(preview))
                result = PixelCopy.SUCCESS
                latch.countDown()
            }
        }
        try {
            check(latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Preview copy failed at $stage: $result" }
            Canvas(destination).drawBitmap(preview, (position[0] - activityPosition[0]).toFloat(),
                (position[1] - activityPosition[1]).toFloat(), null)
            return if (Build.VERSION.SDK_INT >= 34) "activity-plus-popup-pixelcopy-cropped" else "activity-plus-popup-view-draw"
        } finally {
            if (latch.count == 0L) preview.recycle()
        }
    }
}
