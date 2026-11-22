package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.LocalAccount
import dev.codexpocket.wire.ExecutionTarget
import dev.codexpocket.wire.ExecutionTargetDirectory
import dev.codexpocket.wire.ExecutionTargetKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Running Codex on this phone is offered only by a package that carries it, and says what state sign-in is in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThisPhoneSheetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val events = mutableListOf<String>()

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketOverlayHost { block() } } } }
        compose.waitForIdle()
    }
    private fun sheet(added: Boolean, account: LocalAccount, progress: String? = null) = content {
        ThisPhoneSheet(added, account, progress, { events += "dismiss" }, { events += "start" }, { events += "chatgpt" },
            { events += "key:$it" }, { events += "refresh" }, { events += "stop" })
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun locationListOffersThisPhoneOnlyWhenThePackageCarriesTheRuntime() {
        val computer = ExecutionTarget("c", ExecutionTargetKind.Computer, "电脑", "endpoint-c")
        var offered by mutableStateOf(false)
        content { ExecutionLocationChoices(ExecutionTargetDirectory(listOf(computer), "c"), true, {}, {}, {}, onThisPhone = if (offered) ({ events += "open" }) else null) }
        assertFalse(exists("execution-this-phone"))
        offered = true
        compose.waitForIdle()
        compose.onNodeWithTag("execution-this-phone").performScrollTo().assertTextContains("在此手机上运行").performClick()
        assertEquals(listOf("open"), events)
    }

    @Test fun beforeItIsAddedTheOnlyActionIsToStartAndItWaitsWhileStarting() {
        sheet(added = false, account = LocalAccount.Unknown)
        assertFalse(exists("this-phone-chatgpt") || exists("this-phone-stop"))
        compose.onNodeWithTag("this-phone-start").performScrollTo().performClick()
        assertEquals(listOf("start"), events)
        sheet(added = false, account = LocalAccount.Unknown, progress = "正在启动此手机上的运行环境")
        compose.onNodeWithTag("this-phone-start").assertIsNotEnabled()
        compose.onNodeWithText("正在启动此手机上的运行环境").assertExists()
    }

    @Test fun signedOutOffersBothWaysInAndAKeyIsOnlySentWhenItLooksLikeOne() {
        sheet(added = true, account = LocalAccount.SignedOut)
        compose.onNodeWithTag("this-phone-chatgpt").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-use-api-key").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-api-key-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("this-phone-api-key").performTextInput("sk-test-0123456789abcdefghij")
        compose.onNodeWithTag("this-phone-api-key-save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("chatgpt", "key:sk-test-0123456789abcdefghij"), events)
        // The key leaves the screen once handed over.
        assertFalse(exists("this-phone-api-key"))
    }

    @Test fun deviceCodeIsShownForThePersonToEnterAndSignedInNamesTheAccount() {
        sheet(added = true, account = LocalAccount.Code("https://auth.openai.com/codex/device", "ABCD-EFGHI"))
        compose.onNodeWithTag("this-phone-code").assertTextEquals("ABCD-EFGHI")
        compose.onNodeWithTag("this-phone-open-login").assertExists()
        assertFalse(exists("this-phone-chatgpt"))
        sheet(added = true, account = LocalAccount.SignedIn("person@example.com"))
        compose.onNodeWithTag("this-phone-signed-in").assertTextEquals("已登录 · person@example.com")
        compose.onNodeWithTag("this-phone-stop").performScrollTo().performClick()
        assertEquals(listOf("stop"), events)
    }

    @Test fun failureSaysSoAndLetsThePersonRetryOrReadAgain() {
        sheet(added = true, account = LocalAccount.Failed("登录未完成，请重试"))
        compose.onNodeWithTag("this-phone-failed").assertTextEquals("登录未完成，请重试")
        compose.onNodeWithText("重新读取登录状态").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-chatgpt").performScrollTo().performClick()
        assertEquals(listOf("refresh", "chatgpt"), events)
    }
}
