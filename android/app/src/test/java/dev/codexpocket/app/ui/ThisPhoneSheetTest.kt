package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.LocalAccess
import dev.codexpocket.app.data.LocalAccount
import dev.codexpocket.app.data.LocalProviderSummary
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
    private val relay = LocalProviderSummary("relay", "我的中转站", "https://relay.example/v1", "gpt-5.5", "sk-…cdef")
    private var saves = true
    private fun sheet(added: Boolean, account: LocalAccount, progress: String? = null, access: LocalAccess = LocalAccess(), applying: Boolean = false) = content {
        ThisPhoneSheet(added, account, progress, { events += "dismiss" }, { events += "start" }, { events += "chatgpt" },
            { events += "refresh" }, { events += "stop" }, access, applying, { events += "use:$it" },
            { events += "save:${it.id}|${it.name}|${it.baseUrl}|${it.apiKey}|${it.model}"; saves }, { events += "remove:$it" }, { events += "sign-out" })
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

    @Test fun signedOutOffersSignInAndAThirdPartyServiceIsSavedOnlyWhenItCanBeUsed() {
        sheet(added = true, account = LocalAccount.SignedOut)
        compose.onNodeWithTag("this-phone-official").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("this-phone-chatgpt").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-add-provider").performScrollTo().performClick()
        // The list gives way to the form; nothing else can be chosen meanwhile.
        assertFalse(exists("this-phone-official") || exists("this-phone-chatgpt"))
        compose.onNodeWithTag("this-phone-provider-save").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-problem").assertTextEquals("请填写名称，不超过 40 个字。")
        compose.onNodeWithTag("this-phone-provider-name").performTextInput("我的中转站")
        compose.onNodeWithTag("this-phone-provider-url").performTextInput("http://relay.example/v1")
        compose.onNodeWithTag("this-phone-provider-key").performTextInput("sk-test-0123456789abcdefghij")
        compose.onNodeWithTag("this-phone-provider-save").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-problem").assertTextEquals("接口地址需要以 https:// 开头；http:// 只能用于数字 IP 地址。")
        compose.onNodeWithTag("this-phone-provider-url").performTextReplacement("https://relay.example/v1")
        compose.onNodeWithTag("this-phone-provider-model").performTextInput("gpt-5.5")
        assertEquals(listOf("chatgpt"), events)
        compose.onNodeWithTag("this-phone-provider-save").performScrollTo().performClick()
        assertEquals(listOf("chatgpt", "save:null|我的中转站|https://relay.example/v1|sk-test-0123456789abcdefghij|gpt-5.5"), events)
        // The key leaves the screen once handed over.
        assertFalse(exists("this-phone-provider-key"))
        assertTrue(exists("this-phone-official"))
    }

    @Test fun aServiceInUseIsNamedAndCanBeSwitchedEditedWithoutRetypingItsKeyOrRemoved() {
        sheet(added = true, account = LocalAccount.Unknown, access = LocalAccess(listOf(relay), "relay"))
        compose.onNodeWithTag("this-phone-provider:relay").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("this-phone-provider-active").assertTextContains("我的中转站", substring = true)
        // OpenAI's sign-in state is not asked for while another service is in use.
        assertFalse(exists("this-phone-checking") || exists("this-phone-chatgpt"))
        compose.onNodeWithTag("this-phone-official").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-edit:relay", useUnmergedTree = true).performScrollTo().performClick()
        // A saved key is never shown again; leaving the field empty keeps it.
        compose.onNodeWithTag("this-phone-provider-key").assertTextContains("留空则继续使用 sk-…cdef")
        compose.onNodeWithTag("this-phone-provider-name").performTextReplacement("改名")
        compose.onNodeWithTag("this-phone-provider-save").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-edit:relay", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-delete").performScrollTo().performClick()
        assertEquals(listOf("use:null", "save:relay|改名|https://relay.example/v1||gpt-5.5", "remove:relay"), events)
    }

    @Test fun whileCodexRestartsOnAnotherServiceNothingElseCanBeChosenAndAFailedSaveSaysSo() {
        sheet(added = true, account = LocalAccount.Unknown, access = LocalAccess(listOf(relay), "relay"), applying = true)
        assertTrue(exists("this-phone-applying"))
        compose.onNodeWithTag("this-phone-official").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("this-phone-add-provider").performScrollTo().assertIsNotEnabled()
        saves = false
        sheet(added = true, account = LocalAccount.SignedOut)
        compose.onNodeWithTag("this-phone-add-provider").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-name").performTextInput("x")
        compose.onNodeWithTag("this-phone-provider-url").performTextInput("https://relay.example/v1")
        compose.onNodeWithTag("this-phone-provider-key").performTextInput("sk-test-0123456789")
        compose.onNodeWithTag("this-phone-provider-save").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-provider-problem").assertTextEquals("未能保存，请重试。")
        assertTrue(exists("this-phone-provider-key"))
    }

    @Test fun deviceCodeIsShownForThePersonToEnterAndSignedInNamesTheAccount() {
        sheet(added = true, account = LocalAccount.Code("https://auth.openai.com/codex/device", "ABCD-EFGHI"))
        compose.onNodeWithTag("this-phone-code").assertTextEquals("ABCD-EFGHI")
        compose.onNodeWithTag("this-phone-open-login").assertExists()
        assertFalse(exists("this-phone-chatgpt"))
        sheet(added = true, account = LocalAccount.SignedIn("person@example.com"))
        compose.onNodeWithTag("this-phone-signed-in").assertTextEquals("已登录 · person@example.com")
        compose.onNodeWithTag("this-phone-sign-out").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-stop").performScrollTo().performClick()
        assertEquals(listOf("sign-out", "stop"), events)
    }

    @Test fun projectFilesAreListedForExportAndNothingCanBeStartedWhileOneIsMoving() {
        var projects by mutableStateOf(dev.codexpocket.app.data.LocalProjects(listOf("alpha", "beta"), notice = "已导出 alpha。"))
        content {
            ThisPhoneSheet(true, LocalAccount.SignedIn("person@example.com"), null, {}, {}, {}, {}, {}, projects = projects)
        }
        compose.onNodeWithTag("this-phone-export:alpha").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("this-phone-project:beta", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("this-phone-transfer-notice").assertTextEquals("已导出 alpha。")
        compose.onNodeWithTag("this-phone-import").performScrollTo().assertIsEnabled()
        projects = projects.copy(busy = true, notice = null)
        compose.waitForIdle()
        compose.onNodeWithTag("this-phone-import").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("this-phone-export:alpha").performScrollTo().assertIsNotEnabled()
        assertTrue(exists("this-phone-transfer"))
        assertFalse(exists("this-phone-transfer-notice"))
    }

    @Test fun failureSaysSoAndLetsThePersonRetryOrReadAgain() {
        sheet(added = true, account = LocalAccount.Failed("登录未完成，请重试"))
        compose.onNodeWithTag("this-phone-failed").assertTextEquals("登录未完成，请重试")
        compose.onNodeWithText("重新读取登录状态").performScrollTo().performClick()
        compose.onNodeWithTag("this-phone-chatgpt").performScrollTo().performClick()
        assertEquals(listOf("refresh", "chatgpt"), events)
    }
}
