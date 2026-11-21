package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.PocketApplication
import dev.codexpocket.app.data.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w360dp-h800dp-xxhdpi")
class ConversationRecoveryNoticeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val key=ThreadKey("pc","store","t")
    private val chat=ChatEntity(key,"history:store","cli","Saved","","D:/project",0,"idle",false)
    @Test fun actualConversationMenuAllowsUnarchiveThroughSameProfileManagementChannel() {
        val state=PocketState(computerId="pc",online=true,selected=chat.copy(archived=true),
            runtimes=listOf(RuntimeInfo("native","cli","fixture","store",true,emptySet(),setOf("thread/unarchive"))))
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("dark") {PocketBackdrop {
            ConversationPanel(state,(compose.activity.application as PocketApplication).repository,null,{},null)
        }}}}
        compose.onNodeWithText("取消归档").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun automaticRecoveryUsesExistingHeaderWithoutUnlockBannerOrButton() {
        var state by mutableStateOf(PocketState(computerId="pc",online=true,selected=chat,
            capabilities=setOf("threads/takeover"),conversationReadSource="persisted",conversationRecovery=mapOf(key to
                ConversationRecovery("waiting_release","native_start_unconfirmed","op"))))
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("dark") {PocketBackdrop {
            ChatScreen(state,(compose.activity.application as PocketApplication).repository,{},{},{},header={ConversationHeader(state,{},{},{})})
        }}}}
        compose.onNodeWithTag("conversation-sync",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("conversation-recovery").assertDoesNotExist()
        compose.onNodeWithText("重新核验").assertDoesNotExist()
        compose.onNodeWithTag("recovery-action").assertDoesNotExist()
        val header=compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {state=state.copy(selected=chat.copy(runtimeId="native"),
            runtimes=listOf(RuntimeInfo("native","cli","fixture","store",true,emptySet(),emptySet())))}
        compose.onNodeWithTag("conversation-sync",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(header,compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)
    }
}
