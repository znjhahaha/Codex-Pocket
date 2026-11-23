package dev.codexpocket.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.ui.decodePairingAlbum
import dev.codexpocket.wire.PairingCode
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in QA probe for a picker URI already granted to the target app. Run this class alone.
 * Uses only ContentResolver reads and the real album decoder; it opens no Activity or repository.
 * Status contains counts and fixture labels, never URI contents, decoded payloads or exceptions.
 */
@RunWith(AndroidJUnit4::class)
class PairingPickerReadOnlyProbeTest {
    @Test fun readsGrantedPickerUriAndDecodesTwice() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with pairingPickerProbe=true", args.getString("pairingPickerProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName in setOf("dev.codexpocket.app.qa", "dev.codexpocket.app.qa20261117")) {
            "Picker probe requires a QA target"
        }
        val expected = args.getString("pairingPickerExpected")
        require(expected == "A" || expected == "B") { "Set pairingPickerExpected to A or B" }
        val uri = Uri.parse(requireNotNull(args.getString("pairingPickerUri")) { "Set pairingPickerUri from the QA picker grant" })
        require(uri.scheme == "content" && uri.authority == "media" &&
            (uri.path?.startsWith("/picker/") == true || uri.path?.startsWith("/picker_get_content/") == true)) {
            "Expected a Photo Picker content URI"
        }
        val fixtureA = instrumentation.context.assets.open("pairing-picker/album-A.png").use { it.readBytes() }
        val fixtureB = instrumentation.context.assets.open("pairing-picker/album-B.png").use { it.readBytes() }
        var status = "reading"
        var byteCount: Int? = null
        var matchesA: Boolean? = null
        var matchesB: Boolean? = null
        var first: String? = null
        var second: String? = null
        try {
            val bytes = target.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                ?: throw FileNotFoundException()
            byteCount = bytes.size
            matchesA = bytes.contentEquals(fixtureA)
            matchesB = bytes.contentEquals(fixtureB)
            check(bytes.size <= ByteLimit) { "Picker content exceeds the album decoder limit" }
            runBlocking {
                first = decodedFixture(target, uri)
                second = decodedFixture(target, uri)
            }
            status = "observed"
        } catch (_: SecurityException) {
            status = "permission_missing"
            assumeTrue("QA app has no URI grant; select the QA fixture again in the picker before rerunning", false)
        } catch (_: FileNotFoundException) {
            status = "unavailable"
            assumeTrue("Picker content is unavailable; select the QA fixture again before rerunning", false)
        } catch (_: Exception) {
            status = "read_or_decode_failed"
            throw AssertionError("Picker probe failed; inspect the sanitized pairingPickerReport")
        } finally {
            val report = buildJsonObject {
                put("status", status)
                put("expectedFixture", expected)
                put("fixtureAByteCount", fixtureA.size)
                put("fixtureBByteCount", fixtureB.size)
                byteCount?.let {
                    put("byteCount", it)
                    put("byteCountComplete", it <= ByteLimit)
                }
                matchesA?.let { put("matchesFixtureA", it) }
                matchesB?.let { put("matchesFixtureB", it) }
                first?.let { put("firstDecodedFixture", it); put("firstNameMatchesExpected", it == expected) }
                second?.let { put("secondDecodedFixture", it); put("secondNameMatchesExpected", it == expected) }
                if (first != null && second != null) put("decodedFixtureLabelsAgree", first == second)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("pairingPickerReport", report.toString()) })
        }
        assertTrue("Picker bytes do not match the expected fixture; inspect pairingPickerReport",
            if (expected == "A") matchesA == true else matchesB == true)
        assertTrue("First album decode has an unexpected computer name; inspect pairingPickerReport", first == expected)
        assertTrue("Second album decode has an unexpected computer name; inspect pairingPickerReport", second == expected)
    }

    private suspend fun decodedFixture(context: Context, uri: Uri): String {
        val payload = decodePairingAlbum(context, uri) ?: return "not_decoded"
        val name = try { PairingCode.parse(payload).computerName } catch (_: IllegalArgumentException) { return "invalid_pairing" }
        return when (name) {
            "Task6 fixture A" -> "A"
            "Task6 fixture B" -> "B"
            else -> "other_name"
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() <= ByteLimit) {
            val count = input.read(buffer, 0, minOf(buffer.size, ByteLimit + 1 - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object { const val ByteLimit = 12 * 1024 * 1024 }
}
