package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ConversationErrorRowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longHtmlIsCollapsedAndExpandedHeightStaysBounded() {
        val raw = "HTTP 522; cause: <!DOCTYPE html>" + "<div>upstream error</div>\n".repeat(1000)
        var dark by mutableStateOf(false)
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(if (dark) "dark" else "light") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Surface { Column(Modifier.width(320.dp)) {
                        ConversationErrorRow(ConversationErrorPresentation.fromText(raw), "sample")
                        Text("后续消息")
                    } }
                }
            }
        } }
        for (value in listOf(false, true)) {
            compose.runOnIdle { dark = value }
            compose.onNodeWithText("上游连接超时 · HTTP 522").assertIsDisplayed()
            compose.onNodeWithText("<!DOCTYPE", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("error-detail:sample").assertDoesNotExist()
            assertTrue(compose.onNodeWithTag("conversation-error:sample").getUnclippedBoundsInRoot().let { it.bottom - it.top } <= 100.dp)
            compose.onNodeWithTag("error-toggle:sample").performClick()
            compose.onNodeWithTag("error-detail:sample").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("error-detail:sample").getUnclippedBoundsInRoot().let { it.bottom - it.top } <= 300.dp)
            compose.onNodeWithText("后续消息").assertIsDisplayed()
            compose.onNodeWithContentDescription("复制").assertExists()
            compose.onNodeWithTag("error-toggle:sample").performClick()
        }
    }

    @Test fun globalErrorUsesSameCompactViewAndKeepsDismissAction() {
        var dismissals = 0
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("dark") { Surface { Notice("HTTP 503; <!doctype html>" + "error".repeat(1000), true, "关闭") { dismissals++ } } }
        } }
        compose.onNodeWithText("上游服务暂不可用 · HTTP 503").assertIsDisplayed()
        compose.onNodeWithText("<!doctype", substring = true).assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(1, dismissals)
    }
}
