package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ThreadDeepLinkUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun copyWorksForOfflineEmptyAndChildConversations() {
        val chat = ChatEntity(ThreadKey("pc", "store", "01a1c7fb-7bda-7a71-a782-a020229c9573"), "runtime", "cli", "", "", "", 0, "", false, isSubagent = true)
        var copied: String? = null
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("dark") {
            val clipboard = LocalClipboardManager.current
            Column { CopyConversationLinkItem(chat) { clipboard.setText(AnnotatedString(it)); copied = clipboard.getText()?.text } }
        } } }
        compose.onNodeWithText("复制会话链接").performClick()
        compose.runOnIdle { assertEquals("codex://threads/${chat.key.nativeId}", copied) }
    }
    @Test fun noNativeIdDisablesCopy() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { Column { CopyConversationLinkItem(null) {} } } } }
        compose.onNodeWithText("复制会话链接").assertIsNotEnabled()
        compose.onNodeWithText("会话尚无有效原生 ID").assertExists()
    }
}
