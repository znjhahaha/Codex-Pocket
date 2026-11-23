package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketBackdrop
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketIcons
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layers arrive from where they belong: a sheet from below the screen, a menu out of its control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitOverlayMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private var sheet by mutableStateOf(false)
    private var menu by mutableStateOf(false)

    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, true), LocalPocketGlass provides false) {
                PocketBackdrop {
                    val anchor = rememberPAnchor()
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(6) { PText("页面内容第 $it 行") } }
                        PButton("更多", { menu = true }, Modifier.align(Alignment.TopEnd).pocketAnchor(anchor).testTag("more"))
                    }
                    if (sheet) PSheet("会话操作", { sheet = false }, "D:/Projects/Pocket") {
                        PRow("置顶", {}, icon = PocketIcons.PushPin)
                        PRow("归档", {}, icon = PocketIcons.Inventory2)
                        PRow("删除", {}, icon = PocketIcons.DeleteOutline, destructive = true)
                    }
                    if (menu) PMenu("更多", { menu = false }, anchor) {
                        PRow("重命名", {}, icon = PocketIcons.Edit)
                        PRow("复制链接", {}, icon = PocketIcons.Link)
                        PRow("项目文件", {}, icon = PocketIcons.FolderOpen)
                    }
                }
            }
        } } }
        compose.waitForIdle()
    }
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun surface(): Rect = compose.onNodeWithTag("pocket-modal-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun aSheetRisesFromBelowTheScreenAndLeavesTheSameWay() {
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { sheet = true }
        advance(96)
        val rising = surface()
        capture("film-sheet-096")
        advance(96)
        val higher = surface()
        capture("film-sheet-192")
        assertTrue("It starts below where it will rest and climbs: ${rising.top} then ${higher.top}", higher.top < rising.top)
        advance(1200)
        val rest = surface()
        capture("film-sheet-rest")
        assertTrue(rest.top <= higher.top && rest.bottom <= view.height)
        assertTrue("Early on, part of it was still off the bottom of the screen", rising.bottom > rest.bottom + 40f)
        compose.runOnIdle { sheet = false }
        advance(96)
        assertTrue("Leaving, it sinks rather than fading in place", surface().top > rest.top + 20f)
        advance(600)
        compose.onNodeWithTag("pocket-modal-surface", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aMenuGrowsOutOfTheControlThatOpenedIt() {
        content()
        val button = compose.onNodeWithTag("more").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("more").performClick()
        advance(80)
        val growing = surface()
        capture("film-menu-080")
        advance(1200)
        val rest = surface()
        capture("film-menu-rest")
        assertTrue("It begins nearer the control's own size: ${growing.width} of ${rest.width}", growing.width < rest.width - 8f && growing.height < rest.height - 8f)
        assertEquals("and stays attached to the control's trailing edge as it grows", rest.right, growing.right, 2f)
        assertEquals(rest.top, growing.top, 2f)
        assertTrue("It opens beside the control, not somewhere else", rest.top >= button.top && rest.right <= button.right + 2f)
    }
}
