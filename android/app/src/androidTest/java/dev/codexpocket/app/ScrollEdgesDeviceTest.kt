package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollEdgesDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun recordHomeProjectAndManagementScrollBoundaries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketMotionFixture")=="true")
        var screen by mutableStateOf("home")
        val fixture=PocketState(computerId="edge-fixture",computerName="Windows",online=true,
            runtimes=listOf(RuntimeInfo("r","desktop","fixture","store",true,emptySet(),setOf("thread/start"))),
            capabilities=setOf("thread/start"),chats=(1..24).map {ChatEntity(ThreadKey("edge-fixture","store","t$it"),"r","desktop",
                "会话 $it · 阅读、任务与项目","继续查看项目进展","D:/projects/project-${it%3}",it.toLong(),"idle",false)})
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("dark") {
            PocketBackdrop {
                when(screen) {
                    "project" -> Box(Modifier.fillMaxWidth().height(600.dp)) {
                        ProjectCreationForm(fixture,{}) {_,_,_,_->error("Rendering must not create a conversation")}
                    }
                    else -> PocketHomeFrame(fixture,if(screen=="home")HomeTab.Chats else HomeTab.Devices,0,false,false,{},{},{},{},onAssistant={}) {
                        if(screen=="home") ChatsHome(fixture,{},{},{_,_,_->},{},{})
                        else SettingsPageLayout(modifier=Modifier.padding(LocalHomeContentPadding.current)) {
                            repeat(20) {n -> Column(Modifier.fillMaxWidth().padding(vertical=14.dp)) {
                                Text("连接选项 ${n+1}",style=MaterialTheme.typography.titleMedium)
                                Text("滚动经过边界时内容逐渐隐去",style=MaterialTheme.typography.bodyMedium)
                            }}
                        }
                    }
                }
            }
        }}}
        fun record(name:String) {
            compose.waitForIdle()
            NativeWindowRecording(compose.activity,File(compose.activity.getExternalFilesDir(null),"rc37-edges/$name")).use {Thread.sleep(400)}
        }
        compose.onNodeWithTag("chats-home").performScrollToIndex(6)
        record("home")
        compose.runOnIdle {screen="project"}
        compose.onNodeWithTag("project-form-scroll").performTouchInput {swipeUp(durationMillis=350)}
        record("project")
        compose.runOnIdle {screen="management"}
        compose.onNodeWithText("连接选项 10").performScrollTo()
        record("management")
    }
}
