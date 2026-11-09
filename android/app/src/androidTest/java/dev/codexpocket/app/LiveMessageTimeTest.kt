package dev.codexpocket.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in read of one existing native item. No sends, settings writes, or synthetic timestamps. */
@RunWith(AndroidJUnit4::class)
class LiveMessageTimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeInsertedMessageAndGlassContours() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMessageTime") == "true")
        val threadId = requireNotNull(args.getString("threadId"))
        val itemId = requireNotNull(args.getString("itemId"))
        val expectedMs = requireNotNull(args.getString("expectedMs")).toLong()
        val repo = (compose.activity.application as PocketApplication).repository
        runBlocking { withTimeout(90_000) {
            repo.state.first { it.initialized }
            withContext(Dispatchers.Main) { repo.setForeground(true) }
            repo.state.first { it.online }
            repo.refresh().join()
            val chat = repo.state.value.chats.first { it.key.nativeId == threadId }
            repo.select(chat).join()
        } }
        val snapshot = repo.state.value
        assertTrue(snapshot.online)
        assertEquals(threadId, snapshot.selected?.key?.nativeId)
        val item = snapshot.timeline.items.values.single { it.itemId == itemId }
        assertEquals("userMessage", item.type)
        assertEquals(expectedMs, item.raw["startedAtMs"]?.jsonPrimitive?.long)
        assertEquals(MessageTimestamp(expectedMs), messageTimestamp(item, snapshot.timeline))
        val proof = File(compose.activity.getExternalFilesDir(null), "v054-message-time-proof").apply { mkdirs() }
        var theme by mutableStateOf("dark")
        var glass by mutableStateOf(false)
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) { PocketBackdrop { ProvideMarkdownRendering {
                CompositionLocalProvider(LocalDesktopImages provides DesktopImageSource { repo.desktopImage(it) }) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        Column(Modifier.fillMaxSize().layerBackdrop(backdrop).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            if (glass) repeat(20) { Text("阅读内容 · 背景采样与组件轮廓", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (!glass) TranscriptRowView(TranscriptRow(item.key, TranscriptKind.User, listOf(item)),
                                "native-time-proof", false, {}, {}, timeline = snapshot.timeline, onEdit = {})
                            else {
                                Spacer(Modifier.height(20.dp))
                                PocketGlassSurface(Modifier.width(240.dp), RoundedCornerShape(12.dp), backdrop, dense = true) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("提问：原位预览", style = MaterialTheme.typography.labelMedium)
                                        Text("邻近：正文保持清晰", style = MaterialTheme.typography.bodySmall)
                                        Text("308 / 489 · 已加载", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                PocketGlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), backdrop, dense = true) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("xhigh", color = effortAccent())
                                        Text("GPT-6-Astra ›", style = MaterialTheme.typography.labelMedium)
                                        Spacer(Modifier.height(12.dp))
                                        Box(Modifier.fillMaxWidth().height(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape))
                                    }
                                }
                                PocketGlassSurface(Modifier.size(48.dp).testTag("glass-rim-button"), CircleShape, backdrop, onClick = {}) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(PocketIcons.ArrowBack, "返回") }
                                }
                            }
                        }
                    }
                }
            } } }
        } }
        for (value in listOf("light", "dark")) {
            compose.runOnIdle { theme = value; glass = false }
            compose.onNodeWithTag("message-time:${item.key}").assertIsDisplayed()
            compose.onNodeWithContentDescription("发送时间：", substring = true).assertExists()
            capture(proof, "$value-real-inserted-message")
            compose.runOnIdle { glass = true }
            compose.waitForIdle()
            capture(proof, "$value-glass-contours")
            compose.onNodeWithTag("glass-rim-button").performTouchInput { down(center); advanceEventTime(100) }
            compose.mainClock.advanceTimeBy(80)
            capture(proof, "$value-glass-pressed")
            compose.onNodeWithTag("glass-rim-button").performTouchInput { up() }
        }
        assertTrue(repo.state.value.online)
        File(proof, "report.json").writeText(buildJsonObject {
            put("passed", true); put("nativeItemId", itemId); put("nativeStartedAtMs", expectedMs)
            put("displayed", true); put("themes", "light,dark"); put("modelRequests", 0)
            put("glassSamples", "12dp preview,22dp menu,circle button; resting and pressed")
        }.toString())
    }

    private fun capture(directory: File, name: String) {
        val latch = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        compose.activity.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
