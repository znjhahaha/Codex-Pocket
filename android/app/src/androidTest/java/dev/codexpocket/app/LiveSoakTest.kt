package dev.codexpocket.app

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only encrypted connectivity/history soak. No model turns or automatic mutations. */
@RunWith(AndroidJUnit4::class)
class LiveSoakTest {
    @Test fun encryptedHistorySoak() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveSoak") == "true")
        val duration = args.getString("durationSeconds")?.toLong() ?: 60L
        require(duration in 30L..86_400L)
        val threadId = args.getString("threadId") ?: error("Dedicated integration thread required")
        val app = instrumentation.targetContext.applicationContext as PocketApplication
        val repo = app.repository
        val start = SystemClock.elapsedRealtime()
        var samples = 0
        var reconnects = 0
        var peakPssKb = 0L
        var largestReconnectMs = 0L
        var itemKeys: Set<String>? = null
        fun report(status: String) {
            val json = buildJsonObject {
                put("status", status); put("elapsedSeconds", (SystemClock.elapsedRealtime() - start) / 1000)
                put("targetSeconds", duration); put("samples", samples); put("reconnects", reconnects)
                put("peakPssKb", peakPssKb); put("largestReconnectMs", largestReconnectMs)
                put("online", repo.state.value.online); put("historyItems", itemKeys?.size ?: 0)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("pocketSoak", json.toString()) })
        }
        try {
            runBlocking {
                withTimeout((duration + 120L) * 1000L) {
                    repo.state.first { it.initialized }
                    check(repo.state.value.paired)
                    withContext(Dispatchers.Main) { repo.setForeground(true) }
                    withTimeout(30_000) { repo.state.first { it.online } }
                    repo.refresh("Codex Pocket MuMu integration").join()
                    val chat = repo.state.first { s -> s.chats.any { it.key.nativeId == threadId } }.chats.first { it.key.nativeId == threadId }
                    check(chat.title == "Codex Pocket MuMu integration")
                    do {
                        withTimeout(30_000) {
                            repo.state.first { it.online }
                            repo.select(chat).join()
                        }
                        val state = repo.state.value
                        check(state.error == null && state.selected?.key?.nativeId == threadId)
                        check(state.timeline.activeTurn == null && !state.uncertainSend)
                        val currentKeys = state.timeline.items.keys
                        check(itemKeys == null || itemKeys == currentKeys) { "Dedicated history changed during read-only soak" }
                        itemKeys = currentKeys
                        peakPssKb = maxOf(peakPssKb, Debug.getPss())
                        samples++
                        report("running")
                        if (samples % 15 == 0 || duration <= 120L && samples == 1) {
                            withContext(Dispatchers.Main) { repo.setForeground(false) }
                            repo.state.first { !it.online }
                            val reconnectStart = SystemClock.elapsedRealtime()
                            withContext(Dispatchers.Main) { repo.setForeground(true) }
                            withTimeout(30_000) { repo.state.first { it.online } }
                            largestReconnectMs = maxOf(largestReconnectMs, SystemClock.elapsedRealtime() - reconnectStart)
                            reconnects++
                        }
                        val remaining = duration * 1000L - (SystemClock.elapsedRealtime() - start)
                        if (remaining > 0) delay(minOf(60_000L, remaining))
                    } while (SystemClock.elapsedRealtime() - start < duration * 1000L)
                }
            }
            report("completed")
        } catch (failure: Throwable) {
            report("failed")
            throw AssertionError("Read-only soak failed (${failure.javaClass.simpleName}); native diagnostics omitted")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } }
        }
    }
}
