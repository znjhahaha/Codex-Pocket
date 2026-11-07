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

/** Existing history only. Permission mutation additionally requires an explicit idle fixture ID. */
@RunWith(AndroidJUnit4::class)
class LiveV051ControlsTest {
    @Test fun contextAndOptionalIdleFixturePermissions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("v051Controls") == "true")
        val id = requireNotNull(args.getString("contextThreadId"))
        val repo = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketApplication).repository
        var stage = "connect"
        var original: String? = null
        var permissionThread: ThreadKey? = null
        var changed = false
        var restored = false
        var tested = false
        var permissionStatus = "not_requested"
        var contextReady = false
        var failureType: String? = null
        try { runBlocking { withTimeout(120_000) {
            repo.state.first { it.initialized }
            withContext(Dispatchers.Main) { repo.setForeground(true) }
            repo.state.first { it.online }
            repo.refresh().join()
            val chat = repo.state.value.chats.first { it.key.nativeId == id }
            repo.select(chat).join()
            stage = "context"
            repo.refreshContext().join()
            val snapshot = repo.state.value.contextSnapshot
            contextReady = ContextUsage.from(snapshot.usage).used != null
            check(contextReady) { "Numeric context unavailable" }
            val fixtureId = args.getString("permissionFixtureThreadId")
            if (fixtureId != null) {
                val fixture = repo.state.value.chats.single { it.key.nativeId == fixtureId }
                check(fixture.cwd.replace('/', '\\').endsWith("\\.local\\probe-workspace"))
                check(fixture.title.startsWith("Codex Pocket") && !fixture.isSubagent && !fixture.conflict)
                repo.select(fixture).join()
                check(repo.state.value.timeline.activeTurn == null)
                stage = "permissions_read"
                repo.refreshPermissions().join()
                var permissions = repo.state.value.permissions
                if (permissions.status == "unknown" && args.getString("resumePermissionFixture") == "true") {
                    repo.resume().join() // Explicit idle fixture activation, never turn/start.
                    repo.refreshPermissions().join()
                    permissions = repo.state.value.permissions
                }
                permissionStatus = permissions.status
                original = permissions.currentPresetId
                permissionThread = fixture.key
                val target = permissions.presets.firstOrNull { it.enabled && it.id != original && it.id in setOf("request", "full") }
                if (permissions.status == "ready" && original != null && target != null) {
                    stage = "permissions_update"
                    changed = true
                    repo.choosePermission(target.id).join()
                    repo.refreshPermissions().join()
                    check(repo.state.value.permissions.currentPresetId == target.id)
                    check(repo.state.value.timeline.activeTurn == null)
                    tested = true
                }
            }
        } } } catch (failure: Throwable) { failureType = failure.javaClass.simpleName; throw failure } finally { runBlocking {
            if (changed && original != null && permissionThread == repo.state.value.selected?.key) {
                // Read authoritative result first. Never repeat an unknown update blindly.
                withTimeoutOrNull(30_000) {
                    repo.refreshPermissions().join()
                    if (!repo.state.value.uncertainSend && repo.state.value.permissions.status == "ready") {
                        if (repo.state.value.permissions.currentPresetId != original) repo.choosePermission(original!!).join()
                        repo.refreshPermissions().join()
                        restored = repo.state.value.permissions.currentPresetId == original
                    }
                }
            }
            writeLiveReport("controls-report.json", buildJsonObject {
                put("passed", failureType == null && contextReady && (!changed || (tested && restored)))
                put("contextAvailable", contextReady); put("permissionStatus", permissionStatus)
                put("permissionUpdateTested", tested); put("permissionRestored", restored)
                put("stage", stage); put("online", repo.state.value.online); put("modelRequests", 0)
                put("failureType", failureType)
            })
            withContext(Dispatchers.Main) { repo.setForeground(false) }
        } }
        check(!changed || restored) { "Fixture permission restoration requires attention" }
    }
}
