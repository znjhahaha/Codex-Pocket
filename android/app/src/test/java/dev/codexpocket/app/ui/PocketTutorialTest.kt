package dev.codexpocket.app.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tutorial covers every part of the app, can be walked by tapping or by dragging, and is shown once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PocketTutorialTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var theme by mutableStateOf("light")
    private var finished = 0
    private lateinit var view: android.view.View

    private fun content(block: @Composable () -> Unit = { PocketTutorial({ finished++ }) }) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) { block() }
        } } }
        compose.waitForIdle()
    }
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun pixels(name: String? = null): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        if (name != null) java.io.File("build/reports/tutorial/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
    }
    private fun title() = compose.onNodeWithTag("tutorial-title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text

    @Test fun everyPartOfTheAppHasAChapterWithItsOwnPicture() {
        assertEquals("One scene to a chapter", TourScene.entries.toSet(), PocketTourChapters.map { it.scene }.toSet())
        assertEquals(PocketTourChapters.size, PocketTourChapters.map { it.scene }.distinct().size)
        val all = PocketTourChapters.joinToString(" ") { it.title + it.body + it.points.joinToString { point -> point.text } }
        for (topic in listOf("配对", "执行位置", "SSH", "会话", "模型", "权限", "停止", "待处理", "计划", "通知", "铅笔", "助理", "记忆", "用量", "设置"))
            assertTrue("The tutorial says nothing about $topic", topic in all)
        PocketTourChapters.forEach { assertEquals("${it.title}: three points each", 3, it.points.size) }
    }

    private fun chapterText(scene: TourScene): String = PocketTourChapters.single { it.scene == scene }.let {
        it.title + " " + it.body + " " + it.points.joinToString(" ") { point -> point.text }
    }

    // Guard the misleading claims found in the audit. Button labels are navigation contracts;
    // the remaining wording and chapter detail stay free to be concise.
    @Test fun welcomeDoesNotDescribePhoneDraftsAsSharedDesktopState() {
        assertFalse("Phone drafts are not shared desktop drafts", "会话、草稿和历史都留在原处" in chapterText(TourScene.Welcome))
    }

    @Test fun pairingNamesTheExistingEntryAndKeepsTheFiveMinuteLifetime() {
        val text = chapterText(TourScene.Pair)
        assertTrue("Name the existing desktop entry", "添加设备" in text)
        assertTrue("Pairing codes expire after five minutes", "5 分钟" in text)
    }

    @Test fun sendingNamesRunningActionsWithoutReplacingQueueWithStop() {
        val text = chapterText(TourScene.Send)
        assertTrue("Name the current-turn action", compose.activity.getString(R.string.v02_steer) in text)
        assertTrue("Name the next-turn action", compose.activity.getString(R.string.v02_queue) in text)
        assertFalse("Running turns keep a queue action alongside stop", "发送键变成停止" in text)
    }

    @Test fun approvalCopyDoesNotPromiseEveryOperationAndEveryDecision() {
        val text = chapterText(TourScene.Approve)
        assertFalse("Command and file operations do not always prompt", "要执行命令、修改文件或向你提问时，会停下来等你" in text)
        assertFalse("Session approval is only present when offered by the request", "允许这一次、本次会话都允许，或者拒绝" in text)
    }

    @Test fun usageDoesNotPresentRecordedUsageAsRemainingQuota() {
        assertFalse("Observed usage is not the account's remaining quota", "查看用量与额度" in chapterText(TourScene.Usage))
    }

    @Test fun versionTwoIsShownAfterVersionOneAndCompletionIsRemembered() {
        assertEquals("The corrected behavior guidance must be shown again", 2, PocketTutorialController.Version)
        val context: Context = compose.activity.applicationContext
        val store = context.getSharedPreferences(PocketTutorialController.Store, Context.MODE_PRIVATE)
        store.edit().putInt(PocketTutorialController.Seen, 1).commit()
        fun controller() = PocketTutorialController(context,
            first = store.getInt(PocketTutorialController.Seen, 0) < PocketTutorialController.Version)
        val updated = controller()
        assertTrue("Version 1 readers should see the revised tutorial", updated.open)
        updated.finish()
        assertEquals(2, store.getInt(PocketTutorialController.Seen, 0))
        val nextVisit = controller()
        assertFalse("Completing Version 2 should suppress automatic reopening", nextVisit.open)
        nextVisit.show()
        assertTrue("The revised tutorial can still be opened from settings", nextVisit.open)
    }

    @Test fun wordsThatHaveComeInStayStillWhenTheScreenIsBuiltAgain() {
        val controller = PocketTutorialController(null, true)
        var build by mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        content { key(build) { PocketTutorial({ finished++ }, controller = controller) } }
        advance(1600)
        compose.onNodeWithTag("tutorial-next").performClick()
        advance(1600)
        val second = title()
        val settled = pixels()
        assertEquals(setOf(0, 1), controller.entered.toSet())
        // Whatever rebuilds the screen under the tutorial, the reader stays on the chapter and its words do not rise again.
        compose.runOnIdle { build++ }
        compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
        assertEquals(second, title())
        advance(64)
        val rebuilt = pixels()
        val words = compose.onNodeWithTag("tutorial-title").fetchSemanticsNode().boundsInRoot
        val width = view.width
        val changed = (words.top.toInt() until words.bottom.toInt()).sumOf { y -> (0 until width).count { x -> settled[y * width + x] != rebuilt[y * width + x] } }
        assertEquals("The title moved again after it had already come in", 0, changed)
        controller.show()
        assertTrue("Asked for again, it starts over", controller.entered.isEmpty() && controller.chapter == 0)
    }

    @Test fun tappingNextWalksEveryChapterAndTheLastOneStarts() {
        content()
        compose.mainClock.autoAdvance = false
        advance(1800)
        val seen = mutableListOf<String>()
        PocketTourChapters.forEachIndexed { index, chapter ->
            assertEquals(chapter.title, title()); seen += title()
            compose.onNodeWithTag("tutorial-scene:${chapter.scene.name}").assertExists()
            pixels("chapter-${index + 1}-${chapter.scene.name.lowercase()}")
            if (index < PocketTourChapters.lastIndex) {
                compose.onNodeWithTag("tutorial-next").assertTextEquals("下一步").performClick()
                advance(900)
            }
        }
        assertEquals(PocketTourChapters.size, seen.distinct().size)
        compose.onNodeWithTag("tutorial-skip").assertIsNotEnabled()
        assertEquals(0, finished)
        compose.onNodeWithTag("tutorial-next").assertTextEquals("开始使用").performClick()
        advance(100)
        assertEquals(1, finished)
    }

    @Test fun pagesFollowAFingerAndBackStepsToThePreviousChapterThenLeaves() {
        content()
        compose.mainClock.autoAdvance = false
        advance(400)
        compose.onNodeWithTag("tutorial-back").assertIsNotEnabled()
        compose.onNodeWithTag("tutorial-pager").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-width * .3f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-width * .25f, 0f)) }
        advance(32)
        compose.onNodeWithTag("pager-page:0").assertExists(); compose.onNodeWithTag("pager-page:1").assertExists()
        pixels("drag-between-1-and-2")
        compose.onNodeWithTag("tutorial-pager").performTouchInput { up() }
        advance(900)
        assertEquals(PocketTourChapters[1].title, title())
        compose.onNodeWithTag("pager-page:0").assertDoesNotExist()
        compose.onNodeWithTag("tutorial-back").assertIsEnabled().performClick()
        advance(900)
        assertEquals(PocketTourChapters[0].title, title())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(100)
        assertEquals("Back on the first chapter leaves the tutorial", 1, finished)
        compose.onNodeWithTag("tutorial-skip").performClick()
        advance(100)
        assertEquals(2, finished)
    }

    @Test fun withMotionOffEveryChapterIsAFinishedStillAndDarkThemeDrawsToo() {
        preferences = PocketGlassPreferences(false, false, false); theme = "dark"
        content()
        compose.mainClock.autoAdvance = false
        advance(64)
        PocketTourChapters.forEachIndexed { index, chapter ->
            val still = pixels("still-dark-${index + 1}-${chapter.scene.name.lowercase()}")
            advance(700)
            assertArrayEquals("${chapter.title} moves with motion off", still, pixels())
            if (index < PocketTourChapters.lastIndex) { compose.onNodeWithTag("tutorial-next").performClick(); advance(32); assertEquals(PocketTourChapters[index + 1].title, title()) }
        }
    }

    @Test fun itOpensOnceByItselfIsRememberedAndCanBeAskedForAgain() {
        val context: Context = compose.activity.applicationContext
        val store = context.getSharedPreferences(PocketTutorialController.Store, Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val first = PocketTutorialController(context, first = true)
        content { PocketTutorialHost(first) { PText("应用内容", Modifier.testTag("app")) } }
        compose.onNodeWithTag("tutorial").assertExists()
        compose.onNodeWithTag("app").assertDoesNotExist()
        compose.onNodeWithTag("tutorial-skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tutorial").assertDoesNotExist()
        compose.onNodeWithTag("app").assertExists()
        assertEquals(PocketTutorialController.Version, store.getInt(PocketTutorialController.Seen, 0))
        compose.runOnIdle { first.show() }
        compose.onNodeWithTag("tutorial").assertExists()
        assertEquals(PocketTourChapters[0].title, title())
    }
}
