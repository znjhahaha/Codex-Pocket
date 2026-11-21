package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityMotionIconTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private var running by mutableStateOf(false)
    private var preferences by mutableStateOf(PocketGlassPreferences(false,false,true))
    private lateinit var view: android.view.View
    private fun frame(ms:Long, name:String?=null):IntArray {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(ms);compose.waitForIdle()
        return compose.runOnIdle {
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            if(name!=null) java.io.File("build/reports/motion/$name.png").also {it.parentFile.mkdirs()}.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            IntArray(bitmap.width*bitmap.height).also {bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle()}
        }
    }
    @Test fun geometryMovesAndSettlesWithoutChangingBoundsAndHonoursReducedMotion() {
        compose.activity.runOnUiThread {compose.activity.setContent {
            PocketTheme("light") {
                view=LocalView.current
                CompositionLocalProvider(LocalPocketGlassPreferences provides preferences) {
                    Row(Modifier.testTag("icons")) {
                        listOf(ActivityMotion.Terminal,ActivityMotion.Document,ActivityMotion.Connection).forEach {
                            ActivityMotionIcon(it,running,Modifier.size(32.dp))
                        }
                    }
                }
            }
        }}
        compose.waitForIdle();compose.mainClock.autoAdvance=false
        val bounds=compose.onNodeWithTag("icons").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {running=true}
        val start=frame(64);val moving=frame(720)
        assertFalse("The glyph geometry must move",start.contentEquals(moving))
        assertEquals(bounds,compose.onNodeWithTag("icons").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {running=false}
        val settled=frame(250)
        assertArrayEquals(settled,frame(900))
        for(setting in listOf(PocketGlassPreferences(false,false,false),PocketGlassPreferences(false,true,true),PocketGlassPreferences(true,false,true))) {
            compose.runOnIdle {preferences=setting;running=true}
            val still=frame(64);assertArrayEquals(still,frame(900))
        }
    }

    @Test fun travellingTextAndTabTypeKeepTheirLayoutAndStopAfterCompletion() {
        var selected by mutableStateOf(false)
        var entry by mutableIntStateOf(0)
        compose.activity.runOnUiThread {compose.activity.setContent {
            PocketTheme("light") {
                view=LocalView.current
                CompositionLocalProvider(LocalPocketGlassPreferences provides preferences) {
                    Column {
                        Box(Modifier.size(90.dp,48.dp).testTag("tab-hit-target")) {PocketTabLabel("任务",selected)}
                        key(entry) { ActivityTextFlow(running,true,Modifier.height(32.dp).testTag("flow")) {Text("运行命令 · rg ConversationRecovery")} }
                        Text("正文锚点",Modifier.testTag("body-anchor"))
                    }
                }
            }
        }}
        compose.waitForIdle();compose.mainClock.autoAdvance=false
        val tab=compose.onNodeWithTag("tab-hit-target").fetchSemanticsNode().boundsInRoot
        val body=compose.onNodeWithTag("body-anchor").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {running=true;selected=true;entry++}
        val first=frame(64)
        assertFalse(first.contentEquals(frame(440)))
        compose.onNodeWithText("运行命令 · rg ConversationRecovery").assertExists()
        assertEquals(tab,compose.onNodeWithTag("tab-hit-target").fetchSemanticsNode().boundsInRoot)
        assertEquals(body,compose.onNodeWithTag("body-anchor").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {running=false}
        val complete=frame(1000)
        assertArrayEquals(complete,frame(900))
        compose.runOnIdle {preferences=PocketGlassPreferences(false,false,false);running=true;selected=false;entry++}
        val reduced=frame(64,"reduced-start")
        assertArrayEquals(reduced,frame(900,"reduced-end"))
        compose.onNodeWithText("运行命令 · rg ConversationRecovery").assertIsDisplayed()
    }
}
