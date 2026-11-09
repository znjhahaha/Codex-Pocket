package dev.codexpocket.app.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.PairingPhase
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real pairing screen and modal on the host; no camera or repository connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingConfirmationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var now = 100L
    private lateinit var scan: PairingScannerState
    private lateinit var owner: HostLifecycle
    private var generation by mutableIntStateOf(0)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var calls = 0
    private var submittedName: String? = null

    private class HostLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun fixture(expiry: Long = 200) = """{"v":1,"relayUrl":"wss://relay.example/pocket","room":"fixture","phoneToken":"fixture-phone-secret","certificateSha256":"${"ab".repeat(32)}","pairingToken":"fixture-pair-secret","expiresAt":$expiry,"computerName":"Desktop"}"""

    private fun screen(albumUri: Uri? = null) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.CAMERA)
        compose.activity.runOnUiThread {
            owner = HostLifecycle().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
            val resultOwner: ActivityResultRegistryOwner = if (albumUri == null) compose.activity else object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                        options: ActivityOptionsCompat?) {
                        dispatchResult(requestCode, albumUri)
                    }
                }
            }
            compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false,
                        LocalLifecycleOwner provides owner, LocalSaveableStateRegistry provides registry,
                        LocalActivityResultRegistryOwner provides resultOwner) {
                        PocketBackdrop {
                            if (generation >= 0) {
                                scan = remember { PairingScannerState { now } }
                                EmbeddedPairingScreen(false, { _, name -> calls++; submittedName = name },
                                    PairingPhase.Idle, null, null, scan)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun receive(value: String = fixture()) {
        compose.runOnIdle { scan.receive(value) }
        compose.waitForIdle()
    }

    private fun assertNoSecretsInUi() {
        for (secret in listOf(fixture(), "relay.example", "fixture-phone-secret", "fixture-pair-secret", "\"room\"", "ab".repeat(32))) {
            compose.onAllNodes(hasText(secret, substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test fun scanImmediatelyShowsExplicitConfirmationAndConnectConsumesOnce() {
        screen()
        receive()
        compose.onNodeWithTag("pair-confirm-title").assertTextEquals("连接电脑")
        compose.onNodeWithTag("pair-confirm-computer").assertTextEquals("Desktop")
        compose.onNodeWithTag("pair-confirm-cancel").assertTextEquals("取消")
        compose.onNodeWithTag("pair-confirm-connect").assertTextEquals("连接")
        compose.onNodeWithTag("pair-confirm-name").performTextReplacement("  ")
        compose.onNodeWithTag("pair-confirm-connect").assertIsNotEnabled()
        compose.onNodeWithTag("pair-confirm-name").performTextReplacement("  My phone  ")
        assertTrue(scan.paused)
        assertEquals(0, calls)
        assertNoSecretsInUi()
        compose.runOnIdle { assertEquals(ScanDecision.Duplicate, scan.receive(fixture())) }
        compose.onAllNodesWithTag("pair-confirm-title").assertCountEquals(1)

        val click = compose.onNodeWithTag("pair-confirm-connect").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals("My phone", submittedName)
        assertNull(scan.computerName)
        assertTrue(scan.paused)
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ScanDecision.Duplicate, scan.receive(fixture())) }
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
    }

    @Test fun albumQrUsesTheSameImmediateConfirmationWithoutAutoPairing() {
        val matrix = MultiFormatWriter().encode(fixture(), BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("pairing-fixture", ".png", compose.activity.cacheDir)
        try {
            for (y in 0 until 512) for (x in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen(Uri.fromFile(file))
            compose.onNodeWithText("相册").performScrollTo().performClick()
            compose.waitUntil(15_000) { scan.computerName != null }
            compose.onNodeWithTag("pair-confirm-computer").assertTextEquals("Desktop")
            compose.onNodeWithTag("pair-confirm-connect").assertIsEnabled()
            assertTrue(scan.paused)
            assertEquals(0, calls)
            assertNoSecretsInUi()
            compose.onNodeWithTag("pair-confirm-cancel").performClick()
            compose.waitForIdle()
            assertFalse(scan.paused)
            assertEquals(0, calls)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun cancelDiscardsResultAndAllowsAnotherScanWithoutPairing() {
        screen()
        receive()
        compose.onNodeWithTag("pair-confirm-cancel").performClick()
        compose.waitForIdle()
        assertNull(scan.computerName)
        assertFalse(scan.paused)
        assertTrue(scannerShouldRun(true, true, scan.paused, false, false))
        assertEquals(0, calls)
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ScanDecision.Duplicate, scan.confirm("Phone") { _, _ -> calls++ }) }
        receive()
        compose.onNodeWithTag("pair-confirm-title").assertExists()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()
        assertFalse(scan.paused)
        assertEquals(0, calls)
    }

    @Test fun codeExpiringInDialogNeverPairsAndHasSeparateFeedback() {
        screen()
        receive()
        compose.runOnIdle { now = 200 }
        compose.onNodeWithTag("pair-confirm-connect").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        compose.onNodeWithText("配对码已过期，请在电脑上生成新的配对码").assertExists()
        assertEquals(0, calls)
        assertNull(scan.computerName)
        assertNoSecretsInUi()
    }

    @Test fun invalidAndExpiredScansDoNotOpenConfirmation() {
        screen()
        receive("invalid fixture")
        compose.onNodeWithText("不是有效的 Codex Pocket 配对码").assertExists()
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        compose.runOnIdle { scan.cancel() }
        receive(fixture(1))
        compose.onNodeWithText("配对码已过期，请在电脑上生成新的配对码").assertExists()
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun backgroundAndResumeKeepConfirmationPausedUntilExplicitConnect() {
        screen()
        receive()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag("pair-confirm-title").assertExists()
        compose.onNodeWithTag("pair-confirm-connect").assertIsNotEnabled()
        assertTrue(scan.paused)
        assertEquals(0, calls)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("pair-confirm-connect").assertIsEnabled().performClick()
        assertEquals(1, calls)
    }

    @Test fun savedStateRestoresPhoneNameWithoutRestoringQrOrConfirmation() {
        screen()
        receive()
        compose.onNodeWithTag("pair-confirm-name").performTextReplacement("Saved phone")
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle { saved = registry.performSave() }
        val savedText = saved.toString()
        for (secret in listOf(fixture(), "fixture-phone-secret", "fixture-pair-secret", "relay.example", "Desktop")) {
            assertFalse(savedText.contains(secret))
        }
        assertTrue(savedText.contains("Saved phone"))
        val previousScan = scan
        compose.runOnIdle { generation = -1 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ScanDecision.Duplicate, previousScan.confirm("Phone") { _, _ -> calls++ })
        }
        compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; generation = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
        assertNull(scan.computerName)
        assertFalse(scan.paused)
        assertEquals(0, calls)
        receive()
        compose.onNodeWithTag("pair-confirm-name").assertTextContains("Saved phone")
    }
}
