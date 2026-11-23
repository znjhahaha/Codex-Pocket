package dev.codexpocket.app.data

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.codexpocket.app.ui.AssistantAttachmentDraft
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AssistantAttachmentTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun source(bytes:ByteArray):File=File(context.cacheDir,UUID.randomUUID().toString()).apply {writeBytes(bytes)}
    @Test fun selectionCopiesActualBytesAndSendUsesExistingUploadAndTextInput()=runBlocking<Unit> {
        val image=source(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10,1,2))
        val text=source("正文不能截断\nsecond line".toByteArray())
        val files=copyAssistantAttachments(context,listOf(Uri.fromFile(image),Uri.fromFile(text)),4)
        image.delete();text.delete()
        var uploads=0
        val inputs=prepareAssistantAttachments(context,files,{true}) {p->
            uploads++;assertEquals("image/png",p.string("mime"));assertTrue(p.string("base64").isNotEmpty())
            buildJsonObject {put("attachmentId",UUID.randomUUID().toString())}
        }
        assertEquals(1,uploads);assertEquals("pocketImage",inputs[0].jsonObject.string("type"))
        assertEquals("正文不能截断\nsecond line",inputs[1].jsonObject.string("text"))
        assertTrue(files.all {assistantAttachmentFile(context,it).exists()})
    }
    @Test fun draftSurvivesFailureUnknownAndRecreationAndAckOnlyClearsSentAttachments()=runBlocking<Unit> {
        val file=source("old".toByteArray());val one=copyAssistantAttachments(context,listOf(Uri.fromFile(file)),4).single()
        val key=UUID.randomUUID().toString();val draft=AssistantAttachmentDraft(context,key);draft.add(listOf(one));draft.sent("original","text")
        var restored=AssistantAttachmentDraft(context,key);assertEquals(listOf(one),restored.files);assertEquals("original",restored.pendingId)
        restored.complete("unknown");assertEquals("original",restored.pendingId)
        restored.complete("rejected");assertNull(restored.pendingId);assertEquals(listOf(one),restored.files)
        restored.sent("second","text")
        val two=copyAssistantAttachments(context,listOf(Uri.fromFile(source("new".toByteArray()))),3).single();restored.add(listOf(two))
        restored.complete("accepted");restored=AssistantAttachmentDraft(context,key)
        assertEquals(listOf(two),restored.files);assertNull(restored.pendingId)
    }
    @Test fun uploadFailureOrConnectionReplacementKeepsLocalFileAndNeverCreatesUsableInput()=runBlocking<Unit> {
        val file=source(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),1))
        val files=copyAssistantAttachments(context,listOf(Uri.fromFile(file)),4)
        assertTrue(runCatching {prepareAssistantAttachments(context,files,{true}) {error("lost upload")}}.isFailure)
        var current=true
        assertTrue(runCatching {prepareAssistantAttachments(context,files,{current}) {current=false;buildJsonObject {put("attachmentId",UUID.randomUUID().toString())}}}.isFailure)
        assertTrue(assistantAttachmentFile(context,files.single()).exists())
    }
    @Test fun invalidUtf8AndUnsupportedBinaryAreRejectedWithoutTruncating()=runBlocking<Unit> {
        for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),byteArrayOf(0,1,2),ByteArray(256*1024+1){65})) {
            assertTrue(runCatching {copyAssistantAttachments(context,listOf(Uri.fromFile(source(bytes))),4)}.isFailure)
        }
    }
    @Test @Config(sdk=[28]) fun minimumSdkPreservesShortHeadersAndRecognizesEachSupportedFormat()=runBlocking<Unit> {
        val cases=listOf(
            byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10) to "image/png",
            byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()) to "image/jpeg",
            ("RIFF".toByteArray()+byteArrayOf(4,0,0,0)+"WEBP".toByteArray()) to "image/webp",
            "短文本".toByteArray() to "text/plain"
        )
        for((bytes,mime) in cases) {
            val attachment=copyAssistantAttachments(context,listOf(Uri.fromFile(source(bytes))),4).single()
            assertEquals(mime,attachment.mime)
            assertArrayEquals(bytes,assistantAttachmentFile(context,attachment).readBytes())
        }
    }
    @Test fun headerAccumulatesShortReadsAndStopsAtTwelveBytesOrEof() {
        for(size in listOf(0,3,8,11,12,20)) {
            val bytes=ByteArray(size) {it.toByte()}
            val input=object:ByteArrayInputStream(bytes) {
                override fun read(buffer:ByteArray,offset:Int,length:Int)=super.read(buffer,offset,minOf(length,2))
            }
            assertArrayEquals(bytes.take(12).toByteArray(),readAssistantAttachmentHeader(input))
            assertEquals((size-12).coerceAtLeast(0),input.available())
        }
    }
    @Test fun failedBatchRemovesBothItsEarlierCopyAndItsInvalidFile()=runBlocking<Unit> {
        val directory=File(context.filesDir,"attachments").apply {mkdirs()}
        val existing=directory.listFiles().orEmpty().map {it.name}.toSet()
        val valid=source("keep all text".toByteArray())
        val invalid=source(byteArrayOf(0,1,2))
        assertTrue(runCatching {copyAssistantAttachments(context,listOf(Uri.fromFile(valid),Uri.fromFile(invalid)),4)}.isFailure)
        assertEquals(existing,directory.listFiles().orEmpty().map {it.name}.toSet())
    }
}
