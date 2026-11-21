package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.ui.*
import dev.codexpocket.app.data.PocketState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in rendering of the production glyphs. No fixture actions dispatch native requests. */
@RunWith(AndroidJUnit4::class)
class ActivityMotionDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun recordGeometryAndCompletionInLightAndDark() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketMotionFixture")=="true")
        var running by mutableStateOf(true)
        var theme by mutableStateOf("light")
        var tab by mutableStateOf(HomeTab.Chats)
        var segment by mutableIntStateOf(0)
        var entrance by mutableIntStateOf(0)
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme(theme) {
            PocketHomeFrame(PocketState(computerName="Windows",online=true),tab,0,false,false,{},{},{},{tab=it},onAssistant={}) {
              Box(Modifier.fillMaxSize()) {
                PocketLightPoints(Modifier.fillMaxSize(),edgeOnly=false)
                Column(Modifier.padding(LocalHomeContentPadding.current).padding(horizontal=28.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Text("状态图标动画",style=MaterialTheme.typography.titleLarge)
                    Text("命令执行 · 读取内容 · 恢复连接",style=MaterialTheme.typography.bodyMedium)
                    PocketSegmented(listOf("概览","过程","成果"),segment,{segment=it})
                    for((kind,text) in listOf(ActivityMotion.Terminal to "运行命令",ActivityMotion.Document to "读取文件",ActivityMotion.Connection to "恢复连接")) { key(kind,entrance) {
                        Row(Modifier.height(40.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            ActivityMotionIcon(kind,running,Modifier.size(18.dp))
                            ActivityTextFlow(running,running,Modifier.weight(1f)) {
                                Text(if(running) "正在$text · Codex Pocket" else "已完成$text",fontSize=14.sp,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ActivityMotionIcon(kind,running,Modifier.size(36.dp))
                        }
                    }}
                    Text("正文位置保持不变",Modifier.testTag("stable-body"),style=MaterialTheme.typography.bodyMedium)
                }
              }
            }
        }}}
        compose.waitForIdle()
        // Drive the test clock at wall speed: autoAdvance skips finite entrance/tab frames.
        compose.mainClock.autoAdvance=false
        fun play(milliseconds:Int) {repeat(milliseconds/16) {compose.mainClock.advanceTimeByFrame();Thread.sleep(16)}}
        val bounds=compose.onNodeWithTag("stable-body").fetchSemanticsNode().boundsInRoot
        val output=File(compose.activity.getExternalFilesDir(null),"rc37-motion").apply {mkdirs()}
        NativeWindowRecording(compose.activity,output,intervalMillis=33,maxFrames=1000).use {
            compose.runOnIdle {entrance++}
            play(1100);compose.runOnIdle {tab=HomeTab.Attention;segment=1}
            play(1100);compose.runOnIdle {tab=HomeTab.Devices;segment=2}
            play(1100);compose.runOnIdle {tab=HomeTab.Chats;segment=0}
            play(1500)
            compose.runOnIdle {running=false};play(1200)
            org.junit.Assert.assertEquals(bounds,compose.onNodeWithTag("stable-body").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle {theme="dark";running=true;entrance++}
            play(1100);compose.runOnIdle {tab=HomeTab.Attention;segment=1}
            play(1100);compose.runOnIdle {tab=HomeTab.Devices;segment=2}
            play(1100);compose.runOnIdle {tab=HomeTab.Chats;segment=0}
            play(1500)
            compose.runOnIdle {running=false};play(1200)
        }
    }
}
