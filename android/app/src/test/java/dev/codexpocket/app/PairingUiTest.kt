package dev.codexpocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.data.PocketState
import dev.codexpocket.app.ui.PocketRoot
import dev.codexpocket.app.ui.PocketTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Android renderer on the host JVM; this is not a phone/emulator acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun pairing(theme: String) {
        val app = ApplicationProvider.getApplicationContext<PocketApplication>()
        compose.waitUntil(15_000) { app.repository.state.value.initialized }
        app.repository.theme(theme)
        compose.waitForIdle()
        compose.onNodeWithText("连接你的电脑").assertExists()
        compose.onNodeWithTag("pair-scan").assertExists()
        compose.onNodeWithTag("pair-payload").assertDoesNotExist()
        compose.onNodeWithTag("pair-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithText("粘贴配对内容").assertExists()
        compose.onNodeWithTag("pair-connect").assertIsNotEnabled()
        compose.onNodeWithTag("pair-payload").performScrollTo().performTextInput("fixture-only-no-credentials")
        compose.onNodeWithTag("pair-connect").assertIsEnabled()
        compose.onNodeWithTag("pair-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pair-payload").assertDoesNotExist()
        compose.onNodeWithText("配对内容待验证").assertExists()
        compose.onAllNodesWithText("Sign in").assertCountEquals(0)
        compose.onAllNodesWithText("Log in").assertCountEquals(0)
        compose.onAllNodesWithText("登录").assertCountEquals(0)
        compose.waitForIdle()
    }
    @Test fun accountFreePairingLight() = pairing("light")
    @Test fun accountFreePairingDark() = pairing("dark")
}
