package dev.codexpocket.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Read only from the signed APK, beside the manifest that pins the archive contents. */
internal data class PhoneEnvironmentDownload(val url: HttpUrl) {
    companion object {
        fun parse(text: String): PhoneEnvironmentDownload {
            require(text.length <= 16 * 1024)
            val value = Json.parseToJsonElement(text).jsonObject
            require(value.getValue("schema").jsonPrimitive.int == 1)
            val url = value.getValue("url").jsonPrimitive.content.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            return PhoneEnvironmentDownload(url)
        }
    }
}

/** Cancels a blocked network read as well as extraction, without interrupting runtime processes. */
internal class PhonePreparationCancellation {
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    fun cancel() { cancelled = true; call?.cancel() }
    fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Phone preparation paused") }
    fun attach(value: Call?) { call = value; if (cancelled) value?.cancel(); check() }
}

/** The archive stays a .part until the installer has checked its pinned length and digest. */
internal class PhoneEnvironmentDownloader(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .followSslRedirects(false).build()) {
    fun download(source: PhoneEnvironmentDownload, total: Long, partial: File,
        cancellation: PhonePreparationCancellation, progress: (Long, Long) -> Unit): File {
        require(total > 0)
        cancellation.check()
        partial.parentFile?.mkdirs()
        var offset = partial.takeIf(File::isFile)?.length() ?: 0L
        if (offset > total) { FileOutputStream(partial).close(); offset = 0 }
        progress(offset, total)
        if (offset == total) return partial
        val request = Request.Builder().url(source.url).header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val call = client.newCall(request)
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                cancellation.check()
                if (!response.request.url.isHttps) throw IOException("Environment redirect is not HTTPS")
                val append = when (response.code) {
                    200 -> false
                    206 -> {
                        val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        if (range == null || range.groupValues[1].toLongOrNull() != offset ||
                            range.groupValues[2].toLongOrNull() != total - 1 || range.groupValues[3].toLongOrNull() != total)
                            throw IOException("Environment range does not match the requested archive")
                        true
                    }
                    else -> throw IOException("Environment download HTTP ${response.code}")
                }
                if (!append) offset = 0
                val body = response.body ?: throw IOException("Environment download has no body")
                body.byteStream().use { input -> FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (offset + count > total) {
                            // Never retain an oversized response as a seemingly complete valid part.
                            output.channel.truncate(0)
                            throw IOException("Environment download exceeds pinned size")
                        }
                        output.write(buffer, 0, count); offset += count
                        progress(offset, total)
                    }
                    output.fd.sync()
                } }
                cancellation.check()
                if (offset != total) throw IOException("Environment download was interrupted")
            }
            return partial
        } catch (error: IOException) {
            cancellation.check()
            throw error
        } finally { cancellation.attach(null) }
    }
}
