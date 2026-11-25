package dev.codexpocket.app.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneEnvironmentDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = PhoneEnvironmentDownload.parse("""{"schema":1,"url":"https://example.test/fixed.zip"}""")
    private fun client(reply: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()
    private fun response(request: Request, code: Int, body: String, range: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody()).apply { if (range != null) header("Content-Range", range) }.build()

    @Test fun onlyFixedHttpsUrlsAreAccepted() {
        for (bad in listOf("http://example.test/a.zip", "https://user:password@example.test/a.zip", "https://example.test/a.zip#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { PhoneEnvironmentDownload.parse("""{"schema":1,"url":"$bad"}""") }
        }
    }
    @Test fun resumesExactRangeAndReportsTotalBytes() {
        val part = temporary.newFile().apply { writeText("abc") }; var shown = 0L
        val downloader = PhoneEnvironmentDownloader(client { request ->
            assertEquals("bytes=3-", request.header("Range"))
            assertEquals("identity", request.header("Accept-Encoding"))
            response(request, 206, "def", "bytes 3-5/6")
        })
        downloader.download(url, 6, part, PhonePreparationCancellation()) { bytes, total -> shown = bytes; assertEquals(6L, total) }
        assertEquals("abcdef", part.readText()); assertEquals(6L, shown)
    }
    @Test fun rangeIgnoredRestartsWithoutAppending() {
        val part = temporary.newFile().apply { writeText("abc") }
        PhoneEnvironmentDownloader(client { response(it, 200, "abcdef") })
            .download(url, 6, part, PhonePreparationCancellation()) { _, _ -> }
        assertEquals("abcdef", part.readText())
    }
    @Test fun wrongRangeAndNetworkErrorKeepPriorPartialBytes() {
        val part = temporary.newFile().apply { writeText("abc") }
        assertThrows(IOException::class.java) {
            PhoneEnvironmentDownloader(client { response(it, 206, "def", "bytes 2-4/6") })
                .download(url, 6, part, PhonePreparationCancellation()) { _, _ -> }
        }
        assertEquals("abc", part.readText())
        assertThrows(IOException::class.java) {
            PhoneEnvironmentDownloader(client { throw IOException("offline") })
                .download(url, 6, part, PhonePreparationCancellation()) { _, _ -> }
        }
        assertEquals("abc", part.readText())
    }
    @Test fun cancellationPreservesPartialAndNextAttemptResumes() {
        val part = temporary.newFile(); val cancellation = PhonePreparationCancellation()
        val downloader = PhoneEnvironmentDownloader(client { response(it, 200, "abcdef") })
        assertThrows(CancellationException::class.java) {
            downloader.download(url, 6, part, cancellation) { bytes, _ -> if (bytes > 0) cancellation.cancel() }
        }
        assertEquals("abcdef", part.readText())
        // A completed .part needs no request, but still goes through integrity verification at installation.
        PhoneEnvironmentDownloader(client { fail("Should reuse complete part"); response(it, 500, "") })
            .download(url, 6, part, PhonePreparationCancellation()) { _, _ -> }
    }
    @Test fun shortResponseIsRetainedForRetry() {
        val part = temporary.newFile()
        assertThrows(IOException::class.java) {
            PhoneEnvironmentDownloader(client { response(it, 200, "abc") })
                .download(url, 6, part, PhonePreparationCancellation()) { _, _ -> }
        }
        assertEquals("abc", part.readText())
    }

    @Test fun cancellationDuringTransferResumesFromTheBytesActuallySaved() {
        val body = "x".repeat(300_000); val part = temporary.newFile(); val cancellation = PhonePreparationCancellation()
        assertThrows(CancellationException::class.java) {
            PhoneEnvironmentDownloader(client { response(it, 200, body) })
                .download(url, body.length.toLong(), part, cancellation) { bytes, _ -> if (bytes > 0) cancellation.cancel() }
        }
        val offset = part.length()
        assertTrue(offset > 0 && offset < body.length)
        PhoneEnvironmentDownloader(client {
            assertEquals("bytes=$offset-", it.header("Range"))
            response(it, 206, body.substring(offset.toInt()), "bytes $offset-${body.lastIndex}/${body.length}")
        }).download(url, body.length.toLong(), part, PhonePreparationCancellation()) { _, _ -> }
        assertEquals(body, part.readText())
    }
}
