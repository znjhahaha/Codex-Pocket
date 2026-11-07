package dev.codexpocket.app

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in, debug-target-only integration. Never pass pairing data as instrumentation extras. */
@RunWith(AndroidJUnit4::class)
class LivePairingTest {
    @Test fun pairFromPrivateFile() {
        assumeTrue("Live pairing requires explicit opt-in", InstrumentationRegistry.getArguments().getString("livePairing") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Live pairing requires a debug target" }
        val app = target.applicationContext as PocketApplication
        val input = File(target.filesDir, "integration/pairing.json")
        val bytes = ByteArray(65_537)
        var stage = "private input"
        var pairing: Job? = null
        try {
            require(input.isFile && input.length() in 1..65_536)
            val count = input.inputStream().use { stream ->
                var used = 0
                while (used < bytes.size) {
                    val read = stream.read(bytes, used, bytes.size - used)
                    if (read < 0) break
                    used += read
                }
                used
            }
            require(input.delete())
            require(count in 1..65_536)
            runBlocking {
                withTimeout(120_000) {
                    stage = "repository initialization"
                    app.repository.state.first { it.initialized }
                    require(!app.repository.state.value.paired && app.repository.state.value.error == null)
                    stage = "pair and encrypted storage"
                    withContext(Dispatchers.Main) { app.repository.setForeground(true) }
                    pairing = app.repository.pair(String(bytes, 0, count, Charsets.UTF_8), "MuMu integration")
                    bytes.fill(0)
                    pairing!!.join()
                    require(app.repository.state.value.paired)
                    stage = "hello and initial sync"
                    app.repository.state.first { it.online || it.error != null }.also { require(it.online) }
                }
            }
        } catch (_: Throwable) {
            // Do not attach the original exception: parser/transport diagnostics can contain secrets.
            throw AssertionError("Live pairing failed at $stage. Sensitive diagnostics omitted.")
        } finally {
            bytes.fill(0)
            pairing?.cancel()
            input.delete()
            runBlocking { withContext(Dispatchers.Main) { app.repository.setForeground(false) } }
        }
    }
}
