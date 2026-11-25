package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class OutgoingMessageStatusTest {
    @get:Rule val compose = createComposeRule()
    private val original = OutgoingUserMessage("client", ThreadKey("pc", "store", "chat"), "command", 1, "消息", "[]", "[]", 0)
    private var message by mutableStateOf(original)
    private var retries = 0
    private var checks = 0
    private fun content() {
        compose.mainClock.autoAdvance = false
        compose.setContent { PocketTheme("light") { PocketBackdrop {
            OutgoingMessageStatus(message, true, { retries++ }, { checks++ })
        } } }
        compose.waitForIdle()
    }
    private fun frames(count: Int = 45) { repeat(count) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }

    @Test fun pendingDotsStopAtAckAndAtUnknown() {
        content()
        compose.onNodeWithTag("message-sending:client").assertIsDisplayed()
        compose.runOnIdle { message = original.copy(phase = "queued") }
        frames(2)
        compose.onNodeWithTag("message-sending:client").assertDoesNotExist()
        compose.onNodeWithText("已排队").assertIsDisplayed()
        compose.runOnIdle { message = original.copy(phase = "unknown") }
        frames(2)
        compose.onNodeWithTag("message-sending:client").assertDoesNotExist()
        compose.onNodeWithTag("message-failed:client").assertDoesNotExist()
        compose.onNodeWithTag("message-check:client").performClick()
        assertEquals(1, checks)
        assertEquals(0, retries)
    }

    @Test fun failureOpensDetailsAndExplicitRetry() {
        message = original.copy(phase = "rejected")
        content()
        compose.onNodeWithTag("message-failed:client").performClick()
        frames()
        compose.onNodeWithText("消息未发送").assertIsDisplayed()
        compose.onNodeWithText("重新发送").performClick()
        assertEquals(1, retries)
        assertEquals(0, checks)
    }
}
