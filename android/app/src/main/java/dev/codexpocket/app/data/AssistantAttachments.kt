package dev.codexpocket.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Uses the same app-private files and broker image upload as ordinary messages. */
internal fun assistantAttachmentFile(context:Context,a:LocalAttachment):File {
    require(UUID.fromString(a.id).toString()==a.id) {"附件标识无效"}
    return File(File(context.filesDir,"attachments"),a.id)
}
internal fun readAssistantText(file:File):String {
    require(file.length() in 1..256*1024) {"文本附件需为 256 KiB 以内的 UTF-8 文件"}
    val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(file.readBytes())).toString()
    require(text.none {it=='\u0000' || it.code in 1..8 || it.code in 14..31}) {"此附件不是 UTF-8 文本"}
    return text
}
internal fun readAssistantAttachmentHeader(input:InputStream):ByteArray {
    val header=ByteArray(12)
    var size=0
    while(size<header.size) {
        val count=input.read(header,size,header.size-size)
        if(count<0) break
        size+=count
    }
    return header.copyOf(size)
}
internal suspend fun copyAssistantAttachments(context:Context,uris:List<Uri>,available:Int):List<LocalAttachment> = withContext(Dispatchers.IO) {
    require(uris.size<=available) {"每条消息最多 4 个附件"}
    val added=mutableListOf<LocalAttachment>()
    try {
        for(uri in uris) {
            val id=UUID.randomUUID().toString()
            val name=runCatching {context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            }}.getOrNull()?.replace(Regex("[\\r\\n\\u0000-\\u001f]")," ")?.take(128)?.takeIf(String::isNotBlank) ?: "附件"
            val file=File(File(context.filesDir,"attachments").apply {mkdirs()},id)
            try {
                context.contentResolver.openInputStream(uri)?.use {input-> file.outputStream().use {out->
                    val buffer=ByteArray(8192);var total=0
                    while(true) {val n=input.read(buffer);if(n<0) break;total+=n;require(total<=8*1024*1024) {"图片需小于 8 MiB"};out.write(buffer,0,n)}
                }} ?: error("无法读取所选附件")
                val header=file.inputStream().use(::readAssistantAttachmentHeader)
                val mime=when {
                    header.size>=8 && header.take(8)==listOf(0x89.toByte(),80.toByte(),78.toByte(),71.toByte(),13.toByte(),10.toByte(),26.toByte(),10.toByte())->"image/png"
                    header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() && header[2]==0xff.toByte()->"image/jpeg"
                    header.size>=12 && header.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF" && header.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WEBP"->"image/webp"
                    else->{readAssistantText(file);"text/plain"}
                }
                added+=LocalAttachment(id,name,mime)
            } catch(e:Exception) {file.delete();throw e}
        }
        added
    } catch(e:Exception) {added.forEach {assistantAttachmentFile(context,it).delete()};throw e}
}

internal suspend fun prepareAssistantAttachments(context:Context,attachments:List<LocalAttachment>,current:()->Boolean,
    upload:suspend (JsonObject)->JsonObject):JsonArray {
    require(attachments.size<=4 && attachments.map {it.id}.distinct().size==attachments.size) {"每条消息最多 4 个不同附件"}
    val inputs=mutableListOf<JsonElement>()
    for(a in attachments) {
        check(current()) {"发送连接已变更，附件草稿已保留"}
        val file=assistantAttachmentFile(context,a)
        if(a.mime=="text/plain") {
            val text=withContext(Dispatchers.IO) {readAssistantText(file)}
            inputs+=buildJsonObject {put("type","textFile");put("id",a.id);put("name",a.name);put("text",text)}
        } else {
            require(a.mime in setOf("image/png","image/jpeg","image/webp") && file.length() in 1..8*1024*1024) {"附件格式或大小无效"}
            val params=withContext(Dispatchers.IO) {buildJsonObject {put("name",a.name);put("mime",a.mime);put("base64",Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))}}
            check(current()) {"发送连接已变更，附件草稿已保留"}
            val result=upload(params)
            check(current()) {"发送连接已变更，附件草稿已保留"}
            val id=result.string("attachmentId");require(runCatching {UUID.fromString(id)}.isSuccess) {"附件上传未得到有效回执"}
            inputs+=buildJsonObject {put("type","pocketImage");put("attachmentId",id);put("name",a.name)}
        }
    }
    check(current()) {"发送连接已变更，附件草稿已保留"}
    return JsonArray(inputs)
}
