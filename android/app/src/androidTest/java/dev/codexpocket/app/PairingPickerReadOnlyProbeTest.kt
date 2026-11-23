package dev.codexpocket.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.codexpocket.app.ui.decodePairingAlbum
import dev.codexpocket.wire.PairingCode
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in QA probes for a picker URI granted through the existing QA UI. Run one method alone.
 * Uses only ContentResolver reads and the real album decoder; it opens no Activity or repository.
 * Status contains counts and fixture labels, never URI contents, decoded payloads or exceptions.
 */
@RunWith(AndroidJUnit4::class)
class PairingPickerReadOnlyProbeTest {
    @Test fun readsGrantedPickerUriAndDecodesTwice() {
        assumeTrue("Opt in with pairingPickerProbe=true",
            InstrumentationRegistry.getArguments().getString("pairingPickerProbe") == "true")
        probe(useGrantedActivity = false)
    }

    @Test fun readsPickerUriFromAuthorizedActivityAndDecodesTwice() {
        assumeTrue("Opt in with pairingPickerActivityProbe=true",
            InstrumentationRegistry.getArguments().getString("pairingPickerActivityProbe") == "true")
        probe(useGrantedActivity = true)
    }

    @Test fun readsDocumentUriFromAuthorizedActivityAndDecodesTwice() {
        assumeTrue("Opt in with pairingDocumentActivityProbe=true",
            InstrumentationRegistry.getArguments().getString("pairingDocumentActivityProbe") == "true")
        probe(useGrantedActivity = true, useDocumentUri = true)
    }

    private fun probe(useGrantedActivity: Boolean, useDocumentUri: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName in setOf("dev.codexpocket.app.qa", "dev.codexpocket.app.qa20261117")) {
            "Picker probe requires a QA target"
        }
        val expected = args.getString("pairingPickerExpected")
        require(expected == "A" || expected == "B") { "Set pairingPickerExpected to A or B" }
        val uri = Uri.parse(requireNotNull(args.getString("pairingPickerUri")) { "Set pairingPickerUri from the QA picker grant" })
        if (useDocumentUri) {
            require(uri.scheme == "content" && uri.authority in setOf(
                "com.android.providers.downloads.documents", "com.android.externalstorage.documents",
                "com.android.providers.media.documents") && DocumentsContract.isDocumentUri(target, uri)) {
                "Expected a standard document URI selected for the QA fixture"
            }
        } else {
            require(uri.scheme == "content" && uri.authority == "media" &&
                (uri.path?.startsWith("/picker/") == true || uri.path?.startsWith("/picker_get_content/") == true)) {
                "Expected a Photo Picker content URI"
            }
        }
        val fixtureA = instrumentation.context.assets.open("pairing-picker/album-A.png").use { it.readBytes() }
        val fixtureB = instrumentation.context.assets.open("pairing-picker/album-B.png").use { it.readBytes() }
        var status = "reading"
        var stage = "context"
        var activityObservation: ActivityObservation? = null
        var readContext: Context = target
        var byteCount: Int? = null
        var matchesA: Boolean? = null
        var matchesB: Boolean? = null
        var first: String? = null
        var second: String? = null
        try {
            if (useGrantedActivity) {
                stage = "waiting_for_activity_grant"
                report(buildJsonObject {
                    put("status", "awaiting_activity_grant")
                    put("waitMillis", ActivityWaitMillis)
                    put("targetContext", contextFacts(target, uri))
                })
                val observation = awaitAuthorizedActivity(target, uri)
                activityObservation = observation
                val activity = observation.activity
                if (activity == null || !observation.readGranted) {
                    status = if (activity == null) "activity_missing" else "permission_missing"
                    assumeTrue("No authorized QA Activity in the probe process; open QA and reselect the fixture in the picker", false)
                    return
                }
                readContext = activity
            }
            stage = "bytes"
            val bytes = readContext.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                ?: throw FileNotFoundException()
            byteCount = bytes.size
            matchesA = bytes.contentEquals(fixtureA)
            matchesB = bytes.contentEquals(fixtureB)
            check(bytes.size <= ByteLimit) { "Picker content exceeds the album decoder limit" }
            runBlocking {
                stage = "first_decode"
                first = decodedFixture(readContext, uri)
                stage = "second_decode"
                second = decodedFixture(readContext, uri)
            }
            status = "observed"
        } catch (_: SecurityException) {
            status = "permission_missing"
            assumeTrue("Selected QA context cannot read the URI; inspect sanitized context facts and reselect the fixture", false)
        } catch (_: FileNotFoundException) {
            status = "unavailable"
            assumeTrue("Picker content is unavailable; select the QA fixture again before rerunning", false)
        } catch (skipped: AssumptionViolatedException) {
            throw skipped
        } catch (_: Exception) {
            status = "read_or_decode_failed"
            throw AssertionError("Picker probe failed; inspect the sanitized pairingPickerReport")
        } finally {
            report(buildJsonObject {
                put("status", status)
                put("stage", stage)
                put("contextMode", if (useGrantedActivity) "authorized_activity" else "instrumentation_target")
                put("uriSource", if (useDocumentUri) "document" else "photo_picker")
                put("processUid", Process.myUid())
                put("processPid", Process.myPid())
                put("targetContext", contextFacts(target, uri))
                put("readContext", contextFacts(readContext, uri))
                activityObservation?.let { observation ->
                    put("resumedActivityFound", observation.activity != null)
                    put("activityReadGrantedAtSelection", observation.readGranted)
                    put("waitElapsedMillis", observation.elapsedMillis)
                    observation.identity?.let { put("activityIdentity", it) }
                    observation.activity?.let { put("activityContext", contextFacts(it, uri)) }
                }
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
            })
        }
        assertTrue("Picker bytes do not match the expected fixture; inspect pairingPickerReport",
            if (expected == "A") matchesA == true else matchesB == true)
        assertTrue("First album decode has an unexpected computer name; inspect pairingPickerReport", first == expected)
        assertTrue("Second album decode has an unexpected computer name; inspect pairingPickerReport", second == expected)
    }

    private data class ActivityObservation(
        val activity: MainActivity?, val readGranted: Boolean, val elapsedMillis: Long, val identity: JsonObject?,
    )

    private fun awaitAuthorizedActivity(target: Context, uri: Uri): ActivityObservation {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = SystemClock.elapsedRealtime()
        var observed = ActivityObservation(null, false, 0, null)
        while (SystemClock.elapsedRealtime() - start < ActivityWaitMillis) {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .singleOrNull { !it.isFinishing && !it.isDestroyed && it.packageName == target.packageName &&
                        it.application === target.applicationContext }
                if (activity != null) {
                    observed = ActivityObservation(activity, readGranted(activity, uri), SystemClock.elapsedRealtime() - start,
                        buildJsonObject {
                            put("taskId", activity.taskId)
                            put("displayId", activity.window.decorView.display?.displayId ?: -1)
                            put("sameApplication", activity.application === target.applicationContext)
                            put("resumed", true)
                        })
                } else {
                    observed = ActivityObservation(null, false, SystemClock.elapsedRealtime() - start, null)
                }
            }
            if (observed.readGranted) return observed
            Thread.sleep(200)
        }
        return observed
    }

    private fun readGranted(context: Context, uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun contextFacts(context: Context, uri: Uri): JsonObject = buildJsonObject {
        put("packageName", context.packageName)
        put("opPackageName", context.opPackageName)
        put("applicationUid", context.applicationInfo.uid)
        put("readGranted", readGranted(context, uri))
        if (Build.VERSION.SDK_INT >= 31) {
            val attribution = context.attributionSource
            put("attributionUid", attribution.uid)
            put("attributionPackage", attribution.packageName)
            put("attributionTagPresent", attribution.attributionTag != null)
        }
        if (Build.VERSION.SDK_INT >= 34) put("deviceId", context.deviceId)
    }

    private fun report(value: JsonObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("pairingPickerReport", value.toString()) })
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

    private companion object {
        const val ByteLimit = 12 * 1024 * 1024
        const val ActivityWaitMillis = 180_000L
    }
}
