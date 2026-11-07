package dev.codexpocket.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly enabled read-only quota probe. Never starts/resumes a turn or prints balances. */
@RunWith(AndroidJUnit4::class)
class LiveQuotaTest {
    @Test fun pairedHelperQuotaRead() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("quotaSmoke") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication
        val repo = app.repository
        var stage = "connect"
        try {
            runBlocking { withTimeout(120_000) {
                repo.state.first { it.initialized }
                withContext(Dispatchers.Main) { repo.setForeground(true) }
                repo.state.first { it.online }
                repo.refresh().join()
                val s = repo.state.value
                val chat = s.selected?.takeIf { selected -> s.runtimes.any { it.id == selected.runtimeId && it.online } }
                    ?: s.chats.first { chat -> !chat.archived && !chat.conflict && s.runtimes.any { it.id == chat.runtimeId && it.online } }
                if (s.selected?.key != chat.key) repo.select(chat).join()
                stage = "sources"
                repo.refreshQuotaSources().join()
                check(repo.state.value.online)
                val q = repo.state.value.quota
                check(q.sources.isNotEmpty()) { "Quota source catalog unavailable" }
                val label = args.getString("quotaSource")
                val source = if (label != null) q.sources.single { it.string("label") == label }
                    else q.sources.firstOrNull { it.string("id") == q.selectedSourceId }
                if (source != null) {
                    stage = "read"
                    repo.refreshQuota(source.string("id"), refresh = true).join()
                    check(repo.state.value.online)
                    check(repo.state.value.quota.result != null) { "Quota read did not return a bounded result" }
                }
                val final = repo.state.value.quota
                writeLiveReport("quota-report.json", buildJsonObject {
                    put("passed", true); put("online", repo.state.value.online); put("modelRequests", 0)
                    put("status", final.status); put("sourceCount", q.sources.size)
                    put("sourceLabel", source?.string("label")); put("items", final.result?.array("items")?.size ?: 0)
                    put("stale", final.result?.flag("stale") ?: false)
                })
            } }
        } catch (failure: Throwable) {
            writeLiveReport("quota-report.json", buildJsonObject {
                put("passed", false); put("stage", stage); put("exception", failure.javaClass.simpleName)
                put("online", repo.state.value.online); put("status", repo.state.value.quota.status); put("modelRequests", 0)
            })
            throw AssertionError("Read-only quota check failed at $stage (${failure.javaClass.simpleName})")
        } finally { runBlocking { withContext(Dispatchers.Main) { repo.setForeground(false) } } }
    }
}
