package dev.codexpocket.app

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Render-only sample: 1,000 messages and 200 tools; no turns are sent to any runtime. */
@RunWith(AndroidJUnit4::class)
class V05PerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun localTranscriptSample() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v05Performance") == "true")
        val items = linkedMapOf<String, TimelineItem>()
        val turns = linkedMapOf<String, NativeTurnInfo>()
        repeat(500) { n ->
            val id = "sample-$n"
            turns[id] = NativeTurnInfo(id, status = "completed", durationMs = 1_000)
            fun add(item: TimelineItem) { items[item.key] = item }
            add(TimelineItem(id, "user", "userMessage", "检查项目文件 $n，保留中文与代码阅读。", complete = true))
            if (n < 200) add(TimelineItem.from(id, buildJsonObject {
                put("id", "tool"); put("type", "commandExecution"); put("command", "git diff --stat")
                put("status", "completed"); put("exitCode", 0)
            }))
            add(TimelineItem(id, "final", "agentMessage", "已检查第 $n 项。\n\n保留现有配对、草稿和工作区文件。", complete = true))
        }
        val timeline = NativeTimeline(items, completedTurns = turns.keys, turns = turns)
        val projectionTimes = (0..20).map {
            val start = System.nanoTime()
            val rows = ConversationPresentation.rows(timeline)
            check(rows.flatMap { row -> row.items }.size == 1200)
            (System.nanoTime() - start) / 1_000_000.0
        }.drop(1).sorted()
        val rows = ConversationPresentation.rows(timeline)
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { CompositionLocalProvider(LocalPocketGlass provides false) {
                Surface { ProvideMarkdownRendering {
                    LazyColumn(Modifier.fillMaxSize().testTag("sample-list")) {
                        items(rows, key = { it.key }, contentType = { it.kind }) { row ->
                            Box(Modifier.testTag("sample-row:${row.key}")) { TranscriptRowView(row, "sample", false, {}, {}) }
                        }
                    }
                } }
            } }
        } }
        compose.waitForIdle()
        val frameTimes = Collections.synchronizedList(mutableListOf<Long>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frameTimes += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
        compose.activity.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        try {
            repeat(12) { compose.onNodeWithTag("sample-list").performTouchInput { swipeUp(durationMillis = 250) } }
            compose.onNodeWithTag("sample-list").performScrollToIndex(rows.lastIndex)
            compose.waitForIdle()
            compose.onNodeWithTag("sample-row:${rows.last().key}").assertIsDisplayed()
        } finally { compose.activity.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) } }
        val samples = synchronized(frameTimes) { frameTimes.toList() }
        val refreshRate = compose.activity.display?.refreshRate?.takeIf { it > 0 } ?: 60f
        val budget = 1_000_000_000.0 / refreshRate
        writeLiveReport("performance-report.json", buildJsonObject {
            put("passed", true); put("fixtureOnly", true); put("modelRequests", 0)
            put("messages", 1000); put("tools", 200); put("renderedRows", rows.size)
            put("projectionP95Ms", projectionTimes[(projectionTimes.size * .95).toInt().coerceAtMost(projectionTimes.lastIndex)])
            put("frames", samples.size); put("refreshHz", refreshRate)
            if (samples.isNotEmpty()) put("overBudgetFraction", samples.count { it > budget }.toDouble() / samples.size)
            put("cacheSwitchMeasured", false); put("physicalDevice", false)
        })
    }
}
