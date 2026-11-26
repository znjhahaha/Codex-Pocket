package dev.codexpocket.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.*
import dev.codexpocket.app.ui.kit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap

/** Real application repository/agent/runtime host, real model, real approval UI. */
@RunWith(AndroidJUnit4::class)
class PhoneApplicationToolsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun applicationSendsToolsAndNativeApprovalDecisions() = runBlocking {
        compose.mainClock.autoAdvance = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.contains(".qa"))
        if (compose.activity.display?.displayId != android.view.Display.DEFAULT_DISPLAY) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "am display move-stack ${compose.activity.taskId} ${android.view.Display.DEFAULT_DISPLAY}"
                )
            ).use { it.readBytes() }
        }
        val model = requireNotNull(InstrumentationRegistry.getArguments().getString("ccsModel"))
        require(model.startsWith("ccs-"))
        val app = context.applicationContext as PocketApplication
        val locations = app.locations
        fun progress(stage: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("phoneProjectStage", stage) })
        progress("connecting application runtime")
        val provider = locations.thisPhone.access.value.active
        val enabled = listOf(LocalProviderModel(model, listOf("low", "high"), "high"))
        if (provider?.name != "CCS QA" || provider.model != model || provider.baseUrl != "http://127.0.0.1:15721/v1" || provider.enabledModels != enabled)
            assertTrue(locations.thisPhone.saveProvider(LocalProviderDraft(provider?.id?.takeIf { provider.name == "CCS QA" }, "CCS QA", "http://127.0.0.1:15721/v1", "cc-switch-local", model, enabled)))
        withTimeout(180_000) {
            while (locations.thisPhone.applying.value) delay(100)
            val saved = locations.directory.value.targets.firstOrNull { it.kind == dev.codexpocket.wire.ExecutionTargetKind.ThisPhone }
            if (saved == null) locations.connectThisPhone().join() else locations.select(saved.id).join()
        }
        assertEquals("Phone activation failed: ${locations.error.value}; environment=${locations.thisPhone.preparation.value}",
            dev.codexpocket.wire.ExecutionTargetKind.ThisPhone, locations.activeLocation.value?.target?.kind)
        val repo = requireNotNull(locations.active.value)
        locations.setForeground(true)
        suspend fun awaitState(timeout: Long = 45_000, condition: (PocketState) -> Boolean) {
            try { withTimeout(timeout) { while (!condition(repo.state.value)) delay(100) } }
            catch (error: TimeoutCancellationException) {
                val state = repo.state.value
                throw AssertionError("State timed out: online=${state.online}, connection=${state.connection}, cwd=${state.selected?.cwd}, error=${state.error}, create=${state.actionStatus["create"]}, permissions=${state.permissions.status}/${state.permissions.error}, model=${state.model}, effort=${state.effort}; access=${state.accessFor()}, recovery=${state.conversationRecovery}, accessFresh=${state.accessFresh}; runtimes=${state.runtimes.map { "${it.id}:${it.role}:${it.online}:${it.readinessStage}:start=${it.capabilities?.contains("thread/start")}" }}; notice=${state.notice}", error)
            }
        }
        awaitState { it.online }
        val previousCreate = InstrumentationRegistry.getArguments().getString("inspectPreviousTestCreate")
        if (previousCreate != null) {
            // Explicitly inspect only our earlier failed test intent through the app's
            // existing recovery action. Never wipe journals or replay an uncertain write.
            val unresolved = PocketDatabase.create(context).let { db ->
                try { db.dao().uncertain(repo.state.value.computerId).filter { it.chatKey == null } } finally { db.close() }
            }
            assertEquals(listOf(previousCreate), unresolved.map { it.id })
            assertTrue(unresolved.single().let { it.chatKey == null && it.method == "thread/start" })
            assertNull(repo.state.value.selected)
            repo.inspectUnknownHistory().join()
            assertTrue(repo.state.value.unknownHistoryInspected)
            repo.resolveUnknownAfterInspection().join()
            assertNull(repo.state.value.error)
            progress("previous test creation inspected without replay")
        }
        val retainedProject = InstrumentationRegistry.getArguments().getString("retainedProject")
        val retainedThread = InstrumentationRegistry.getArguments().getString("retainedThread")
        val name = retainedProject ?: "app-tools-${UUID.randomUUID().toString().take(8)}"
        // Prepared from the real Pocket repository by prepare-real-project-rc53.py.
        val archive = File(context.getExternalFilesDir(null), "rc53-real-project.zip")
        assertTrue("Push the real project archive before this test", archive.isFile)
        if (retainedProject == null) {
            val zip = File(context.cacheDir, "capture/$name.zip").apply { parentFile!!.mkdirs(); archive.copyTo(this) }
            locations.thisPhone.importProject(FileProvider.getUriForFile(context, "${context.packageName}.files", zip))
            withTimeout(15_000) { while (locations.thisPhone.projects.value.busy) delay(50) }
        }
        val project = File(locations.thisPhone.layout.projects, name)
        val source = File(project, "linux_environment.py")
        assertTrue("Real project import did not finish", source.isFile)
        progress("real project imported")
        val originalSource = java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("linux_environment.py")).bufferedReader().use { it.readText() }
        }
        // Match the creation page: the first use prepares CLI on demand, without
        // waiting for a runtime the app intentionally has not started yet.
        if (retainedThread == null) withTimeout(60_000) { repo.create(SessionMode.Cli, project.path).join() }
        else {
            repo.refresh().join()
            awaitState { it.chats.any { chat -> chat.key.nativeId == retainedThread } }
            repo.select(repo.state.value.chats.first { it.key.nativeId == retainedThread }, forceRefresh = true).join()
            repo.observeConversation(requireNotNull(repo.state.value.selected).key)
        }
        assertNull("Native project conversation creation failed", repo.state.value.error)
        assertNull("Native project conversation creation not confirmed: notice=${repo.state.value.notice}; diagnostics=${repo.connectionDiagnostics().takeLast(5)}", repo.state.value.actionStatus["create"]?.error)
        awaitState { it.selected?.cwd == project.path && it.permissions.status == "ready" && it.models.isNotEmpty() }
        val chat = repo.state.value.selected!!
        progress("conversation created")
        assertEquals(listOf(model), repo.state.value.models.map { it.model })
        compose.activity.runOnUiThread { compose.activity.setContent {
            val state by repo.state.collectAsState()
            val activeLocation by locations.activeLocation.collectAsState()
            PocketTheme("dark") { CompositionLocalProvider(LocalExecutionLocation provides activeLocation?.target) {
                PocketRoot(state, repo)
            } }
        } }
        fun settle() { repeat(65) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
        settle()
        compose.onNodeWithTag("home-chat-${chat.key.encoded}").performScrollTo().performClick()
        settle()
        compose.onNodeWithContentDescription("会话权限").performClick(); settle()
        compose.onNodeWithTag("permission-preset:app-request").performScrollTo().performClick(); settle()
        awaitState { it.permissions.currentPresetId == "app-request" && !it.permissions.updating }
        progress("native approval mode confirmed")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; settle()
        compose.onNodeWithTag("composer-model").performClick(); settle()
        if (compose.onAllNodesWithTag("model-picker-open").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("model-picker-open").performClick(); settle()
        }
        compose.onNodeWithTag("model-row-${repo.state.value.models.single().id}").performClick(); settle()
        compose.onNodeWithTag("composer-model").performClick(); settle()
        compose.onNodeWithTag("effort-track").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        settle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; settle()
        val prompt = if (retainedProject != null) "The project implementation and tests already passed. Do not redo them. Only request this exact separate command now: printf forbidden > rejected.txt . I will deny or cancel it in the native approval UI. Do not retry or write that file another way." else """
            Work on this real copy of Codex Pocket's Linux environment packaging project. Use search and file-reading tools to locate Image.write and its existing tests. Add a fileBytes object to the generated manifest: canonical regular file paths mapped to byte lengths, including materialized hardlinks and zero-byte files, excluding symbolic links. Preserve all existing manifest fields. Add regression tests in test_linux_environment.py and run BOTH test_linux_environment and test_linux_sources with python3 -m unittest -v; fix any failures. Do not download packages. Write ACCEPTANCE.md describing the actual change, test command and result. Also run node -e 'console.log(process.version)' to verify the project runtime. Finally, as a separate command request, run printf forbidden > rejected.txt . I will reject that last operation; do not retry it or write rejected.txt another way. You must implement and test, not just reply with text.
        """.trimIndent()
        val leakedContext = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val contextWatch = launch(Dispatchers.Default) {
            repo.state.collect { state -> state.timeline.items.values.filter {
                it.type == "userMessage" && it.text.trim().startsWith("<environment_context>") && ConversationPresentation.visible(it)
            }.forEach { leakedContext.add(it.key) } }
        }
        compose.onNodeWithTag("composer-input").performTextInput(prompt)
        settle(); compose.onNodeWithTag("composer-send").performClick(); settle()
        progress("real project task sent")
        var approved = InstrumentationRegistry.getArguments().getString("retainedApproved")?.toInt() ?: 0
        var declined = 0; var observed = false
        var generatedContextItems = 0
        fun assertNoEnvironmentBubble() {
            val items = repo.state.value.timeline.items.values
            generatedContextItems = maxOf(generatedContextItems, items.count { it.raw.string("pocketContextSource") == "runtime-environment" })
            assertTrue("Injected environment leaked into a message", items.none {
                it.type == "userMessage" && it.text.trim().startsWith("<environment_context>") && ConversationPresentation.visible(it)
            })
        }
        val handled = mutableSetOf<String>()
        withTimeout(600_000) {
            while (true) {
                val state = repo.state.value
                assertNoEnvironmentBubble()
                observed = observed || state.timeline.activeTurn != null
                val approval = state.approvals.firstOrNull { it.threadId == chat.key.nativeId && !it.submitted && it.key !in handled }
                if (approval != null) {
                    settle()
                    val reject = approval.params.toString().contains("rejected.txt")
                    val decisions = (approval.params["availableDecisions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val rejectLabel = if (decisions == null || "decline" in decisions) "拒绝" else "取消任务"
                    compose.onNodeWithText(if (reject) rejectLabel else "允许一次", useUnmergedTree = true).performClick()
                    handled += approval.key
                    awaitState(20_000) { current ->
                        approval.key !in current.submittingApprovalKeys && current.approvals.none { it.key == approval.key }
                    }
                    if (reject) declined++ else approved++
                    progress("native approval round trip: allow=$approved deny=$declined")
                    settle()
                }
                if (observed && state.timeline.activeTurn == null && !state.sendBusy && state.approvals.none { it.threadId == chat.key.nativeId }) break
                delay(150)
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
            }
        }
        assertNotEquals("Model did not modify real project source", originalSource, source.readText())
        progress("project implementation returned")
        assertTrue("No implementation report", File(project, "ACCEPTANCE.md").isFile)
        assertFalse("Rejected operation happened", File(project, "rejected.txt").exists())
        assertTrue("No allow/reject round trip", approved > 0 && declined > 0)
        assertEquals(model, repo.state.value.model)
        assertEquals("high", repo.state.value.effort)
        val toolItems = repo.state.value.timeline.items.values.filter { it.type in setOf("commandExecution", "dynamicToolCall", "fileChange") }
        assertTrue("No application tool timeline", toolItems.isNotEmpty())
        // Run the project suite independently; model prose is not acceptance evidence.
        val verification = File(project, "verify-acceptance.py")
        verification.writeText("""
            import io, json, tempfile, unittest
            from pathlib import Path
            from linux_environment import Image
            suite=unittest.defaultTestLoader.loadTestsFromNames(['test_linux_environment','test_linux_sources'])
            result=unittest.TextTestRunner(verbosity=2).run(suite)
            assert result.wasSuccessful() and result.testsRun >= 7
            image=Image(); image.files['usr/bin/empty']=(b'',0o755); image.files['usr/bin/data']=(b'abc',0o644)
            image.hardlinks['usr/bin/copy']='usr/bin/data'; image.links['bin']='usr/bin'
            with tempfile.TemporaryDirectory() as tmp:
                manifest=image.write(Path(tmp),'x86_64','acceptance',{})
                assert manifest['fileBytes']=={'usr/bin/empty':0,'usr/bin/data':3,'usr/bin/copy':3}
            Path('independent-tests.json').write_text(json.dumps({'passed':True,'tests':result.testsRun}))
        """.trimIndent())
        val checkLog = File(project, "independent-tests.log")
        val layout = locations.thisPhone.layout
        val command = phoneLinuxCommand(layout, File("/opt/python/bin/python3"), listOf(verification.path)).toMutableList()
        command[command.indexOf("-w") + 1] = project.path
        val check = ProcessBuilder(command).directory(project).apply { environment().putAll(layout.environment("http://127.0.0.1:1")) }
            .redirectErrorStream(true).redirectOutput(checkLog).start()
        assertTrue("Project tests timed out", check.waitFor(60, TimeUnit.SECONDS))
        assertEquals(checkLog.readText(), 0, check.exitValue())
        progress("independent project tests passed")
        val rollout = layout.codexHome.walkTopDown().firstOrNull { it.isFile && it.extension == "jsonl" && it.name.contains(chat.key.nativeId) }
        assertNotNull("Native turn evidence missing", rollout)
        val nativeContext = rollout!!.useLines { lines -> lines.mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        }.filter { it.string("type") == "turn_context" }.lastOrNull()?.obj("payload") }
        assertEquals("Native model differs from UI", model, nativeContext?.string("model"))
        assertEquals("Native effort differs from UI", "high", nativeContext?.string("effort"))
        repo.select(chat, forceRefresh = true).join()
        awaitState { it.model == model && it.effort == "high" && it.models.map { m -> m.model } == listOf(model) &&
            it.permissions.currentPresetId == "app-request" && it.automaticConnectionLabel() == null }
        assertNoEnvironmentBubble()
        contextWatch.cancelAndJoin()
        assertTrue("Environment context appeared transiently after send", leakedContext.isEmpty())
        settle()
        File(context.getExternalFilesDir(null), "phone-project-accepted.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(context.getExternalFilesDir(null), "phone-application-tools-acceptance.json").writeText(buildJsonObject {
            put("version", BuildConfig.VERSION_NAME); put("model", model); put("effort", repo.state.value.effort)
            put("approved", approved); put("declined", declined); put("toolItems", toolItems.size)
            put("project", project.path); put("threadId", chat.key.nativeId); put("rejectedWriteAbsent", true)
            put("projectSource", "Codex Pocket scripts/android-native"); put("independentTests", File(project, "independent-tests.json").readText())
            put("generatedContextItems", generatedContextItems); put("environmentBubbleAbsent", true)
            put("nativeTurnModel", nativeContext!!.string("model")); put("nativeTurnEffort", nativeContext.string("effort"))
        }.toString())
    }

    /** Run in a fresh instrumentation process after the real-project turn. No new model call. */
    @Test fun coldStartAndReentryRetainModelPermissionsAndHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.contains(".qa"))
        val report = File(context.getExternalFilesDir(null), "phone-application-tools-acceptance.json")
        org.junit.Assume.assumeTrue("Run the real project acceptance first", report.isFile)
        val proof = Json.parseToJsonElement(report.readText()).jsonObject
        val locations = (context.applicationContext as PocketApplication).locations
        val start = android.os.SystemClock.elapsedRealtime()
        withTimeout(120_000) {
            val saved = locations.directory.value.targets.single { it.kind == dev.codexpocket.wire.ExecutionTargetKind.ThisPhone }
            locations.select(saved.id).join()
        }
        val repo = requireNotNull(locations.active.value)
        locations.setForeground(true)
        withTimeout(45_000) { while (repo.state.value.chats.none { it.key.nativeId == proof.string("threadId") }) delay(100) }
        val chat = repo.state.value.chats.first { it.key.nativeId == proof.string("threadId") }
        val timings = mutableListOf<Long>()
        var observation: Job? = null
        repeat(3) {
            val at = android.os.SystemClock.elapsedRealtime()
            repo.select(chat, forceRefresh = true).join()
            observation?.cancel()
            observation = repo.observeConversation(chat.key)
            try { withTimeout(30_000) {
                while (repo.state.value.let { s -> s.model != proof.string("model") || s.effort != "high" ||
                    s.permissions.currentPresetId != "app-request" || s.automaticConnectionLabel() != null ||
                    s.conversationLoadState != ConversationLoadState.Ready }) delay(50)
            } } catch (error: TimeoutCancellationException) {
                val s=repo.state.value
                throw AssertionError("reentry=$it timings=$timings online=${s.online} model=${s.model}/${s.effort} permissions=${s.permissions.status}/${s.permissions.currentPresetId}/${s.permissions.error} load=${s.conversationLoadState} read=${s.conversationReadSource} label=${s.automaticConnectionLabel()} selected=${s.selected?.runtimeId} access=${s.accessFor()} recovery=${s.conversationRecovery} diagnostics=${repo.connectionDiagnostics().takeLast(4)}", error)
            }
            timings += android.os.SystemClock.elapsedRealtime() - at
            assertEquals(listOf(proof.string("model")), repo.state.value.models.map { it.model })
            assertTrue(repo.state.value.timeline.items.values.none { item -> item.type == "userMessage" &&
                item.text.trim().startsWith("<environment_context>") && ConversationPresentation.visible(item) })
            locations.setForeground(false); delay(200); locations.setForeground(true)
        }
        observation?.cancel()
        File(context.getExternalFilesDir(null), "phone-recovery-acceptance.json").writeText(buildJsonObject {
            put("coldAndReentryMs", android.os.SystemClock.elapsedRealtime() - start)
            put("reentryMs", JsonArray(timings.map(::JsonPrimitive))); put("permissions", repo.state.value.permissions.currentPresetId)
            put("model", repo.state.value.model); put("effort", repo.state.value.effort)
        }.toString())
    }
}
