package dev.codexpocket.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.PairingPhase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real album action and decoder; only the external picker result is supplied by the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingAlbumDocumentTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val launches = mutableListOf<Intent>()
    private val scanner = PairingScannerState { 100L }
    private var pairingCalls = 0

    private class HostLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun screen(resultUri: Uri? = null) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.CAMERA)
        compose.activity.runOnUiThread {
            val owner = HostLifecycle().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
            val resultOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                        options: ActivityOptionsCompat?) {
                        launches += contract.createIntent(compose.activity, input)
                        dispatchResult(requestCode, resultUri)
                    }
                }
            }
            compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlass provides false,
                        LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides resultOwner) {
                        PocketBackdrop {
                            EmbeddedPairingScreen(false, { _, _ -> pairingCalls++ }, PairingPhase.Idle,
                                null, null, scanner)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun albumRequestsSingleImageDocument() {
        screen()
        compose.onNodeWithText("相册").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = launches.single()
            assertEquals("Use the document entry instead of the stale Photo Picker content route",
                Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertEquals("*/*", intent.type)
            assertArrayEquals(arrayOf("image/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertEquals(0, pairingCalls)
        }
        compose.onNodeWithTag("pair-confirm-title").assertDoesNotExist()
    }

    @Test fun selectingSameDocumentAfterReplacementReadsAndConfirmsNewQr() {
        val file = File.createTempFile("pairing-same-document", ".png", compose.activity.cacheDir)
        val uri = Uri.parse("content://dev.codexpocket.test.documents/document/task6-qa-pairing.png")
        val opens = AtomicInteger()
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) {
            opens.incrementAndGet()
            file.inputStream()
        }
        try {
            writeFixture(file, "A")
            screen(uri)
            selectAndExpect("A")
            assertEquals("The first selection reads the document", 1, opens.get())
            compose.onNodeWithTag("pair-confirm-cancel").performClick()
            compose.waitForIdle()

            // Same file, same URI, same screen: only the QR image contents change.
            writeFixture(file, "B")
            selectAndExpect("B")
            assertEquals("Reselecting the same URI must reopen its current contents", 2, opens.get())
            assertEquals(2, launches.size)
            assertEquals(0, pairingCalls)
            compose.onNodeWithTag("pair-confirm-cancel").performClick()
            compose.waitForIdle()
            assertFalse(scanner.paused)
            assertEquals(0, pairingCalls)
        } finally {
            file.delete()
        }
    }

    private fun selectAndExpect(label: String) {
        compose.onNodeWithText("相册").performScrollTo().performClick()
        compose.waitUntil(15_000) { scanner.computerName == "Task6 fixture $label" }
        compose.onNodeWithTag("pair-confirm-computer").assertTextEquals("Task6 fixture $label")
        compose.onNodeWithTag("pair-confirm-connect").assertIsEnabled()
        assertEquals(0, pairingCalls)
    }

    private fun writeFixture(file: File, label: String) {
        val payload = """{"v":1,"relayUrl":"wss://fixture.invalid/pocket","room":"fixture","phoneToken":"fixture-phone-secret","certificateSha256":"${"ab".repeat(32)}","pairingToken":"fixture-pair-secret","expiresAt":5000000000,"computerName":"Task6 fixture $label"}"""
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 512) for (x in 0 until 512) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
