package dev.codexpocket.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.MonitoringInstrumentation
import androidx.test.runner.intercepting.InterceptingActivityFactory
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.AssistantScreen
import dev.codexpocket.app.ui.LocalPocketGlass
import dev.codexpocket.app.ui.PocketOverlayHost
import dev.codexpocket.app.ui.PocketTheme
import dev.codexpocket.app.ui.UsageScreen
import dev.codexpocket.wire.ConnectionConfig
import dev.codexpocket.wire.PocketClient
import dev.codexpocket.wire.PocketConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parent owns build/install/adb/backend. Run alone in a fresh instrumentation process.
 * This is a real repository + real pinned/encrypted PocketClient test, with a transparent
 * observing/scope-guarding connection (no canned replies, notification injection or fake state).
 * All inputs/proofs are private target-app files. Never initialize the app's repository.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class V070LiveAcceptanceDeviceTest {
    private val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var input: JsonObject
    private lateinit var runId: String
    private lateinit var root: File
    private lateinit var proof: File
    private lateinit var db: PocketDatabase
    private lateinit var repository: PocketRepository
    private lateinit var activity: ComponentActivity
    private lateinit var nativeChat: ChatEntity
    private val originalRepositoryAccesses = AtomicInteger()
    private val scopeViolations = AtomicInteger()
    private val usageRpcSequence = AtomicInteger()
    private val calls = CopyOnWriteArrayList<RpcEvidence>()
    private val connections = CopyOnWriteArrayList<ObservedConnection>()
    private val checks = CopyOnWriteArrayList<String>()
    private val images = CopyOnWriteArrayList<JsonObject>()
    private val metrics = CopyOnWriteArrayList<JsonObject>()
    private val ownedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var nativeRuntime: String? = null
    private var assistantRuntime: String? = null
    private var assistantBinding: AssistantComposerBinding? = null
    private var stage = "isolation"
    private var page by mutableStateOf("usage")
    private var theme by mutableStateOf("light")
    private var fontScale by mutableFloatStateOf(1f)
    private val nativeCwd get() = input.obj("native").string("cwd")
    private val nativeMarker get() = "V070_NATIVE_${runId.replace('-', '_')}"
    private val assistantMarker get() = "V070_ASSISTANT_${runId.replace('-', '_')}"

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                // Opt-in precedes Compose, Activity, private input, database and network access.
                assumeTrue("Opt in with v070LiveAcceptance=true",
                    InstrumentationRegistry.getArguments().getString("v070LiveAcceptance") == "true")
                val app = instrumentation.targetContext.applicationContext as PocketApplication
                val field = PocketApplication::class.java.getDeclaredField("repository\$delegate")
                    .apply { isAccessible = true }
                val original = field.get(app) as Lazy<*>
                check(!original.isInitialized()) { "V070 requires a fresh isolated instrumentation process" }
                val runner = instrumentation as MonitoringInstrumentation
                val forbidden = lazy {
                    originalRepositoryAccesses.incrementAndGet()
                    error("V070 forbids access to PocketApplication.repository")
                }
                instrumentation.runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(app)
                    field.set(app, forbidden)
                }
                var passed = false
                var failureType: String? = null
                try {
                    readInput(app)
                    writeResult("running")
                    val isolated = IsolatedContext(app, root, runId)
                    db = Room.databaseBuilder(isolated, PocketDatabase::class.java,
                        File(root, "repository/live.db").absolutePath)
                        .addMigrations(PocketDatabase.MIGRATION_1_2, PocketDatabase.MIGRATION_2_3).build()
                    repository = PocketRepository(isolated,
                        connectionFactory = {
                            val routing = dev.codexpocket.wire.RoutingPocketConnection(dev.codexpocket.wire.RouteManager())
                                .apply { p2pEnabled = false; usePublicStun = false }
                            ObservedConnection(routing).also { connections += it }
                        },
                        pairStore = PrivatePairingStorage(), db = db, networkAvailable = emptyFlow())
                    runner.interceptActivityUsing(object : InterceptingActivityFactory {
                        override fun shouldIntercept(loader: ClassLoader, name: String, intent: Intent) =
                            name == MainActivity::class.java.name
                        override fun create(loader: ClassLoader, name: String, intent: Intent): Activity =
                            IsolatedActivity { LiveContent() }
                    })
                    base.evaluate()
                    assertEquals("Original repository access attempts", 0, originalRepositoryAccesses.get())
                    assertEquals("Scope violations", 0, scopeViolations.get())
                    assertFalse("Original repository remained uninitialized", original.isInitialized())
                    passed = true
                } catch (failure: Throwable) {
                    // Do not attach a cause: peer exceptions/Compose trees can contain private text.
                    failureType = failure.javaClass.simpleName
                    throw AssertionError("V070 acceptance failed at $stage ($failureType); inspect private proof/result.json")
                } finally {
                    runner.useDefaultInterceptingActivityFactory()
                    if (::repository.isInitialized) {
                        repository.setForeground(false)
                        // Repository has no close API. Cancel only this explicitly created instance.
                        val scopeField = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
                        val ownScope = scopeField.get(repository) as CoroutineScope
                        runBlocking { ownScope.coroutineContext[Job]?.cancelAndJoin() }
                    }
                    connections.forEach { it.close() }
                    if (::db.isInitialized) db.close()
                    if (::proof.isInitialized) writeResult(if (passed) "passed" else "failed", failureType)
                    instrumentation.runOnMainSync { field.set(app, original) }
                    // Observer stays detached until this standalone instrumentation process ends.
                    // No forget(), cache clearing, production preference reads, or formal DB opens.
                }
            }
        }
    }).around(compose)

    private fun readInput(context: Context) {
        // Release targets cannot use run-as. The opted-in operator supplies a transient
        // input in this app's scoped external directory; it is never rendered or logged.
        val inbox = File(checkNotNull(context.getExternalFilesDir(null)), "v070-live-acceptance").canonicalFile
        val file = File(inbox, "input.json")
        require(file.canonicalFile.parentFile == inbox && file.isFile && file.length() in 2..131072)
        val bytes = file.readBytes()
        input = try { pocketJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            catch (_: Exception) { error("Invalid private V070 input") }
            finally { bytes.fill(0); check(file.delete()) { "Transient V070 input could not be removed" } }
        require(input.number("schemaVersion") == 1L)
        runId = input.string("runId").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,63}"))) }
        require(input["pairing"] is JsonObject)
        val cwd = nativeCwd
        require(cwd.matches(Regex("^[A-Za-z]:[\\\\/].+")) && '\u0000' !in cwd)
        require(normalize(input.obj("assistant").string("cwd")) == normalize(cwd))
        root = File(context.filesDir, "v070-live-acceptance/runs/$runId")
        // A run ID is one-shot; never resume/replay mutations by rerunning the same input.
        require(!root.exists()) { "Use a new V070 runId" }
        check(File(root, "repository").mkdirs())
        // Only sanitized evidence and Activity screenshots leave private repository storage.
        proof = File(inbox, "runs/$runId/proof").apply { check(mkdirs()) }
        File(root, "claimed").writeText("one-shot; inspect result before a new run")
    }

    private class IsolatedContext(base: Context, private val root: File, private val run: String) : ContextWrapper(base) {
        private fun directory(name: String) = File(root, name).apply { check(isDirectory || mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name.matches(Regex("[A-Za-z0-9_.-]+")))
            return baseContext.getSharedPreferences("v070_live_${run}_$name", Context.MODE_PRIVATE)
        }
        override fun getFilesDir(): File = directory("repository/files")
        override fun getCacheDir(): File = directory("repository/cache")
        override fun getNoBackupFilesDir(): File = directory("repository/no-backup")
        override fun getExternalFilesDir(type: String?): File = directory("repository/external")
        override fun getDatabasePath(name: String): File {
            val path = File(name)
            val target = if (path.isAbsolute) path.canonicalFile else File(directory("repository/db"), name).canonicalFile
            require(target.toPath().startsWith(root.canonicalFile.toPath()))
            target.parentFile?.mkdirs()
            return target
        }
    }

    private class PrivatePairingStorage : PairingStorage {
        @Volatile private var pair: PairedComputer? = null
        override fun load(): PairedComputer? = pair
        override fun save(pair: PairedComputer) { check(this.pair == null); this.pair = pair }
        override fun forget(): Unit = error("V070 never forgets a pairing")
    }

    private class IsolatedActivity(private val render: @Composable () -> Unit) : ComponentActivity() {
        private var contentMounted by mutableStateOf(false)
        fun mountContent() { contentMounted = true }
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setContent { if (contentMounted) render() }
        }
    }

    @Composable private fun LiveContent() {
        val state by repository.state.collectAsState()
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val context = LocalContext.current
        val scaled = remember(configuration, fontScale) { Configuration(configuration).apply { this.fontScale = this@V070LiveAcceptanceDeviceTest.fontScale } }
        val scaledContext = remember(context, scaled) { context.createConfigurationContext(scaled) }
        CompositionLocalProvider(LocalContext provides scaledContext, LocalConfiguration provides scaled,
            LocalDensity provides Density(density.density, fontScale), LocalPocketGlass provides false) {
            key(page, theme, fontScale) {
                PocketTheme(theme) {
                    PocketOverlayHost {
                        if (page == "assistant") AssistantScreen(state.assistant, state.assistantModels,
                            state.runtimes.map { AssistantRuntimeChoice(it.id, "Acceptance runtime", it.online) },
                            if (::nativeChat.isInitialized) listOf(AssistantWorkspaceChoice(nativeChat.runtimeId,
                                nativeChat.key.nativeId, "Acceptance workspace", true, configStoreId = nativeChat.key.configStoreId)) else emptyList(),
                            onAction = { action ->
                                if (action.method == "assistant/send") {
                                    check(::nativeChat.isInitialized)
                                    // The UI must explicitly select the one newly created project.
                                    check(action.params.string("threadId") == nativeChat.key.nativeId)
                                    check(action.params.string("sourceRuntimeId") == nativeChat.runtimeId)
                                }
                                repository.assistantAction(action)
                            }, onOpenThread = { _, _ -> error("No external thread navigation in V070") }, onBack = {}, connected = state.online)
                        else UsageScreen(state.usage, { repository.loadUsage() }, { repository.loadUsage(it) },
                            contentPadding = PaddingValues(top = 36.dp, bottom = 32.dp),
                            onRequests = { repository.loadUsageRequests(it) })
                    }
                }
            }
        }
    }

    @Test(timeout = 720_000) fun liveAcceptance() {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<ComponentActivity>(Intent(instrumentation.targetContext, MainActivity::class.java), options).use { activityScenario ->
            activityScenario.onActivity { activity = it }
            // Move only the blank test task: MuMu otherwise mounts it on a secondary
            // display while Compose injection and the system IME target display zero.
            if (activity.window.decorView.display?.displayId != android.view.Display.DEFAULT_DISPLAY) {
                val command = "am display move-stack ${activity.taskId} ${android.view.Display.DEFAULT_DISPLAY}"
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                    .bufferedReader().use { it.readText() }
                compose.waitUntil(5_000) {
                    var ready = false
                    activityScenario.onActivity { activity = it; ready = it.window.decorView.display?.displayId == android.view.Display.DEFAULT_DISPLAY }
                    ready
                }
            }
            activityScenario.onActivity {
                activity = it
                assertEquals(android.view.Display.DEFAULT_DISPLAY, it.window.decorView.display?.displayId)
                (it as IsolatedActivity).mountContent()
            }
            scenario("private_pair_and_runtime_readiness") { pairAndReady() }
            scenario("native_send_ack_reply_draft_reconnect") { nativeAcceptance() }
            scenario("assistant_prepare_project_marker_offline_recovery") { assistantAcceptance() }
            scenario("usage_comparison_rpc_and_current_repository") { usageRpcAcceptance() }
            scenario("usage_activity_pixels_kpis_touch_filters_paging_appearance") { usageUiAcceptance() }
        }
    }

    private fun scenario(name: String, body: () -> Unit) {
        stage = name
        writeResult("running")
        body()
        checks += name
        writeResult("running")
    }

    private fun pairAndReady() {
        await("repository initialization", 15_000) { repository.state.value.initialized }
        join(repository.pair(input.obj("pairing").toString(), "V070 isolated acceptance"), 40_000)
        assertTrue("Private backend pairing accepted", repository.state.value.paired)
        repository.setForeground(true)
        await("authenticated repository sync", 60_000) { repository.state.value.online }
        val requested = input.obj("native").string("runtimeId").takeIf(String::isNotBlank)
        val store = input.obj("native").string("configStoreId").takeIf(String::isNotBlank)
        repository.refreshNativeSnapshot()
        await("CLI runtime readiness", 60_000) {
            repository.state.value.runtimes.any { it.source == "cli" && it.online &&
                (requested == null || it.id == requested) && (store == null || it.configStoreId == store) &&
                !it.readinessTimedOut && it.readinessStage in setOf(null, "ready") && repository.state.value.supports("thread/start", it) }
        }
        val choices = repository.state.value.runtimes.filter { it.source == "cli" && it.online &&
            (requested == null || it.id == requested) && (store == null || it.configStoreId == store) && repository.state.value.supports("thread/start", it) }
        assertEquals("Pin runtimeId when multiple CLI runtimes are advertised", 1, choices.size)
        nativeRuntime = choices.single().id
        metrics += buildJsonObject { put("runtimeOnline", true); put("source", "cli"); put("readinessTimedOut", false) }
    }

    private fun nativeAcceptance() {
        val runtime = repository.state.value.runtimes.single { it.id == nativeRuntime }
        join(repository.create(SessionMode.Cli, nativeCwd, runtime.id), 60_000)
        nativeChat = checkNotNull(repository.state.value.selected) { "Native create did not select a new conversation" }
        assertEquals(runtime.id, nativeChat.runtimeId)
        assertEquals(normalize(nativeCwd), normalize(nativeChat.cwd))
        assertTrue("Thread was observed in the real thread/start response", ownedThreads.contains(nativeChat.key.nativeId))
        val prompt = "Do not run tools, access files, create tasks, or modify anything. Reply with only this exact marker, without punctuation:\n$nativeMarker"
        repository.editDraft(prompt)
        await("private draft persisted", 5_000) { runBlocking { db.dao().draft(nativeChat.key)?.text == prompt } }
        join(repository.send(), 100_000)
        await("native ACK and draft cleanup", 10_000) {
            calls.count { it.nativeMethod == "turn/start" && it.accepted } == 1 &&
                repository.state.value.draft?.text == "" && runBlocking { db.dao().draft(nativeChat.key)?.text == "" }
        }
        await("real native model reply", 120_000) {
            repository.state.value.timeline.items.values.any { it.type == "agentMessage" && it.complete && it.text.trim() == nativeMarker } &&
                repository.state.value.timeline.activeTurn == null
        }
        assertEquals(1, calls.count { it.nativeMethod == "turn/start" })
        val before = calls.count { it.nativeMethod == "turn/start" }
        offlineAndReconnect()
        join(repository.reloadConversation(), 40_000)
        assertTrue(repository.state.value.timeline.items.values.any { it.type == "agentMessage" && it.text.trim() == nativeMarker })
        observeNoReplay(2_000)
        assertEquals("Reconnect must not resubmit native send", before, calls.count { it.nativeMethod == "turn/start" })
        assertEquals("One durable native command and accepted ACK", 1L, scalar("SELECT count(*) FROM commands WHERE method='turn/start' AND status='accepted'"))
        metrics += buildJsonObject { put("nativeSendCount", before); put("nativeAck", true); put("nativeReply", true); put("draftCleared", true); put("reconnectReplayCount", 0) }
    }

    private fun assistantAcceptance() {
        // No enable(runtime) or desktop runtime ensure: only the backend prepares its assistant.
        join(repository.assistantAction(AssistantActions.prepare()), 60_000)
        await("prepared assistant", 90_000, poll = { join(repository.loadAssistant(automaticPrepare = false), 20_000) }) {
            repository.state.value.assistant.snapshot?.prepared == true
        }
        var snapshot = checkNotNull(repository.state.value.assistant.snapshot)
        assistantRuntime = checkNotNull(snapshot.runtimeId)
        assertEquals("One explicit prepare", 1, calls.count { it.method == "assistant/prepare" })
        assertTrue("Fresh dedicated backend assistant has no prior user conversation", repository.state.value.assistant.messages.none { it.role == "User" })
        assistantBinding = snapshot.composerBinding(repository.state.value.computerId)
        compose.runOnIdle { page = "assistant" }
        compose.waitForIdle()
        node("assistant-tools").performClick()
        node("assistant-workspace-project:${nativeChat.key.nativeId}").assertIsDisplayed().performClick()
        val prompt = "This is an acceptance conversation in the selected dedicated workspace. Do not run tools, read files, create tasks, or modify anything. Reply with only this exact marker, without punctuation:\n$assistantMarker"
        node("assistant-workspace-draft").performTextReplacement(prompt)
        hideKeyboard()
        node("assistant-workspace-send").assertIsEnabled().performClick()
        await("assistant accepted receipt", 60_000) {
            repository.state.value.assistant.sendReceipts.any { it.status == "accepted" } && calls.any { it.method == "assistant/send" && it.accepted }
        }
        compose.waitUntil(10_000) { node("assistant-workspace-draft").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty() }
        await("real assistant marker reply", 120_000, poll = { join(repository.loadAssistant(automaticPrepare = false), 20_000) }) {
            assistantMessages().any { it.role == "Assistant" && it.text.trim() == assistantMarker }
        }
        snapshot = checkNotNull(repository.state.value.assistant.snapshot)
        assertNotNull("Backend created coordinator conversation", snapshot.coordinatorThreadId)
        val coordinator = snapshot.coordinatorThreadId
        assertEquals(1, calls.count { it.method == "assistant/send" })
        capture("01-assistant-real-reply")
        repository.setForeground(false)
        await("assistant offline", 10_000) { !repository.state.value.online }
        join(repository.loadAssistant(automaticPrepare = false), 15_000)
        assertTrue("Offline keeps actual assistant reply", assistantMessages().any { it.role == "Assistant" && it.text.trim() == assistantMarker })
        node("assistant-workspace-draft").performTextReplacement("Unsent offline draft")
        hideKeyboard()
        node("assistant-workspace-send").assertIsNotEnabled()
        capture("02-assistant-offline")
        repository.setForeground(true)
        await("assistant reconnect", 60_000) { repository.state.value.online }
        join(repository.loadAssistant(automaticPrepare = false), 20_000)
        assertEquals(coordinator, repository.state.value.assistant.snapshot?.coordinatorThreadId)
        assertEquals(assistantBinding, repository.state.value.assistant.snapshot?.composerBinding(repository.state.value.computerId))
        assertTrue(assistantMessages().any { it.role == "Assistant" && it.text.trim() == assistantMarker })
        node("assistant-workspace-draft").assertTextEquals("Unsent offline draft")
        node("assistant-workspace-draft").performTextClearance()
        hideKeyboard()
        observeNoReplay(2_000)
        assertEquals("Assistant reconnect must not replay", 1, calls.count { it.method == "assistant/send" })
        assertEquals(1L, scalar("SELECT count(*) FROM commands WHERE method='assistant/send' AND status='accepted'"))
        metrics += buildJsonObject { put("assistantPrepared", true); put("assistantSendCount", 1); put("assistantAck", true); put("assistantReply", true); put("assistantOfflineRecovered", true) }
    }

    private fun usageRpcAcceptance() {
        val comparison = input.obj("usage")["comparisonParams"] as? JsonObject
        if (comparison != null) {
            require(comparison.string("source_id") == "cc_switch" && comparison["query"] is JsonObject)
            runBlocking { withTimeout(15_000) { currentConnection().request("usage/dashboard/read", comparison, timeoutMillis = 12_000) } }
            checks += "usage_fixed_cutoff_real_rpc_saved"
        }
        val u = input.obj("usage")
        val zone = u.string("zoneId", "Asia/Shanghai")
        val current = UsageFilter.lastDays(7, zone)
        // Always exercise actual recent seven days first; supplied dates serve a separate live query.
        join(repository.loadUsage(current), 20_000)
        assertUsageReady(current)
        checks += "usage_actual_current_seven_days"
        if (u.string("startDate").isNotBlank() || u.string("endDate").isNotBlank()) {
            val specified = current.withDates(LocalDate.parse(u.string("startDate")), LocalDate.parse(u.string("endDate")))
            join(repository.loadUsage(specified), 20_000)
            assertUsageReady(specified)
            join(repository.loadUsage(current), 20_000)
            assertUsageReady(current)
        }
        assertTrue("Real dashboard RPC was observed", calls.any { it.method == "usage/dashboard/read" && it.accepted })
        metrics += buildJsonObject { put("usageRpcCount", usageRpcSequence.get()); put("currentSevenDays", true); put("fixedSnapshotEqualityAsserted", false) }
    }

    private fun usageUiAcceptance() {
        compose.runOnIdle { page = "usage" }
        compose.waitForIdle()
        node("usage-screen").assertIsDisplayed()
        val originalFilter = repository.state.value.usage.filter
        verifyKpis("03-usage-light")
        scroll("usage-trend")
        node("usage-trend-chart").assertIsDisplayed().performTouchInput { click(center) }
        node("usage-insight-detail").assertExists()
        capture("04-usage-trend-touch")
        dismissSheet()
        node("usage-trend:Tokens").performScrollTo().performClick()
        assertTrue(node("usage-trend-chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().contains("令牌"))
        scroll("usage-filter-open")
        node("usage-filter-open").performClick()
        node("usage-filter-sheet").performScrollToNode(hasTestTag("usage-filter-status:success"))
        node("usage-filter-status:success").performClick()
        node("usage-filter-apply").performClick()
        await("real filter RPC", 20_000) { !repository.state.value.usage.refreshing && repository.state.value.usage.filter.status == UsageStatusFilter.Success }
        assertUsageReady(originalFilter.withStatus(UsageStatusFilter.Success))
        capture("05-usage-real-filter")
        join(repository.loadUsage(originalFilter), 20_000)
        assertUsageReady(originalFilter)
        // One-row group pages force real pagination whenever two real groups exist.
        join(repository.loadUsage(originalFilter.copy(providerPage = UsagePage(limit = 1), modelPage = UsagePage(limit = 1), sessionPage = UsagePage(limit = 1))), 20_000)
        assertUsageReady(repository.state.value.usage.filter)
        var groupPages = 0
        for (kind in listOf("provider", "model", "session")) {
            val data = checkNotNull(repository.state.value.usage.visibleSnapshot?.response?.data)
            val hasMore = when (kind) { "provider" -> data.providerGroups.hasMore; "model" -> data.modelGroups.hasMore; else -> data.sessionGroups?.hasMore == true }
            if (hasMore) {
                val before = repository.state.value.usage.filter
                scroll("usage-group:$kind")
                node("usage-group:$kind").performClick()
                scroll("usage-$kind-next")
                node("usage-$kind-next").assertIsEnabled().performClick()
                await("group page RPC", 20_000) { repository.state.value.usage.filter != before && !repository.state.value.usage.refreshing }
                assertUsageReady(repository.state.value.usage.filter)
                groupPages++
                capture("06-usage-$kind-page")
            }
        }
        val requests = repository.state.value.usage.visibleRequests
        if (requests?.hasMore == true) {
            val before = requests.items.size
            val count = calls.count { it.method == "usage/requests/read" }
            scroll("usage-requests-next")
            node("usage-requests-next").assertIsEnabled().performClick()
            await("request cursor RPC", 20_000) { calls.count { it.method == "usage/requests/read" } > count && !repository.state.value.usage.requestLoading }
            assertNull("Request page must succeed", repository.state.value.usage.requestError)
            assertTrue(repository.state.value.usage.visibleRequests!!.items.size > before)
            checks += "usage_real_request_cursor_page"
            capture("07-usage-request-page")
        } else checks += "usage_request_paging_not_exercised_no_real_next_cursor"
        assertTrue("Need real multi-group data to accept pagination", groupPages > 0)
        metrics += buildJsonObject { put("usageGroupPages", groupPages); put("usageRequestCursorExercised", requests?.hasMore == true) }
        join(repository.loadUsage(originalFilter), 20_000)
        compose.runOnIdle { theme = "dark" }
        verifyKpis("08-usage-dark")
        compose.runOnIdle { fontScale = 1.8f }
        verifyKpis("09-usage-dark-font180")
        scroll("usage-filter-open")
        node("usage-filter-open").assertIsDisplayed().performClick()
        node("usage-filter-apply").assertIsDisplayed().assertIsEnabled()
        capture("10-usage-font180-filters")
        dismissSheet()
    }

    private fun verifyKpis(image: String) {
        for (id in listOf("tokens", "cost", "requests", "cache", "ttft", "speed", "errors")) {
            scroll("usage-metric:$id")
            node("usage-metric:$id").assertIsDisplayed()
            val spark = compose.onNodeWithTag("usage-spark:$id", useUnmergedTree = true)
            assertTrue(spark.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().startsWith("真实每日趋势："))
            val value = compose.onNodeWithTag("usage-value:$id", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            value.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("KPI $id has visual overflow", layout.hasVisualOverflow)
            assertEquals("KPI $id is one line", 1, layout.lineCount)
            assertFalse("KPI $id ellipsized", layout.isLineEllipsized(0))
            val b = node("usage-metric:$id").fetchSemanticsNode().boundsInRoot
            val screen = node("usage-screen").fetchSemanticsNode().boundsInRoot
            assertTrue("KPI $id fits Activity width", b.left >= screen.left - 1 && b.right <= screen.right + 1)
            if (id in setOf("tokens", "ttft", "errors")) capture("$image-$id")
        }
    }

    private fun assistantMessages(): List<AssistantChatMessage> = repository.state.value.assistant.let {
        (it.messages + it.snapshot?.coordinatorReplies.orEmpty()).distinctBy { message -> message.id }
    }

    private fun assertUsageReady(filter: UsageFilter) {
        val state = repository.state.value.usage
        assertEquals(filter, state.filter)
        assertEquals("Real usage response is ready", UsagePhase.Ready, state.phase)
        assertNotNull("No fixture fallback", state.visibleSnapshot?.response?.data)
        assertFalse(state.refreshing)
    }

    private fun offlineAndReconnect() {
        repository.setForeground(false)
        await("offline", 10_000) { !repository.state.value.online }
        repository.setForeground(true)
        await("reconnected and synced", 60_000) { repository.state.value.online }
    }

    private fun observeNoReplay(milliseconds: Long) {
        // Bounded observation, never a retry of a mutation.
        runBlocking { delay(milliseconds) }
    }

    private fun scalar(sql: String): Long = db.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst()); it.getLong(0)
    }

    private fun join(job: Job, timeout: Long) = runBlocking { withTimeout(timeout) { job.join() } }
    private fun await(label: String, timeout: Long, poll: (() -> Unit)? = null, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < end) { "Timed out: $label" }
            poll?.invoke()
            runBlocking { delay(350) }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun scroll(tag: String) { node("usage-list").performScrollToNode(hasTestTag(tag)) }
    private fun dismissSheet() {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun hideKeyboard() {
        compose.runOnIdle {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private data class RpcEvidence(val method: String, val nativeMethod: String?, val commandHash: String?,
        val accepted: Boolean, val elapsedMs: Long)

    private inner class ObservedConnection(private val real: PocketConnection) : PocketConnection by real {
        override suspend fun connect(config: ConnectionConfig) {
            try { real.connect(config) }
            catch (failure: Exception) {
                metrics += buildJsonObject {
                    put("connectionFailureType", failure.javaClass.simpleName)
                    if (failure is dev.codexpocket.wire.WireException && failure.code.matches(Regex("[a-z_]{1,64}")))
                        put("connectionFailureCode", failure.code)
                }
                throw failure
            }
        }
        override suspend fun request(method: String, params: JsonObject, commandId: String?, timeoutMillis: Long): JsonElement {
            val native = if (method == "codex/request") params.string("method") else null
            guard(method, native, params, commandId)
            val start = android.os.SystemClock.elapsedRealtime()
            var accepted = false
            try {
                val response = real.request(method, params, commandId, timeoutMillis.coerceAtMost(30_000))
                accepted = if (method == "assistant/send") commandId != null && assistantSendAckStatus(response, commandId) == "accepted"
                    else (response as? JsonObject)?.string("status") !in setOf("unknown", "submitted", "rejected")
                if (native == "thread/start") {
                    val thread = response.jsonObject.obj("thread")
                    check(normalize(thread.string("cwd")) == normalize(nativeCwd)) { "Native returned unexpected cwd" }
                    check(thread.string("id").isNotBlank())
                    ownedThreads += thread.string("id")
                }
                if (method in setOf("usage/read", "usage/dashboard/read", "usage/requests/read")) {
                    val n = usageRpcSequence.incrementAndGet()
                    check(n <= 64) { "Usage RPC proof bound exceeded" }
                    File(proof, "usage-rpc-${n.toString().padStart(3, '0')}.json").writeText(buildJsonObject {
                        put("method", method); put("params", redactedMetrics(params)); put("response", redactedMetrics(response))
                        put("realEncryptedPocketClient", true); put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                    }.toString())
                }
                return response
            } finally {
                calls += RpcEvidence(method, native, commandId?.let(::hash), accepted, android.os.SystemClock.elapsedRealtime() - start)
            }
        }
        override fun close() { real.close() }
    }

    private fun guard(method: String, native: String?, params: JsonObject, commandId: String?) {
        fun deny(): Nothing { scopeViolations.incrementAndGet(); error("V070 outbound scope rejected") }
        if (method in setOf("runtime/ensure", "assistant/enable", "devices/revoke")) deny()
        if (native != null) {
            val runtime = params.string("runtimeId")
            // All mutation traffic targets the selected dedicated CLI runtime and new threads.
            if (commandId != null) {
                if (runtime != nativeRuntime) deny()
                val body = params.obj("params")
                if (native == "thread/start") {
                    if (normalize(body.string("cwd")) != normalize(nativeCwd) || calls.any { it.nativeMethod == "thread/start" }) deny()
                } else if (body.string("threadId") !in ownedThreads) deny()
                if (native !in setOf("thread/start", "thread/resume", "turn/start")) deny()
                if (native == "turn/start" && calls.any { it.nativeMethod == "turn/start" }) deny()
            }
        }
        if (method.startsWith("assistant/") && commandId != null) {
            if (method !in setOf("assistant/prepare", "assistant/send")) deny()
            if (method == "assistant/prepare" && calls.any { it.method == method }) deny()
            if (method == "assistant/send") {
                if (params.string("runtimeId") != assistantRuntime || params.string("sourceRuntimeId") != nativeRuntime ||
                    params.string("threadId") !in ownedThreads || calls.any { it.method == method }) deny()
            }
        }
    }

    private fun currentConnection(): ObservedConnection = connections.last { it.state.value == dev.codexpocket.wire.LinkState.ONLINE }

    /** Preserve numeric measurements/decimal strings; never persist identifying or arbitrary peer text. */
    private fun redactedMetrics(value: JsonElement, key: String = ""): JsonElement = when (value) {
        JsonNull -> JsonNull
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> redactedMetrics(v, k) })
        is JsonArray -> JsonArray(value.map { redactedMetrics(it, key) })
        is JsonPrimitive -> if (!value.isString) value else {
            val text = value.content
            val identifying = key.endsWith("_id") || key in setOf("id", "name", "title", "label", "model", "provider_name", "session_title")
            when {
                identifying -> JsonPrimitive("[redacted]")
                text.matches(Regex("-?[0-9]+(\\.[0-9]+)?")) -> value
                text in setOf("ready", "fresh", "stale", "missing", "all", "success", "error", "proxy", "codex_session", "codex", "cc_switch", "USD", "successful_proxy_observations", "retained_detail_only") -> value
                else -> JsonPrimitive("[redacted]")
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { frame.countDown() }
        } }
        check(frame.await(5, TimeUnit.SECONDS)) { "No Activity frame" }
        val copied = CountDownLatch(1)
        var result = -1
        var display = -1
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            display = decor.display?.displayId ?: -1
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        try {
            check(copied.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Activity PixelCopy failed" }
            val output = File(proof, "$name.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            images += buildJsonObject { put("file", output.name); put("displayId", display); put("widthPx", bitmap.width)
                put("heightPx", bitmap.height); put("source", "Activity.window PixelCopy"); put("theme", theme); put("fontScale", fontScale) }
        } finally { bitmap.recycle() }
    }

    private fun writeResult(status: String, failureType: String? = null) {
        File(proof, "result.json").writeText(buildJsonObject {
            put("schemaVersion", 1); put("status", status); put("stage", stage); put("runId", runId)
            put("realRepository", true); put("realEncryptedPocketClient", true); put("productionRepositoryAccessAttempts", originalRepositoryAccesses.get())
            put("scopeViolations", scopeViolations.get()); put("independentPairingStorage", true); put("privateRoomDatabase", true)
            put("connectionInstances", connections.size); put("usageRpcProofFiles", usageRpcSequence.get())
            put("checks", JsonArray(checks.map(::JsonPrimitive))); put("metrics", JsonArray(metrics)); put("screenshots", JsonArray(images))
            put("rpc", buildJsonArray { calls.forEach { call -> add(buildJsonObject {
                put("method", call.method); call.nativeMethod?.let { put("nativeMethod", it) }; call.commandHash?.let { put("commandHash", it) }
                put("accepted", call.accepted); put("elapsedMs", call.elapsedMs)
            }) } })
            failureType?.let { put("failureType", it) }
            put("evidenceBoundary", "Device/backend run only; fixed-cutoff comparison is parent-owned; no process-death, real network outage, physical phone, or visual human review claim")
        }.toString())
    }

    private fun normalize(path: String) = path.replace('/', '\\').trimEnd('\\').lowercase(java.util.Locale.ROOT)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
