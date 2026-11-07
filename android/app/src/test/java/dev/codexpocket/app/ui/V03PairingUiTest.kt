package dev.codexpocket.app.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V03PairingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screen(onPair: (String, String) -> Any) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.CAMERA)
        compose.activity.runOnUiThread {
            compose.activity.setContent { PocketTheme("light") {
                CompositionLocalProvider(LocalPocketGlass provides false) { PocketBackdrop { PairScreen(false, onPair) } }
            } }
        }
        compose.waitForIdle()
    }
    private fun manual(value: String) {
        compose.onNodeWithTag("pair-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("pair-payload").performScrollTo().performTextInput(value)
        compose.onNodeWithTag("pair-connect").performScrollTo().performClick()
    }
    private fun fixture(expiry: Long) = """{"v":1,"relayUrl":"wss://relay.example/pocket","room":"fixture","phoneToken":"fixture-phone","certificateSha256":"${"ab".repeat(32)}","pairingToken":"fixture-pair","expiresAt":$expiry,"computerName":"Desktop"}"""

    @Test fun missingPermissionStillAllowsManualAndInvalidNeverReachesRepository() {
        var calls = 0
        screen { _, _ -> calls++ }
        compose.onNodeWithText("允许相机权限后开始扫描").assertExists()
        compose.onNodeWithText("相册").assertExists()
        manual("invalid fixture")
        compose.onNodeWithText("不是有效的 Codex Pocket 配对码").assertExists()
        assertEquals(0, calls)
    }
    @Test fun expiredManualCodeShowsRefreshInstructionAndDoesNotPair() {
        var calls = 0
        screen { _, _ -> calls++ }
        manual(fixture(1))
        compose.onNodeWithText("配对码已过期，请在电脑上生成新的配对码").assertExists()
        assertEquals(0, calls)
    }
    @Test fun validManualSubmissionConsumesPayloadOnce() {
        var calls = 0
        screen { _, _ -> calls++ }
        manual(fixture(System.currentTimeMillis() / 1000 + 300))
        assertEquals(1, calls)
        compose.onNodeWithTag("pair-payload").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("pair-connect").assertIsNotEnabled()
    }
}
