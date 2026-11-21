package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production UI states only; no requests or changes to paired conversations. */
@RunWith(AndroidJUnit4::class)
class RecoveryNoticeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun automaticRecoveryStaysInHeaderWithoutBlockingMessages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketMotionFixture")=="true")
        val key=ThreadKey("pc","store","t")
        var state by mutableStateOf(PocketState(computerId="pc",online=true,
            selected=ChatEntity(key,"history:store","cli","项目进展","","D:/project",0,"idle",false),
            capabilities=setOf("threads/takeover"),conversationCached=true,
            conversationRecovery=mapOf(key to ConversationRecovery("waiting_release","native_start_unconfirmed","op"))))
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("dark") {PocketBackdrop {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                CompositionLocalProvider(LocalConversationSyncLabel provides state.automaticConnectionLabel()) {
                    ConversationHeader(state,{},{},{})
                }
                Text("已有消息保留在这里，可以继续阅读。",Modifier.padding(vertical=24.dp))
            }
        }}}}
        fun record(name:String, change:()->Unit={}) {
            NativeWindowRecording(compose.activity,File(compose.activity.getExternalFilesDir(null),"rc38-recovery/$name")).use {
                compose.runOnIdle(change);Thread.sleep(1600)
            }
        }
        compose.onNodeWithTag("conversation-sync",useUnmergedTree=true).assertIsDisplayed()
        val header=compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        record("waiting")
        record("checking") {state=state.copy(actionStatus=mapOf("access:${key.encoded}" to ActionStatus(loading=true)))}
        compose.onNodeWithTag("recovery-action").assertDoesNotExist()
        record("reconnect") {state=state.copy(actionStatus=emptyMap(),conversationRecovery=mapOf(key to ConversationRecovery("disconnected","runtime_restarted","op")))}
        compose.onNodeWithText("重新连接").assertDoesNotExist()
        compose.onNodeWithText("已有消息保留在这里，可以继续阅读。").assertIsDisplayed()
        org.junit.Assert.assertEquals(header,compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)
    }
}
