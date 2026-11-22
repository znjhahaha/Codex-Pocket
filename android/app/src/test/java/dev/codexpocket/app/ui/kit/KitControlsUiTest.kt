package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val haptics = mutableListOf<PocketHaptic>()
    private lateinit var view: android.view.View

    private fun content(theme: String = "light", fontScale: Float = 1f, motion: Boolean = true, block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketHaptics provides PocketHaptics { haptics += it },
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion),
                LocalDensity provides Density(density.density, fontScale)) { PocketOverlayHost { block() } }
        } } }
        compose.waitForIdle()
    }
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun buttonActsOnceWithItsHapticAndCannotBePressedWhileBusyOrDisabled() {
        var clicks = 0
        var busy by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        content { Column {
            PButton("合入主分支", { clicks++ }, Modifier.testTag("merge"), enabled = enabled, busy = busy, haptic = PocketHaptic.Confirm)
            PIconButton(PocketIcons.Refresh, "刷新", { clicks += 10 }, haptic = PocketHaptic.Tap)
        } }
        compose.onNodeWithTag("merge").assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(listOf(PocketHaptic.Confirm), haptics) }
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("merge").assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "进行中")).performClick()
        compose.runOnIdle { busy = false; enabled = false }
        compose.onNodeWithTag("merge").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, haptics.size) }
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.runOnIdle { assertEquals(11, clicks); assertEquals(PocketHaptic.Tap, haptics.last()) }
    }

    private fun pixels(): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
    }

    @Test fun pressShowsWhileHeldAndAQuickTapStillShowsThenReturnsToRest() {
        var clicks = 0
        var motion by mutableStateOf(true)
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketHaptics provides PocketHaptics { haptics += it },
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, motion)) {
                Box(Modifier.padding(24.dp)) { PButton("交办", { clicks++ }, Modifier.testTag("press")) }
            }
        } } }
        compose.waitForIdle()
        val rest = pixels()
        compose.mainClock.autoAdvance = false
        fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
        val button = compose.onNodeWithTag("press")
        button.performTouchInput { down(center) }
        advance(500)
        val held = pixels()
        assertFalse("A held press is visible", rest.contentEquals(held))
        // The whole control settles in, not only its label: the fill leaves the outer edge.
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val edge = bounds.center.y.toInt() * view.width + bounds.left.toInt() + 1
        assertNotEquals("The fill reaches the edge at rest and draws back when pressed", rest[edge], held[edge])
        advance(400)
        assertArrayEquals("A held press stays put", held, pixels())
        button.performTouchInput { up() }
        advance(600)
        assertArrayEquals("Releasing returns to rest", rest, pixels())
        // Down and up within one frame: the press must still show, and must not stay stuck part-way.
        button.performTouchInput { click() }
        advance(48)
        assertFalse("A quick tap still shows its press", rest.contentEquals(pixels()))
        advance(700)
        assertArrayEquals("A quick tap ends at rest", rest, pixels())
        compose.runOnIdle { assertEquals(2, clicks); motion = false }
        // Let the changed preference reach the control before freezing the clock again.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        advance(48)
        val snapped = pixels()
        assertArrayEquals("Without motion the press is the full depth at once", held, snapped)
        button.performTouchInput { up() }
        advance(48)
        assertArrayEquals("Without motion release is immediate", rest, pixels())
    }

    @Test fun togglesAndChoicesExposeTheirRolesStatesAndHaptics() {
        var on by mutableStateOf(false)
        var checked by mutableStateOf(false)
        var segment by mutableIntStateOf(0)
        var pill by mutableStateOf("7")
        content { Column {
            PSwitch(on, { on = it }, Modifier.testTag("switch"))
            PCheck(checked, { checked = it }, Modifier.testTag("check"))
            PSegmented(listOf("上游", "模型", "对话"), segment, { segment = it }, Modifier.testTag("segments"))
            Row { listOf("1", "7", "30").forEach { PPill("$it 天", pill == it, { pill = it }, Modifier.testTag("pill:$it")) } }
        } }
        compose.onNodeWithTag("switch").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick().assertIsOn()
        compose.onNodeWithTag("check").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(PocketHaptic.ToggleOn, PocketHaptic.ToggleOn), haptics); haptics.clear() }
        compose.onNodeWithText("上游").assertIsSelected()
        compose.onNodeWithText("模型").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("模型").performClick()
        compose.runOnIdle { assertEquals(1, segment); assertEquals("Reselecting gives no second tick", listOf(PocketHaptic.Tick), haptics) }
        compose.onNodeWithTag("pill:30").performClick().assertIsSelected()
        compose.onNodeWithTag("pill:7").assertIsNotSelected()
    }

    @Test fun textFieldKeepsItsLabelAndReportsInput() {
        var text by mutableStateOf("")
        content { PTextField(text, { text = it }, Modifier.testTag("field"), label = "任务标题", placeholder = "交办新目标") }
        compose.onNodeWithText("任务标题").assertIsDisplayed()
        compose.onNodeWithText("交办新目标").assertIsDisplayed()
        compose.onNodeWithTag("field").performTextInput("修复登录超时")
        compose.runOnIdle { assertEquals("修复登录超时", text) }
        compose.onNodeWithText("交办新目标").assertDoesNotExist()
        compose.onNodeWithText("任务标题").assertIsDisplayed()
    }

    @Test fun typingInADialogOpenedFromAPageKeepsItsOrderAndCaret() {
        // A page is itself an overlay measured in a subcomposition, so its dialog hears of each
        // keystroke one frame late. The field must not take that stale text for an edit.
        var name by mutableStateOf("")
        content {
            PocketModal("配对", {}, sheet = false, screen = true) {
                PText("页面")
                PocketDialog({}, title = { PText("连接电脑") }, text = {
                    PTextField(name, { name = it.take(8) }, Modifier.testTag("name"), label = "本机名称", singleLine = true)
                }, confirmButton = { PButton("连接", {}, enabled = name.isNotBlank()) })
            }
        }
        val field = compose.onNodeWithTag("name")
        field.performClick()
        "MuMu-QA".forEach { field.performTextInput(it.toString()) }
        compose.runOnIdle { assertEquals("MuMu-QA", name) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(7)))
        // What the caller refuses does not stay in the field.
        field.performTextInput("12")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("MuMu-QA1", name) }
        field.assertTextEquals("本机名称", "MuMu-QA1")
        // A value the caller sets on its own replaces the text.
        compose.runOnIdle { name = "BRA" }
        field.assertTextEquals("本机名称", "BRA")
        field.performTextInput("x")
        compose.runOnIdle { assertEquals("BRAx", name) }
    }

    @Test fun sheetDialogAndMenuOpenInsideTheAppWindowAndDismiss() {
        var sheet by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var menu by mutableStateOf(false)
        var confirmed = 0
        var chosen = ""
        content {
            val anchor = rememberPAnchor()
            Column {
                PButton("更多", { menu = true }, Modifier.pocketAnchor(anchor).testTag("open-menu"))
                PText("正文锚点")
            }
            if (sheet) PSheet("待你拍板", { sheet = false }, subtitle = "2 项") { PText("审批写入 src/login.rs", Modifier.padding(20.dp)) }
            if (dialog) PDialog("丢弃分支？", { dialog = false }, "丢弃", { confirmed++; dialog = false }, text = "分支上的改动不会合入。", destructive = true)
            if (menu) PMenu("更多", { menu = false }, anchor) {
                PRow("记忆", { chosen = "memory"; menu = false }, icon = PocketIcons.Lightbulb)
                PRow("助理设置", { chosen = "settings"; menu = false }, icon = PocketIcons.Settings)
            }
        }
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithText("助理设置").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("settings", chosen); assertFalse(menu) }
        compose.runOnIdle { sheet = true }
        compose.onNodeWithText("待你拍板").assertIsDisplayed()
        compose.onNodeWithText("审批写入 src/login.rs").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.runOnIdle { assertFalse(sheet) }
        compose.runOnIdle { dialog = true }
        compose.onNodeWithText("分支上的改动不会合入。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(dialog); assertEquals(0, confirmed); dialog = true }
        compose.onNodeWithText("丢弃").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(PocketHaptic.Reject, haptics.last()) }
    }

    @Test fun controlsKeepFortyEightDpTargetsAndFitNarrowDarkLargeTextWithoutMotion() {
        content("dark", fontScale = 1.4f, motion = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PText("口袋助理", style = Pocket.type.titleLarge)
                PCard { PBadge("等你回答", tone = PTone.Warning); PText("整理行业动态并生成简报"); PLinearProgress(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton("回答问题", {}, Modifier.weight(1f).testTag("target:primary"))
                    PButton("查看任务", {}, Modifier.weight(1f).testTag("target:secondary"), kind = PButtonKind.Secondary)
                }
                PSegmented(listOf("上游", "模型", "对话"), 1, {}, Modifier.testTag("target:segments"))
                Row { PSwitch(true, {}, Modifier.testTag("target:switch")); PCheck(true, {}, Modifier.testTag("target:check"))
                    PIconButton(PocketIcons.Refresh, "刷新", {}, Modifier.testTag("target:icon")) }
                PTextAction("查看过程 / Diff", {}, Modifier.testTag("target:text"))
                PPill("近 7 天", true, {}, Modifier.testTag("target:pill"))
                PTextField("", {}, Modifier.testTag("target:field"), placeholder = "交办新目标，或继续聊")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { PSpinner(); PDots(); PRing(.6f, Modifier.size(18.dp)) }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        compose.onAllNodes(SemanticsMatcher("has target tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("target:") == true })
            .fetchSemanticsNodes().also { assertEquals(9, it.size) }.forEach { node ->
                val bounds = node.boundsInRoot
                val tag = node.config[SemanticsProperties.TestTag]
                assertTrue("$tag is ${bounds.height / density}dp tall", bounds.height / density >= 47.9f)
                assertTrue("$tag overflows the screen", bounds.left >= root.left && bounds.right <= root.right)
            }
        // Both buttons keep their whole label on one row at 1.4x in 360dp.
        val primary = compose.onNodeWithTag("target:primary").fetchSemanticsNode().boundsInRoot
        val secondary = compose.onNodeWithTag("target:secondary").fetchSemanticsNode().boundsInRoot
        assertTrue(primary.right <= secondary.left)
        assertEquals(primary.top, secondary.top)
        capture("controls-dark-large")
    }
}
