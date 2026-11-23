package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalProviderModelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private var saved: LocalProviderDraft? = null
    private val provider = LocalProviderSummary("saved", "我的服务", "https://relay.example/v1", "old-model", "sk-…test")

    private fun content(access: LocalAccess = LocalAccess(), loader: LocalProviderModelLoader = { LocalProviderModelResult.Models(emptyList()) }) {
        compose.mainClock.autoAdvance = false
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlass provides false,
                LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) { PocketOverlayHost {
                ThisPhoneSheet(true, LocalAccount.SignedOut, null, {}, {}, {}, {}, {}, access = access,
                    onSaveProvider = { saved = it; true }, onFetchModels = loader)
            } }
        } } }
        settle()
    }
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun scroll(tag: String, unmerged: Boolean = false): SemanticsNodeInteraction {
        // Check presence without Compose's full error-tree dump, then let semanticsScrollBy finish.
        // Its internal loop cannot make progress with a frozen clock. All kit motion is disabled above.
        compose.waitUntil(5_000) { settle(); compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)
        compose.mainClock.autoAdvance = true
        try { node.performScrollTo(); compose.waitForIdle() } finally { compose.mainClock.autoAdvance = false }
        settle()
        return node
    }
    private fun select(id: String) {
        val list = scroll("this-phone-provider-model-list")
        compose.mainClock.autoAdvance = true
        try { list.performScrollToNode(hasTestTag("this-phone-provider-model-option:$id")); compose.waitForIdle() }
        finally { compose.mainClock.autoAdvance = false }
        settle()
        compose.onNodeWithTag("this-phone-provider-model-option:$id").performClick()
        settle()
    }
    private fun add() { scroll("this-phone-add-provider").performClick(); settle() }
    private fun fill() {
        scroll("this-phone-provider-name").performTextReplacement("我的服务"); settle()
        scroll("this-phone-provider-url").performTextReplacement("https://relay.example/v1"); settle()
        scroll("this-phone-provider-key").performTextReplacement("sk-test-key-12345678"); settle()
    }
    private fun fetch() { scroll("this-phone-provider-fetch-models").performClick(); settle() }
    private fun screenshot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/provider-models/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun modelDiscoveryIsOfferedAlongsideManualModelInput() {
        content()
        add()
        compose.onNodeWithTag("this-phone-provider-model").assertExists()
        scroll("this-phone-provider-fetch-models").assertTextContains("获取模型列表")
        compose.onNodeWithTag("this-phone-provider-fetch-models").assertIsNotEnabled()
    }

    @Test fun aLargeCatalogCanBeSearchedSelectedAndThenOverriddenManually() {
        content(loader = { LocalProviderModelResult.Models((1..200).map { "vendor/model-$it" } + "ccs-aggit/gpt-6.1-sol") })
        add(); fill(); fetch()
        scroll("this-phone-provider-model-search").performTextInput("AGGIT")
        settle()
        select("ccs-aggit/gpt-6.1-sol")
        compose.onNodeWithTag("this-phone-provider-model").assertTextContains("ccs-aggit/gpt-6.1-sol")
        scroll("this-phone-provider-model-search")
        screenshot("search-and-selection")
        scroll("this-phone-provider-model").performTextReplacement("manual/model"); settle()
        scroll("this-phone-provider-save").performClick()
        assertEquals("manual/model", saved?.model)
    }

    @Test fun savedProviderRequestsItsOwnKeyWithoutPuttingTheKeyInTheField() {
        var request: LocalProviderModelRequest? = null
        content(LocalAccess(listOf(provider), provider.id)) { request = it; LocalProviderModelResult.Models(listOf("new-model")) }
        scroll("this-phone-provider-edit:saved", unmerged = true).performClick()
        settle(); fetch()
        assertEquals("saved", request?.providerId)
        assertEquals("", request?.apiKey)
        compose.onNodeWithTag("this-phone-provider-key").assertTextContains("留空则继续使用 sk-…test")
        compose.onNodeWithTag("this-phone-provider-model").assertTextContains("old-model")
    }

    @Test fun changingTheAddressCancelsLoadingAndIgnoresALateResult() {
        val gate = CompletableDeferred<LocalProviderModelResult>()
        content { withContext(NonCancellable) { gate.await() } }
        add(); fill(); fetch()
        compose.onNodeWithTag("this-phone-provider-models-loading").assertExists()
        scroll("this-phone-provider-url").performTextReplacement("https://other.example/v1")
        settle()
        compose.onNodeWithTag("this-phone-provider-models-loading").assertDoesNotExist()
        compose.runOnIdle { gate.complete(LocalProviderModelResult.Models(listOf("stale-model"))) }
        settle()
        compose.onNodeWithTag("this-phone-provider-model-search").assertDoesNotExist()
        compose.onNodeWithTag("this-phone-provider-model-option:stale-model").assertDoesNotExist()
    }

    @Test fun changingTheKeyClearsTheOldCatalogAndSearch() {
        content { LocalProviderModelResult.Models(listOf("old-model")) }
        add(); fill(); fetch()
        scroll("this-phone-provider-model-search").performTextInput("no match")
        settle()
        compose.onNodeWithTag("this-phone-provider-models-no-match").assertExists()
        scroll("this-phone-provider-key").performTextReplacement("sk-other-key-12345678")
        settle()
        compose.onNodeWithTag("this-phone-provider-model-search").assertDoesNotExist()
        fetch()
        compose.onNodeWithTag("this-phone-provider-model-option:old-model").assertExists()
    }

    @Test fun closingAndReopeningDoesNotAdoptTheOldFormsRequest() {
        val gate = CompletableDeferred<LocalProviderModelResult>()
        var calls = 0
        content { if (calls++ == 0) withContext(NonCancellable) { gate.await() } else LocalProviderModelResult.Models(listOf("current-model")) }
        add(); fill(); fetch()
        scroll("this-phone-provider-cancel").performClick()
        settle(); add(); fill(); fetch()
        compose.runOnIdle { gate.complete(LocalProviderModelResult.Models(listOf("stale-model"))) }
        settle()
        compose.onNodeWithTag("this-phone-provider-model-option:current-model").assertExists()
        compose.onNodeWithTag("this-phone-provider-model-option:stale-model").assertDoesNotExist()
    }

    @Test fun emptyAuthenticationAndNetworkResultsExplainRecoveryAndKeepManualEntry() {
        var next: LocalProviderModelResult = LocalProviderModelResult.Models(emptyList())
        content { next }
        add(); fill(); fetch()
        compose.onNodeWithTag("this-phone-provider-models-empty").assertTextContains("手动填写模型", substring = true)
        next = LocalProviderModelResult.Failed(LocalProviderModelProblem.Authentication)
        fetch()
        compose.onNodeWithTag("this-phone-provider-models-problem").assertTextContains("鉴权失败", substring = true)
        scroll("this-phone-provider-models-problem")
        screenshot("authentication-failure")
        next = LocalProviderModelResult.Failed(LocalProviderModelProblem.Network)
        fetch()
        compose.onNodeWithTag("this-phone-provider-models-problem").assertTextContains("网络", substring = true)
        scroll("this-phone-provider-model").performTextReplacement("manual/model"); settle()
        scroll("this-phone-provider-save").performClick()
        assertEquals("manual/model", saved?.model)
    }
}
