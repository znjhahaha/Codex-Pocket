package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The mark inside the app is the icon on the home screen, and it assembles into exactly that. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitBrandTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, true), LocalPocketGlass provides false) { block() }
        } } }
        compose.waitForIdle()
    }
    private fun save(bitmap: android.graphics.Bitmap, name: String) =
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    private fun screen(name: String? = null): android.graphics.Bitmap = compose.runOnIdle {
        android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
            view.draw(android.graphics.Canvas(it)); if (name != null) save(it, name)
        }
    }
    private fun crop(all: android.graphics.Bitmap, tag: String): android.graphics.Bitmap {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return android.graphics.Bitmap.createBitmap(all, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
    }
    /** The launcher's view of the adaptive icon: both layers, the middle two thirds, [side] pixels square. */
    private fun launcher(side: Int): android.graphics.Bitmap {
        val full = side * 108 / 72
        val layers = android.graphics.Bitmap.createBitmap(full, full, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(layers)
        for (id in listOf(R.drawable.ic_pocket_background, R.drawable.ic_pocket_foreground))
            ContextCompat.getDrawable(compose.activity, id)!!.apply { setBounds(0, 0, full, full) }.draw(canvas)
        val inset = (full - side) / 2
        return android.graphics.Bitmap.createBitmap(layers, inset, inset, side, side)
    }
    private fun pixels(bitmap: android.graphics.Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    /** Mean difference per channel, 0..255, over the pixels inside the middle [share] of the picture. */
    private fun apart(a: android.graphics.Bitmap, b: android.graphics.Bitmap, share: Float = .86f): Float {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val x = pixels(a); val y = pixels(b); val edge = ((1f - share) / 2f * a.width).toInt()
        var sum = 0L; var count = 0
        for (row in edge until a.height - edge) for (col in edge until a.width - edge) {
            val p = x[row * a.width + col]; val q = y[row * a.width + col]
            sum += abs((p shr 16 and 255) - (q shr 16 and 255)) + abs((p shr 8 and 255) - (q shr 8 and 255)) + abs((p and 255) - (q and 255)); count += 3
        }
        return sum.toFloat() / count
    }

    @Test fun theMarkInTheAppIsTheLauncherIcon() {
        content { Box(Modifier.fillMaxSize().background(Color.Black)) { PBrandMark(Modifier.padding(24.dp).size(96.dp).testTag("mark")) } }
        val mark = crop(screen(), "mark")
        save(mark, "brand-mark")
        val icon = launcher(mark.width)
        save(icon, "brand-launcher")
        // The launcher cuts its own corners; inside them the two are one drawing.
        assertTrue("The mark and the launcher icon differ: ${apart(mark, icon, .7f)}", apart(mark, icon, .7f) < 6f)
        for ((id, name) in listOf(R.drawable.ic_pocket to "brand-legacy", R.drawable.ic_pocket_monochrome to "brand-monochrome", R.drawable.ic_pocket_notification to "brand-notification")) {
            val drawn = android.graphics.Bitmap.createBitmap(288, 288, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(drawn).also { it.drawColor(0xFF30343A.toInt()) }.let { canvas ->
                ContextCompat.getDrawable(compose.activity, id)!!.apply { setBounds(0, 0, 288, 288) }.draw(canvas)
            }
            save(drawn, name)
            assertTrue("$name draws something", pixels(drawn).any { it != 0xFF30343A.toInt() })
        }
    }

    @Test fun theMarkAssemblesFromNothingIntoTheIconAndTheCursorCanBlink() {
        var t by mutableFloatStateOf(0f)
        var cursor by mutableFloatStateOf(1f)
        content { Box(Modifier.fillMaxSize().background(Color.Black)) { PBrandMark(Modifier.padding(24.dp).size(96.dp).testTag("mark"), { t }, { cursor }) } }
        val empty = crop(screen(), "mark")
        assertTrue("Nothing is there before it starts", pixels(empty).all { it == 0xFF000000.toInt() })
        var last = empty
        for (step in listOf(.2f, .4f, .6f, .8f, 1f)) {
            compose.runOnIdle { t = step }
            val now = crop(screen(), "mark")
            save(now, "brand-assemble-${(step * 100).toInt()}")
            assertTrue("It keeps changing until it is whole ($step)", apart(last, now, 1f) > .05f)
            last = now
        }
        compose.runOnIdle { cursor = 0f }
        val dark = crop(screen(), "mark")
        assertTrue("The cursor goes out on its own", apart(last, dark, 1f) > .02f)
        assertTrue("and nothing else changes", apart(last, dark, 1f) < 3f)
    }
}
