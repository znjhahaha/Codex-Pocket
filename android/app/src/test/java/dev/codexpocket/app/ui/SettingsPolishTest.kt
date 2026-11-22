package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketHaptics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings, project creation and the model menu: kit press feedback and moving glyphs over unchanged geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private val haptics = mutableListOf<PocketHaptic>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun content(block: @Composable () -> Unit) {
        compose.activity.getSharedPreferences("settings", 0).edit().clear().commit()
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false,
                LocalPocketHaptics provides PocketHaptics { haptics += it }) { PocketBackdrop { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun pixels(b: Rect): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val width = b.right.toInt() - b.left.toInt(); val height = b.bottom.toInt() - b.top.toInt()
        IntArray(width * height).also { bitmap.getPixels(it, 0, width, b.left.toInt(), b.top.toInt(), width, height); bitmap.recycle() }
    }
    /** Antialiasing may round an edge differently between renderers; a missing or misplaced stroke is far larger than this. */
    private fun sameShape(message: String, expected: IntArray, actual: IntArray) {
        val changed = expected.indices.count { i -> (0..24 step 8).any { kotlin.math.abs(((expected[i] ushr it) and 255) - ((actual[i] ushr it) and 255)) > 64 } }
        assertTrue("$message ($changed of ${expected.size} pixels differ)", changed <= expected.size / 400)
    }
    private fun advance(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

    @Test fun settingsThumbStretchesInFlightRestsOnThePageAndTabsStaySilent() {
        content { SettingsPageLayout(SettingsPage.Connection) { page -> Text(page.name) } }
        val tabs = SettingsPage.entries.map { bounds("settings-tab:${it.name}") }
        tabs.forEach { assertTrue("48dp minimum", it.height >= 48 * 3f) }
        assertEquals(tabs[0], bounds("settings-selected-thumb"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("settings-tab:${SettingsPage.entries.last().name}").performClick()
        advance(96)
        val moving = bounds("settings-selected-thumb")
        assertTrue("The thumb stretches toward its target: ${moving.width}", moving.width > tabs[0].width + 12f)
        assertEquals(tabs[0].height, moving.height, .01f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(tabs.last(), bounds("settings-selected-thumb"))
        compose.onNodeWithTag("settings-tab:${SettingsPage.entries.last().name}").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        assertEquals("Tabs keep their boxes", tabs, SettingsPage.entries.map { bounds("settings-tab:${it.name}") })

        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("settings-tab:${SettingsPage.Connection.name}").performClick()
        advance(32)
        assertEquals("Reduced motion moves the thumb at once", tabs[0], bounds("settings-selected-thumb"))
        assertTrue("Changing page is not one of the felt actions", haptics.isEmpty())
    }

    @Test fun refreshRowTurnsOncePerTapInsideTheStaticIconsBox() {
        var static by mutableStateOf(true)
        var turns by mutableIntStateOf(0)
        var taps = 0
        content {
            Box(Modifier.testTag("row")) {
                if (static) PocketActionRow("重新连接", PocketIcons.Refresh, { taps++ })
                else PocketActionRow("重新连接", PocketIcons.Refresh, { turns++; taps++ }, glyph = { PocketRefreshGlyph(turns) })
            }
        }
        val row = bounds("row")
        assertTrue("50dp minimum row", row.height >= 50 * 3f)
        val icon = pixels(row)
        compose.runOnIdle { static = false }
        compose.waitForIdle()
        assertEquals("The glyph takes the icon's place without moving the row", row, bounds("row"))
        val rest = pixels(row)
        sameShape("At rest the glyph is the refresh icon", icon, rest)
        compose.mainClock.autoAdvance = false
        advance(700)
        assertArrayEquals("Nothing turns until it is tapped", rest, pixels(row))
        compose.onNodeWithText("重新连接").performClick()
        advance(160)
        assertEquals(1, taps)
        assertFalse("A tap turns the arrows", rest.contentEquals(pixels(row)))
        advance(1200)
        assertArrayEquals("One turn, then still", rest, pixels(row))
        compose.runOnIdle { preferences = PocketGlassPreferences(false, true, true) }
        compose.onNodeWithText("重新连接").performClick()
        advance(160)
        assertArrayEquals("Battery saver keeps the glyph still", rest, pixels(row))
        assertEquals(2, taps)
    }

    @Test fun modelRowsUseKitPressFeedbackWithTheSameTargetAndSemantics() {
        val model = NativeModel("id", "codex", "Cached default", "", emptyList(), "", false, true)
        var selections = 0
        content { ModelSheet(PocketState(online = true, models = listOf(model), modelCatalogState = ModelCatalogState.Ready), { _, _ -> selections++ }, {}, {}) }
        val row = bounds("model-row-id")
        assertTrue("48dp minimum", row.height >= 48 * 3f)
        compose.onNodeWithTag("model-row-id").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val rest = pixels(row)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("model-row-id").performTouchInput { down(center) }
        advance(200)
        assertFalse("The row answers the touch", rest.contentEquals(pixels(row)))
        assertEquals("Pressing never changes the hit target", row, bounds("model-row-id"))
        compose.onNodeWithTag("model-row-id").performTouchInput { up() }
        advance(600)
        assertEquals(1, selections)
        assertTrue(haptics.isEmpty())
    }

    @Test fun directorySwitchKeepsItsTabsAndSelectsOnceWithoutHaptics() {
        var newDirectory by mutableStateOf(false)
        var changes = 0
        content { CreationDirectorySwitch(newDirectory, true) { newDirectory = it; changes++ } }
        compose.onNodeWithText("已有目录").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("新建目录").assertIsNotSelected().performClick()
        compose.onNodeWithText("新建目录").assertIsSelected()
        compose.runOnIdle { assertEquals(1, changes); assertTrue(haptics.isEmpty()) }
    }

    @Test fun submitSpinnerTurnsOnlyWhileCreating() {
        var state by mutableStateOf(PocketState(online = true, computerId = "pc", computerName = "工作电脑",
            runtimes = listOf(RuntimeInfo("desktop", "desktop", "0.170.0", "store", true, emptySet(), setOf("thread/start"))), capabilities = setOf("thread/start")))
        content { NewChatDialog(state, {}) { _, _, _, _ -> } }
        compose.mainClock.autoAdvance = false
        val idle = pixels(bounds("project-submit"))
        advance(400)
        assertArrayEquals("The arrow is still", idle, pixels(bounds("project-submit")))
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(loading = true, phase = "正在创建"))) }
        advance(300)
        val first = pixels(bounds("project-submit"))
        advance(200)
        assertFalse("Work in flight turns the spinner", first.contentEquals(pixels(bounds("project-submit"))))
        compose.runOnIdle { state = state.copy(actionStatus = emptyMap()) }
        advance(700)
        val done = pixels(bounds("project-submit"))
        advance(400)
        assertArrayEquals("It stops with the work", done, pixels(bounds("project-submit")))

        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(loading = true, phase = "正在创建"))) }
        advance(300)
        val still = pixels(bounds("project-submit"))
        advance(300)
        assertArrayEquals("Reduced motion shows a still loader", still, pixels(bounds("project-submit")))
    }
}
