package dev.codexpocket.app.ui

import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class V054ToolMediaTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun media(size: Int = 16, status: ToolMediaStatus = ToolMediaStatus.Ready) = ToolMediaRef("a".repeat(32), "runtime", "thread", "turn", "tool", "image/png", size.toLong(), status)
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { Surface { ProvideMarkdownRendering { block() } } } } }
        compose.waitForIdle()
    }
    private fun chunk(ref: ToolMediaRef, offset: Long, data: ByteArray, eof: Boolean) = ToolMediaChunk(ref.mediaId, ToolMediaStatus.Ready, ref.mimeType, ref.byteLength, offset, eof, Base64.encodeToString(data, Base64.NO_WRAP))
    @Test fun fixedAllocationChunkReaderChecksProgressIdentityAndMime() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) + ByteArray(TOOL_IMAGE_CHUNK_BYTES + 7)
        val ref = media(bytes.size)
        val offsets = mutableListOf<Long>()
        val result = readToolImage(ref, ToolMediaImageSource { owner, offset, cap ->
            assertEquals(ref, owner); assertEquals(TOOL_IMAGE_CHUNK_BYTES, cap); offsets.add(offset)
            val end = minOf(bytes.size, offset.toInt() + cap)
            chunk(owner, offset, bytes.copyOfRange(offset.toInt(), end), end == bytes.size)
        })
        assertArrayEquals(bytes, result)
        assertEquals(listOf(0L, TOOL_IMAGE_CHUNK_BYTES.toLong()), offsets)
        for (bad in listOf(
            chunk(ref, 1, byteArrayOf(1), false), chunk(ref, 0, byteArrayOf(), false),
            chunk(ref, 0, byteArrayOf(1), true), chunk(ref, 0, byteArrayOf(1), false).copy(mediaId = "wrong"),
            chunk(ref, 0, byteArrayOf(1), false).copy(size = MAX_TOOL_IMAGE_BYTES + 1),
        )) {
            try { readToolImage(ref, ToolMediaImageSource { _, _, _ -> bad }); fail("Accepted invalid chunk") } catch (_: IllegalArgumentException) { }
        }
        try { readToolImage(media(3), ToolMediaImageSource { owner, _, _ -> chunk(owner, 0, byteArrayOf(1, 2, 3), true) }); fail("Accepted invalid signature") } catch (failure: ToolMediaLoadException) { assertEquals(ToolMediaStatus.Unsupported, failure.status) }
    }
    @Test fun expiredAndOversizedReferencesDoNotFetch() = runBlocking {
        for (ref in listOf(media(status = ToolMediaStatus.Expired), media().copy(byteLength = MAX_TOOL_IMAGE_BYTES + 1))) {
            var called = false
            try { readToolImage(ref, ToolMediaImageSource { _, _, _ -> called = true; error("Should not fetch") }); fail("Accepted unavailable image") }
            catch (_: ToolMediaLoadException) { }
            assertFalse(called)
        }
    }
    @Test fun recoverableReferencesReadMetadataLazilyAndKeepOriginalOwner() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) + ByteArray(TOOL_IMAGE_CHUNK_BYTES + 7)
        val ref = media().copy(status = ToolMediaStatus.Recoverable, mimeType = "", byteLength = 0, contentPart = 3, connectionId = "connection")
        val offsets = mutableListOf<Long>()
        val restored = readToolImage(ref, ToolMediaImageSource { owner, offset, cap ->
            assertEquals(ref, owner); offsets += offset
            val end = minOf(bytes.size, offset.toInt() + cap)
            ToolMediaChunk(ref.mediaId, ToolMediaStatus.Ready, "image/png", bytes.size.toLong(), offset,
                end == bytes.size, Base64.encodeToString(bytes.copyOfRange(offset.toInt(), end), Base64.NO_WRAP))
        })
        assertArrayEquals(bytes, restored)
        assertEquals(listOf(0L, TOOL_IMAGE_CHUNK_BYTES.toLong()), offsets)
        assertTrue(ref.readable)
    }
    @Test fun pureToolImageHasDefaultThumbnailExpandedGalleryAndNoUnavailableText() {
        val item = TimelineItem.from("turn", buildJsonObject { put("id", "tool"); put("type", "dynamicToolCall"); put("status", "completed"); put("tool", "inspect")
            put("toolOutputBlocks", buildJsonArray { add(buildJsonObject {
                put("type", "image"); put("status", "expired"); put("mediaId", "a".repeat(32)); put("runtimeId", "runtime")
                put("threadId", "thread"); put("turnId", "turn"); put("itemId", "tool"); put("mimeType", "image/png"); put("byteLength", 16)
            }) }) })
        content { NativeActivityTrace(TranscriptRow("activity", TranscriptKind.Activity, listOf(item)), "test", false, {}, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool-media-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool-media-grid").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.v03_output_unavailable)).assertDoesNotExist()
        compose.onNodeWithTag("activity-toggle:${item.key}").performClick()
        compose.onNodeWithTag("tool-output-blocks").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("图片已过期").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("图片 1").performClick()
        compose.onNodeWithTag("image-viewer").assertExists()
        compose.onNodeWithContentDescription("关闭").performClick()
    }
    @Test fun mixedOutputPreservesProseAroundImagesAndNeverUsesDesktopPathSource() {
        var paths = 0
        content { CompositionLocalProvider(LocalDesktopImages provides DesktopImageSource { paths++; null }) {
            ToolOutputContent(listOf(ToolOutputBlock.Text("before"), ToolOutputBlock.Image(media(status = ToolMediaStatus.Unsupported)), ToolOutputBlock.Text("after")))
        } }
        compose.onNodeWithText("before").assertExists(); compose.onNodeWithText("after").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("暂不支持这张图片").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, paths) }
    }
    @Test fun chipsOpenReadOnlyPreviewAndExposeCompletePathInDetails() {
        val file = MentionedFile("a-very-long-report-name-that-must-keep-its-extension.docx", "D:/work/full-folder/report.docx", image = false)
        var opened: String? = null
        content { CompositionLocalProvider(LocalAttachmentFilePreview provides { opened = it }) { AttachmentChips("test", listOf(file), false) } }
        assertTrue(chipName(file.name).endsWith(".docx")); assertTrue(chipName(file.name).contains('…'))
        assertEquals(PocketIcons.Code, attachmentTypeIcon(MentionedFile("media.rs", "D:/work/media.rs", false)))
        compose.onNodeWithTag("attachment-chip:${file.path}").performClick()
        compose.runOnIdle { assertEquals(file.path, opened) }
        compose.onNodeWithTag("attachment-chip:${file.path}").performTouchInput { longClick() }
        compose.onNodeWithTag("attachment-full-path").assertTextEquals(file.path)
        compose.onNodeWithText("复制完整路径").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(file.path, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
