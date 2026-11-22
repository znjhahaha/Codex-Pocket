package dev.codexpocket.app.ui

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
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.MotionIcon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home's motion polish: it moves between the same resting geometry as before, and stands still when motion is off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) { block() }
        } } }
        compose.waitForIdle()
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun region(tag: String): IntArray {
        val b = bounds(tag)
        return compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val width = b.right.toInt() - b.left.toInt(); val height = b.bottom.toInt() - b.top.toInt()
            IntArray(width * height).also { bitmap.getPixels(it, 0, width, b.left.toInt(), b.top.toInt(), width, height); bitmap.recycle() }
        }
    }
    /** Antialiasing may round an edge differently between renderers; a missing or misplaced stroke is far larger than this. */
    private fun sameShape(message: String, expected: IntArray, actual: IntArray) {
        val changed = expected.indices.count { kotlin.math.abs((expected[it] ushr 24) - (actual[it] ushr 24)) > 64 }
        assertTrue("$message ($changed of ${expected.size} pixels differ)", changed <= expected.size / 200)
    }
    /** One frame so a changed target is seen, then exactly [ms] of animation. */
    private fun advance(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

    private val base = PocketPreviewStates.chats.first()
    private fun chat(id: String, cwd: String) = base.copy(key = ThreadKey("polish-pc", "polish-store", id), title = "会话 $id",
        cwd = cwd, pinned = false, archived = false, status = "completed")

    @Test fun navigationSliderStretchesInFlightAndRestsExactlyOnTheSelectedTab() {
        var tab by mutableStateOf(HomeTab.Chats)
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PocketHomeNavigation(tab, 0, onSelect = { tab = it }) } }
        // 360dp - 18dp - 88dp of row padding - 2 x 6dp inside the dock = 242dp, a third each, at 3 px per dp.
        val item = 242f
        HomeTab.entries.forEachIndexed { index, entry ->
            val b = bounds("home-tab-${entry.name.lowercase()}")
            assertEquals(item, b.width, .01f); assertTrue("58dp minimum height", b.height >= 58 * 3f)
            assertEquals((18 + 6) * 3f + index * item, b.left, .01f)
        }
        assertEquals(bounds("home-tab-chats"), bounds("home-tab-selection"))
        val tabs = HomeTab.entries.map { bounds("home-tab-${it.name.lowercase()}") }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("home-tab-devices").performClick()
        advance(96)
        val moving = bounds("home-tab-selection")
        assertTrue("The leading edge runs ahead of the trailing one: ${moving.width}", moving.width > item + 12f)
        assertTrue(moving.left > tabs[0].left && moving.right < tabs[2].right + 12f)
        assertEquals("Height never changes", tabs[0].height, moving.height, .01f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(bounds("home-tab-devices"), bounds("home-tab-selection"))
        assertEquals("Hit targets hold while the pill moves", tabs, HomeTab.entries.map { bounds("home-tab-${it.name.lowercase()}") })

        // Back the other way the same edge trails, so the pill never inverts.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("home-tab-chats").performClick()
        advance(96)
        assertTrue(bounds("home-tab-selection").width > item + 12f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(bounds("home-tab-chats"), bounds("home-tab-selection"))
    }

    @Test fun reducedMotionMovesTheSliderAndGlyphsAtOnce() {
        preferences = PocketGlassPreferences(false, false, false)
        var tab by mutableStateOf(HomeTab.Attention)
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PocketHomeNavigation(tab, 0, onSelect = { tab = it }) } }
        val rest = region("home-tab-attention")
        compose.runOnIdle { tab = HomeTab.Chats }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("home-tab-attention").performClick()
        advance(32)
        assertEquals(bounds("home-tab-attention"), bounds("home-tab-selection"))
        advance(700)
        assertArrayEquals("The tab ends exactly as it is drawn at rest", rest, region("home-tab-attention"))
        // Battery saver is the same rule.
        compose.runOnIdle { preferences = PocketGlassPreferences(false, true, true) }
        compose.onNodeWithTag("home-tab-devices").performClick()
        advance(32)
        assertEquals(bounds("home-tab-devices"), bounds("home-tab-selection"))
    }

    @Test fun enteringATabPlaysItsGlyphOnceAndReturnsToTheStaticPose() {
        var tab by mutableStateOf(HomeTab.Attention)
        content { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PocketHomeNavigation(tab, 0, onSelect = { tab = it }) } }
        // Composed already selected: nothing has played, so this is the resting picture.
        val rest = region("home-navigation")
        compose.runOnIdle { tab = HomeTab.Chats }
        compose.waitForIdle()
        val chats = region("home-navigation")
        compose.mainClock.autoAdvance = false
        advance(900)
        assertArrayEquals("Leaving a tab leaves nothing moving", chats, region("home-navigation"))
        compose.onNodeWithTag("home-tab-attention").performClick()
        advance(1500)
        val settled = region("home-navigation")
        advance(600)
        assertArrayEquals("The bell rings once", settled, region("home-navigation"))
        assertArrayEquals("And ends as it would have been drawn at rest", rest, settled)
    }

    @Test fun homeGlyphsRestInThePoseOfTheIconsTheyReplace() {
        var t by mutableFloatStateOf(0f)
        var static by mutableStateOf(false)
        val glyphs = listOf(HomeGlyphs.Chat to PocketIcons.ChatBubbleOutline, HomeGlyphs.Devices to PocketIcons.Devices,
            HomeGlyphs.tab(HomeTab.Attention) to PocketIcons.NotificationsNone)
        content {
            Column {
                // The same boxes show either the moving glyph or the icon it replaced, so pixels compare in place.
                Row(Modifier.testTag("rest")) { glyphs.forEach { (glyph, icon) ->
                    if (static) androidx.compose.material3.Icon(icon, null, Modifier.size(42.dp)) else MotionIcon(glyph, { t }, Modifier.size(42.dp))
                } }
                Box(Modifier.testTag("search")) {
                    if (static) androidx.compose.material3.Icon(if (t < .5f) PocketIcons.Search else PocketIcons.Close, null, Modifier.size(42.dp))
                    else MotionIcon(HomeGlyphs.SearchClose, { t }, Modifier.size(42.dp))
                }
            }
        }
        assertEquals(42 * 3f, bounds("search").width, .01f)
        val start = region("rest"); val search = region("search")
        compose.runOnIdle { t = .4f }
        assertFalse("The glyphs move", start.contentEquals(region("rest")))
        assertFalse(search.contentEquals(region("search")))
        compose.runOnIdle { t = 1f }
        assertArrayEquals("Pulse glyphs finish where they began", start, region("rest"))
        val close = region("search")
        assertFalse("Search and close are different poses", search.contentEquals(close))
        // A spring may overshoot either end; the glyph holds its pose there.
        compose.runOnIdle { t = 1.08f }
        assertArrayEquals(close, region("search"))
        compose.runOnIdle { t = -.08f }
        assertArrayEquals(search, region("search"))
        compose.runOnIdle { static = true; t = 0f }
        sameShape("Resting tab glyphs are the static icons", start, region("rest"))
        sameShape("The glyph at 0 is the search icon", search, region("search"))
        compose.runOnIdle { t = 1f }
        sameShape("The glyph at 1 is the close icon", close, region("search"))
        assertEquals(126 * 126, close.size)
    }

    @Test fun headerSearchAndConnectivityKeepTheirBoxesWhileTheyAnimate() {
        var online by mutableStateOf(false)
        val search = HomeSearchCoordinator()
        content {
            CompositionLocalProvider(LocalHomeSearchCoordinator provides search) {
                HomeHeader(PocketPreviewStates.home.copy(online = online), HomeTab.Chats, false, false, {}, {}, {})
            }
        }
        val button = bounds("home-header-search"); val dot = bounds("home-connectivity")
        assertEquals(48 * 3f, button.width, .01f); assertEquals(48 * 3f, button.height, .01f)
        assertEquals(6 * 3f, dot.width, .01f); assertEquals(6 * 3f, dot.height, .01f)
        val collapsed = region("home-header-search")
        compose.onNodeWithTag("home-header-search").assertContentDescriptionEquals(compose.activity.getString(dev.codexpocket.app.R.string.v03_home_search))

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { search.expanded = true; online = true }
        advance(80)
        val morphing = region("home-header-search")
        assertFalse(collapsed.contentEquals(morphing))
        assertEquals(button, bounds("home-header-search")); assertEquals(dot, bounds("home-connectivity"))
        advance(1500)
        val expanded = region("home-header-search")
        assertFalse("Mid-morph is neither pose", expanded.contentEquals(morphing))
        compose.onNodeWithTag("home-header-search").assertContentDescriptionEquals(compose.activity.getString(dev.codexpocket.app.R.string.v03_home_hide_search))
        compose.runOnIdle { search.expanded = false }
        advance(1500)
        assertArrayEquals("Closing the search returns the lens", collapsed, region("home-header-search"))

        // Motion off: the next change is complete on the first frame.
        compose.runOnIdle { preferences = PocketGlassPreferences(true, false, true); search.expanded = true }
        advance(32)
        assertArrayEquals(expanded, region("home-header-search"))
        assertEquals(dot, bounds("home-connectivity"))
    }

    @Test fun collapsingAGroupSlidesTheNextHeaderAndReducedMotionMovesItAtOnce() {
        val first = chat("a", "C:\\work\\alpha"); val second = chat("b", "C:\\work\\beta")
        val state = PocketPreviewStates.home.copy(chats = listOf(first, second), computerId = first.key.computerId, online = true)
        content { ChatsHome(state, {}, {}, { _, _, _ -> }, {}, {}, onEnsureDirectory = { _, _ -> }) }
        fun header(chat: ChatEntity) = "home-group-project:${HomeProject(chat.key.computerId, chat.cwd).id}"
        val groups = listOf(first, second).sortedBy { bounds(header(it)).top }
        val upper = header(groups[0]); val lower = header(groups[1]); val row = "home-chat-${groups[0].key.encoded}"
        val open = bounds(lower)
        val rowBounds = bounds(row)
        assertTrue("64dp minimum row", rowBounds.height >= 64 * 3f)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(upper).performClick()
        advance(48)
        val sliding = bounds(lower)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val closed = bounds(lower)
        assertEquals("The header ends directly under the collapsed group", open.top - rowBounds.height, closed.top, 1f)
        assertTrue("It travels there rather than jumping: ${sliding.top}", sliding.top > closed.top + 1f && sliding.top <= open.top)
        compose.onNodeWithTag(row).assertDoesNotExist()
        compose.onNodeWithTag(upper).performClick()
        compose.waitForIdle()
        assertEquals(open, bounds(lower)); assertEquals(rowBounds, bounds(row))

        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(upper).performClick()
        advance(32)
        assertEquals(closed, bounds(lower))
        compose.onNodeWithTag(row).assertDoesNotExist()
    }
}
