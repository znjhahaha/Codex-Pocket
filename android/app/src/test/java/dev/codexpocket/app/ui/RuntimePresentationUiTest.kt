package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.wire.ExecutionTarget
import dev.codexpocket.wire.ExecutionTargetKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Wire roles, not the CLI transport or a missing version, identify a Pocket assistant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimePresentationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun runtime(role: String?, version: String = "unknown", source: String = "cli", id: String = "native") =
        RuntimeInfo.from(buildJsonObject {
            put("id", id); put("source", source); put("version", version)
            put("configStoreId", "store"); put("online", true)
            put("helperVersion", "0.7.1-rc.20261121.47")
            if (role != null) put("role", buildJsonObject { put("kind", role) })
        })

    private fun show(kind: ExecutionTargetKind, runtimes: List<RuntimeInfo>) {
        compose.mainClock.autoAdvance = false
        val target = ExecutionTarget("location", kind, when (kind) {
            ExecutionTargetKind.Computer -> "工作电脑"
            ExecutionTargetKind.LinuxSsh -> "开发服务器"
            ExecutionTargetKind.ThisPhone -> "此手机"
        })
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalExecutionLocation provides target,
                    LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                    PocketBackdrop {
                        DeviceOverview(PocketState(computerName = target.label, online = true,
                            connection = "Connected", connectionPhase = "ready", runtimes = runtimes), {}, {}, {})
                    }
                }
            }
        } }
        compose.waitForIdle()
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        captureFixture("overview-${kind.wire}-${runtimes.joinToString("-") { it.role }}")
        compose.onNodeWithText(target.label).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(android.graphics.Canvas(it))
            }
        }
        val file = File("build/reports/runtime-presentation/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun captureFixture(name: String) {
        screenshot("fixture-$name")
        File("build/reports/runtime-presentation/fixture-$name.txt")
            .writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }

    private fun repositoryScreen(kind: ExecutionTargetKind, runtime: RuntimeInfo, chat: Boolean = false, verify: () -> Unit) {
        compose.mainClock.autoAdvance = false
        val target = ExecutionTarget("location", kind, when (kind) {
            ExecutionTargetKind.Computer -> "工作电脑"
            ExecutionTargetKind.LinuxSsh -> "开发服务器"
            ExecutionTargetKind.ThisPhone -> "此手机"
        })
        val db = Room.inMemoryDatabaseBuilder(compose.activity.application, PocketDatabase::class.java).build()
        val storage = object : PairingStorage {
            override fun load(): PairedComputer? = null
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        val repo = PocketRepository(compose.activity.application, { error("Display fixture cannot connect") }, storage, db, emptyFlow())
        try {
            compose.waitUntil(5_000) { repo.state.value.initialized }
            compose.activity.runOnUiThread { compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalExecutionLocation provides target,
                        LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                        val state = PocketState(initialized = true, paired = true, computerName = target.label,
                            computerId = "endpoint", runtimes = listOf(runtime), glassEffects = false)
                        if (chat) PocketBackdrop {
                            ChatScreen(state.copy(selected = ChatEntity(ThreadKey("endpoint", "store", "chat"),
                                runtime.id, runtime.source, "会话", "", "/workspace", 0, "idle", false),
                                conversationLoadState = ConversationLoadState.Ready), repo, {}, {}, {})
                        } else PocketRoot(state, repo)
                    }
                }
            } }
            compose.waitForIdle()
            // setContent replaces MainActivity's root. Commit its state/effects before querying
            // the nested stage and home chrome while the Compose clock is frozen.
            repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            if (!chat) compose.onNodeWithTag("home-host")
                .assertExists("Drawer fixture did not reach Home:\n" + compose.onRoot(useUnmergedTree = true).printToString())
                .performClick()
            repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            captureFixture("${if (chat) "chat" else "drawer"}-${kind.wire}")
            verify()
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            val scope = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as CoroutineScope
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    @Test fun computerManagedHostIsNamedAssistantAndDoesNotBorrowTheHelperVersion() {
        show(ExecutionTargetKind.Computer, listOf(runtime("managedHost")))
        compose.onNodeWithText("电脑助理").assertIsDisplayed()
        compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
        compose.onNodeWithText("CLI").assertDoesNotExist()
        compose.onNodeWithText("unknown").assertDoesNotExist()
        compose.onNodeWithText("0.7.1-rc.20261121.47").assertDoesNotExist()
        screenshot("computer-assistant")
    }

    @Test fun sshManagedHostIsNamedForItsLocation() {
        show(ExecutionTargetKind.LinuxSsh, listOf(runtime("managedHost")))
        compose.onNodeWithText("SSH 助理").assertIsDisplayed()
        compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
        compose.onNodeWithText("电脑助理").assertDoesNotExist()
        screenshot("ssh-assistant")
    }

    @Test fun phonePackagedRuntimeIsLocalCodexNotAnAssistantOrExternalCli() {
        // NativeRole.shown_when(true) deliberately exposes the phone host as cliDaemon:
        // it serves ordinary sessions, unlike the separate computer/SSH assistant.
        show(ExecutionTargetKind.ThisPhone, listOf(runtime("cliDaemon")))
        compose.onNodeWithText("此手机 Codex").assertIsDisplayed()
        compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
        compose.onNodeWithText("CLI").assertDoesNotExist()
        compose.onNodeWithText("此手机助理").assertDoesNotExist()
        screenshot("phone-codex")
    }

    @Test fun explicitManagedRoleStillWinsOverPhoneContext() {
        show(ExecutionTargetKind.ThisPhone, listOf(runtime("managedHost")))
        compose.onNodeWithText("此手机助理").assertIsDisplayed()
        compose.onNodeWithText("此手机 Codex").assertDoesNotExist()
    }

    @Test fun computerKeepsTheRealCliAndAssistantAsSeparateVisibleInstances() {
        show(ExecutionTargetKind.Computer, listOf(runtime("managedHost", id = "assistant"),
            runtime("cliDaemon", "codex/0.171.0", id = "cli")))
        compose.onNodeWithText("电脑助理").assertIsDisplayed()
        compose.onNodeWithText("CLI").assertIsDisplayed()
        compose.onNodeWithText("0.171.0").assertIsDisplayed()
        screenshot("computer-assistant-and-cli")
    }

    @Test fun sshKeepsARealCliWithMissingVersionAsCli() {
        show(ExecutionTargetKind.LinuxSsh, listOf(runtime("cliDaemon", "")))
        compose.onNodeWithText("CLI").assertIsDisplayed()
        compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
        compose.onNodeWithText("SSH 助理").assertDoesNotExist()
    }

    @Test fun legacyCliWithNoRoleIsNotGuessedToBeAnAssistant() {
        show(ExecutionTargetKind.Computer, listOf(runtime(null)))
        compose.onNodeWithText("CLI").assertIsDisplayed()
        compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
        compose.onNodeWithText("电脑助理").assertDoesNotExist()
    }

    @Test fun phoneDrawerDescribesThisPhoneAndItsOrdinaryRuntime() =
        repositoryScreen(ExecutionTargetKind.ThisPhone, runtime("cliDaemon")) {
            compose.onNodeWithText("此手机上的 Codex").assertIsDisplayed()
            compose.onNodeWithText("此手机 Codex").assertIsDisplayed()
            compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
            compose.onNodeWithText("此电脑上的 Codex").assertDoesNotExist()
            screenshot("phone-drawer")
        }

    @Test fun sshDrawerDescribesTheSshHostAndItsAssistant() =
        repositoryScreen(ExecutionTargetKind.LinuxSsh, runtime("managedHost")) {
            compose.onNodeWithText("此 SSH 主机上的 Codex").assertIsDisplayed()
            compose.onNodeWithText("SSH 助理").assertIsDisplayed()
            compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
            compose.onNodeWithText("此电脑上的 Codex").assertDoesNotExist()
            screenshot("ssh-drawer")
        }

    @Test fun computerDrawerRetainsItsComputerContextAndIdentifiesAssistant() =
        repositoryScreen(ExecutionTargetKind.Computer, runtime("managedHost")) {
            compose.onNodeWithText("此电脑上的 Codex").assertIsDisplayed()
            compose.onNodeWithText("电脑助理").assertIsDisplayed()
            compose.onNodeWithText("原生版本未报告").assertIsDisplayed()
            screenshot("computer-drawer")
        }

    private fun modelStatus(kind: ExecutionTargetKind, status: ModelCatalogState) {
        compose.mainClock.autoAdvance = false
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalExecutionLocation provides ExecutionTarget("location", kind, "测试位置"),
                    LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                    PocketBackdrop {
                        ModelSheet(PocketState(online = status != ModelCatalogState.Offline, modelCatalogState = status), { _, _ -> }, {})
                    }
                }
            }
        } }
        compose.waitForIdle()
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        captureFixture("models-${kind.wire}-${status.name}")
        compose.onNodeWithText("选择模型").assertIsDisplayed()
    }

    @Test fun phoneOfflineModelStatusDoesNotAskForAComputer() {
        modelStatus(ExecutionTargetKind.ThisPhone, ModelCatalogState.Offline)
        compose.onNodeWithText("此手机的 Codex 未连接，连接后可重新读取模型").assertIsDisplayed()
        compose.onNodeWithText("电脑离线，连接后可重新读取模型").assertDoesNotExist()
        compose.onNodeWithText("重新读取").assertIsNotEnabled()
        screenshot("phone-model-offline")
    }

    @Test fun phoneUnavailableModelStatusNamesItsLocalConnection() {
        modelStatus(ExecutionTargetKind.ThisPhone, ModelCatalogState.RuntimeUnavailable)
        compose.onNodeWithText("已连接此手机的 Codex，此会话暂无实时运行时").assertIsDisplayed()
        compose.onNodeWithText("电脑已连接，此会话暂无实时运行时").assertDoesNotExist()
        compose.onNodeWithText("重新读取").assertIsEnabled()
        screenshot("phone-model-runtime-unavailable")
    }

    @Test fun sshOfflineModelStatusNamesTheSshHost() {
        modelStatus(ExecutionTargetKind.LinuxSsh, ModelCatalogState.Offline)
        compose.onNodeWithText("SSH 主机离线，连接后可重新读取模型").assertIsDisplayed()
        compose.onNodeWithText("电脑离线，连接后可重新读取模型").assertDoesNotExist()
        compose.onNodeWithText("重新读取").assertIsNotEnabled()
    }

    @Test fun sshUnavailableModelStatusNamesTheSshConnection() {
        modelStatus(ExecutionTargetKind.LinuxSsh, ModelCatalogState.RuntimeUnavailable)
        compose.onNodeWithText("SSH 主机已连接，此会话暂无实时运行时").assertIsDisplayed()
        compose.onNodeWithText("电脑已连接，此会话暂无实时运行时").assertDoesNotExist()
        compose.onNodeWithText("重新读取").assertIsEnabled()
    }

    @Test fun phoneEmptyChatDescribesLocalWork() =
        repositoryScreen(ExecutionTargetKind.ThisPhone, runtime("cliDaemon"), chat = true) {
            compose.onNodeWithText("在此手机上开始或继续对话。").assertIsDisplayed()
            compose.onNodeWithText("在手机上继续电脑里的对话。").assertDoesNotExist()
            screenshot("phone-empty-chat")
        }

    @Test fun sshEmptyChatDescribesWorkOnTheSshHost() =
        repositoryScreen(ExecutionTargetKind.LinuxSsh, runtime("cliDaemon"), chat = true) {
            compose.onNodeWithText("在手机上继续 SSH 主机里的对话。").assertIsDisplayed()
            compose.onNodeWithText("在手机上继续电脑里的对话。").assertDoesNotExist()
        }

    @Test fun cachedHomeNoticeNamesTheCurrentLocationAndKeepsTheConversationVisible() {
        compose.mainClock.autoAdvance = false
        var kind by mutableStateOf(ExecutionTargetKind.ThisPhone)
        val cached = ChatEntity(ThreadKey("endpoint", "store", "cached"), "native", "cli",
            "缓存会话", "已保存的内容", "/workspace", 1, "completed", false)
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") {
                CompositionLocalProvider(LocalExecutionLocation provides ExecutionTarget("location", kind, "测试位置"),
                    LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                    PocketBackdrop {
                        ChatsHome(PocketState(initialized = true, paired = true, online = false,
                            connection = "Offline", computerId = "endpoint", chats = listOf(cached)),
                            {}, {}, { _, _, _ -> }, {}, {})
                    }
                }
            }
        } }
        for ((location, message) in listOf(
            ExecutionTargetKind.ThisPhone to "此手机的 Codex 未连接，显示已缓存的会话",
            ExecutionTargetKind.LinuxSsh to "SSH 主机离线，显示已缓存的会话",
            ExecutionTargetKind.Computer to "电脑离线，显示已缓存的会话",
        )) {
            compose.runOnIdle { kind = location }
            repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            captureFixture("home-cached-${location.wire}")
            compose.onNodeWithTag("home-sync-state").assertIsDisplayed()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("缓存会话").assertIsDisplayed()
        }
    }
}
