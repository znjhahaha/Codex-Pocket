@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
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

/** Each icon's own motion starts and ends in the pose the static icon rests in, and moves in between. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitIconGlyphTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))

    private val icons: Map<String, ImageVector> = PocketIcons.javaClass.methods
        .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
        .map { it.invoke(PocketIcons) as ImageVector }.associateBy { it.name }

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) { block() }
        } } }
        compose.waitForIdle()
    }
    private fun pixels(name: String? = null): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        if (name != null) java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
    }
    private fun region(all: IntArray, tag: String): IntArray {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val left = b.left.toInt(); val right = b.right.toInt()
        return IntArray((right - left) * (b.bottom.toInt() - b.top.toInt())).also { out ->
            var i = 0
            for (y in b.top.toInt() until b.bottom.toInt()) for (x in left until right) out[i++] = all[y * view.width + x]
        }
    }
    /** How much ink a pixel carries, whether it lies on paper or on nothing at all. */
    private fun ink(pixel: Int): Int = (pixel ushr 24) * (255 - ((pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255)) / 3) / 255
    /** Pixels that are clearly ink in one picture and clearly not in the other, as a share of the ink. */
    private fun mismatch(a: IntArray, b: IntArray): Float {
        var inked = 0; var apart = 0
        for (i in a.indices) {
            val x = ink(a[i]); val y = ink(b[i])
            if (x > 96 || y > 96) inked++
            if (kotlin.math.abs(x - y) > 128) apart++
        }
        return if (inked == 0) 1f else apart.toFloat() / inked
    }

    @Test fun everyGlyphBelongsToAnIconAndTheCommonIconsHaveOne() {
        assertTrue("Glyphs for icons that do not exist: ${IconGlyphs.byName.keys - icons.keys}", icons.keys.containsAll(IconGlyphs.byName.keys))
        assertTrue("More than sixty icons answer in their own way", IconGlyphs.byName.size > 60)
        for (name in listOf("Settings", "Edit", "Search", "FolderOpen", "Description", "Image", "Terminal", "ContentCopy", "Refresh", "DeleteOutline", "PushPin", "Inventory2"))
            assertNotNull("$name has its own motion", pocketIconGlyph(icons.getValue(name)))
        // Arrows and crosses keep the whole-icon motions that already say what they mean.
        for (name in listOf("ArrowBack", "ChevronRight", "Close", "Add")) assertNull(pocketIconGlyph(icons.getValue(name)))
    }

    @Test fun eachGlyphRestsInThePoseOfItsStaticIconAtBothEndsAndMovesBetween() {
        var t by mutableFloatStateOf(1f)
        val names = IconGlyphs.byName.keys.toList()
        content {
            FlowRow(Modifier.padding(6.dp)) {
                names.forEach { name ->
                    Column {
                        PIcon(icons.getValue(name), null, Modifier.size(24.dp).testTag("static:$name"))
                        MotionIcon(IconGlyphs.byName.getValue(name), { t }, Modifier.size(24.dp).testTag("glyph:$name"))
                    }
                }
            }
        }
        val end = pixels("icon-glyphs-end")
        compose.runOnIdle { t = 0f }
        val start = pixels("icon-glyphs-start")
        compose.runOnIdle { t = .42f }
        val moving = pixels("icon-glyphs-moving")
        val still = mutableListOf<String>()
        for (name in names) {
            val static = region(end, "static:$name")
            val atEnd = mismatch(static, region(end, "glyph:$name"))
            val atStart = mismatch(static, region(start, "glyph:$name"))
            assertTrue("$name ends away from its icon: $atEnd", atEnd < .06f)
            assertTrue("$name starts away from its icon: $atStart", atStart < .06f)
            if (region(end, "glyph:$name").contentEquals(region(moving, "glyph:$name"))) still += name
        }
        assertTrue("These do not move: $still", still.isEmpty())
    }

    @Test fun eachToolLoopStartsAndEndsOnItsIconSoItCanRepeatAndStopWithoutASeam() {
        var t by mutableFloatStateOf(1f)
        val names = ToolGlyphs.byName.keys.toList()
        assertTrue("Loops for icons that do not exist: ${names - icons.keys}", icons.keys.containsAll(names))
        // Every icon a tool line can show has something to do while the tool runs.
        for (name in listOf("BookOpen", "Edit", "Search", "Folder", "Globe", "Link", "Image", "RobotOutline", "Sparkle", "Wrench", "Checklist"))
            assertNotNull("$name is still while its tool runs", ToolGlyphs.working(icons.getValue(name)))
        content {
            FlowRow(Modifier.padding(6.dp)) {
                names.forEach { name ->
                    Column {
                        PIcon(icons.getValue(name), null, Modifier.size(24.dp).testTag("static:$name"))
                        MotionIcon(ToolGlyphs.byName.getValue(name), { t }, Modifier.size(24.dp).testTag("glyph:$name"))
                    }
                }
            }
        }
        val end = pixels("tool-glyphs-end")
        compose.runOnIdle { t = 0f }
        val start = pixels()
        val frames = listOf(.2f, .42f, .7f).map { step -> compose.runOnIdle { t = step }; pixels("tool-glyphs-${(step * 100).toInt()}") }
        for (name in names) {
            val static = region(end, "static:$name")
            assertTrue("$name ends away from its icon", mismatch(static, region(end, "glyph:$name")) < .06f)
            assertTrue("$name starts away from its icon", mismatch(static, region(start, "glyph:$name")) < .06f)
            assertTrue("$name does not move", frames.any { !region(end, "glyph:$name").contentEquals(region(it, "glyph:$name")) })
        }
    }

    @Test fun everyGlyphDrawsAtAnyProgressWithoutLeavingItsBox() {
        var t by mutableFloatStateOf(0f)
        content { FlowRow { IconGlyphs.byName.values.distinct().forEach { MotionIcon(it, { t }, Modifier.size(24.dp)) } } }
        for (step in listOf(-.2f, 0f, .01f, .13f, .25f, .5f, .75f, .99f, 1f, 1.3f)) { compose.runOnIdle { t = step }; pixels() }
    }

    @Test fun aTappedIconPlaysItsGlyphOnceAndIsTheStaticIconAgain() {
        var taps = 0
        content {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.testTag("buttons")) {
                    PIconButton(PocketIcons.Settings, "设置", { taps++ }, Modifier.testTag("settings"))
                    PIconButton(PocketIcons.Search, "搜索", { taps++ }, Modifier.testTag("search"))
                }
                PRow("项目文件", { taps++ }, Modifier.testTag("row"), PocketIcons.FolderOpen)
                PButton("复制", { taps++ }, Modifier.testTag("button"), leading = PocketIcons.ContentCopy)
            }
        }
        val rest = pixels()
        compose.mainClock.autoAdvance = false
        fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
        for (tag in listOf("settings", "search", "row", "button")) {
            compose.onNodeWithTag(tag).performClick()
            advance(260)
            assertFalse("$tag: the icon is moving", region(rest, tag).contentEquals(region(pixels("icon-tap-$tag"), tag)))
            advance(1600)
            assertArrayEquals("$tag: back to the static icon, pixel for pixel", rest, pixels())
        }
        assertEquals(4, taps)

        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        compose.waitForIdle()
        val calm = pixels()
        compose.onNodeWithTag("settings").performClick()
        advance(260)
        assertArrayEquals("With motion off the icon stays as it is", region(calm, "buttons"), region(pixels(), "buttons"))
    }
}
