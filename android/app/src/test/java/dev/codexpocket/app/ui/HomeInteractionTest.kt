package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val base = PocketPreviewStates.chats.first()
    private fun chat(id: String, title: String, pinned: Boolean = false, archived: Boolean = false) = base.copy(
        key = ThreadKey("home-test-pc", "home-test-store", id), title = title,
        pinned = pinned, archived = archived, status = "completed",
    )

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop { block() } } } }
        compose.waitForIdle()
    }

    @Test fun cachedHomeRemountDoesNotShowLoadingOrForceRefresh() {
        val row = chat("cached", "已缓存的会话")
        var mounted by mutableStateOf(true)
        var ensures = 0
        var forced = 0
        content {
            if (mounted) ChatsHome(PocketPreviewStates.home.copy(chats = listOf(row),
                computerId = row.key.computerId, online = true, directoryRefreshing = true, directoryLoading = false),
                {}, {}, { _, _, _ -> forced++ }, {}, {}, onEnsureDirectory = { _, _ -> ensures++ })
        }
        repeat(5) {
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("home-chat-${row.key.encoded}").assertIsDisplayed()
            compose.onNodeWithTag("home-sync-state").assertDoesNotExist()
            compose.runOnIdle { mounted = false }
            compose.runOnIdle { mounted = true }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(0, forced); assertTrue(ensures > 0) }
    }

    @Test fun directoryFailureKeepsCachedRowsAndRetryWhileConnectionStaysOnline() {
        val row = chat("cached", "已缓存的会话")
        var retries = 0
        content {
            ChatsHome(PocketPreviewStates.home.copy(chats = listOf(row), computerId = row.key.computerId,
                online = true, directoryError = "会话列表更新超时，已保留缓存，可重试"),
                {}, {}, { _, _, _ -> retries++ }, {}, {}, onEnsureDirectory = { _, _ -> })
        }
        compose.onNodeWithTag("home-chat-${row.key.encoded}").assertIsDisplayed()
        compose.onNodeWithText("会话列表更新超时，已保留缓存，可重试").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(dev.codexpocket.app.R.string.v03_home_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun runningWinsOverPinAndEachNativeChatOccursOnce() {
        val runningPin = chat("running", "运行置顶", pinned = true)
        val pinned = chat("pin", "普通置顶", pinned = true)
        val recent = chat("recent", "普通会话")
        val rows = listOf(runningPin, pinned, recent, runningPin)
        val statuses = mapOf(runningPin.key.encoded to "inProgress")
        val groups = homeChatGroups(rows, statuses, "", false)
        assertEquals(listOf("running", "pinned", "recent"), groups.map { it.id })
        assertEquals(listOf(runningPin), groups[0].chats)
        assertEquals(listOf(pinned), groups[1].chats)
        assertEquals(listOf(recent), groups[2].chats)
        assertEquals(3, groups.flatMap { it.chats }.map { it.key }.distinct().size)
        assertEquals(listOf("search"), homeChatGroups(rows, statuses, "会话", false).map { it.id })
        assertEquals(listOf("archive"), homeChatGroups(rows, statuses, "", true).map { it.id })
    }

    @Test fun disclosureNeverSelectsAChatAndBothMenuGesturesStaySeparate() {
        val pinned = chat("pin", "菜单测试会话", pinned = true)
        var selections = 0
        content {
            ChatsHome(PocketPreviewStates.home.copy(chats = listOf(pinned)), { selections++ }, {}, { _, _, _ -> }, {}, {})
        }
        val rowTag = "home-chat-${pinned.key.encoded}"
        compose.onNodeWithTag("home-project-${HomeProject(pinned.key.computerId, pinned.cwd).id}").performClick()
        compose.onNodeWithTag("home-group-pinned").performClick()
        compose.onNodeWithTag(rowTag).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithTag("home-group-pinned").performClick()
        compose.onNodeWithTag(rowTag).performClick()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithTag(rowTag).performTouchInput { longClick(center) }
        compose.onNodeWithText("对话操作").assertExists()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithContentDescription("关闭").performClick()
        // No visible "…" any more: the accessibility long-click action opens the same menu.
        compose.onNodeWithTag(rowTag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("对话操作").assertExists()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun pageSwitchPreservesSearchArchiveFilterAndCollapsedSection() {
        val archivedChat = chat("archived", "目标归档会话", archived = true)
        var showHome by mutableStateOf(true)
        content {
            val holder = rememberSaveableStateHolder()
            if (showHome) holder.SaveableStateProvider("home-chats") {
                ChatsHome(PocketPreviewStates.home.copy(chats = listOf(archivedChat)), {}, {}, { _, _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("home-filter-archive").performClick()
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("目标")
        compose.onNodeWithTag("home-group-archive").performClick()
        compose.runOnIdle { showHome = false }
        compose.waitForIdle()
        compose.runOnIdle { showHome = true }
        compose.onNodeWithTag("home-filter-archive").assertIsSelected()
        compose.onNodeWithContentDescription("搜索对话").assertTextContains("目标")
        compose.onNodeWithTag("home-chat-${archivedChat.key.encoded}").assertDoesNotExist()
        compose.onNodeWithTag("home-group-archive").performClick()
        compose.onNodeWithTag("home-chat-${archivedChat.key.encoded}").assertExists()
    }
    @Test fun collapsedSearchRetainsQueryAndReopensConsistently() {
        content { ChatsHome(PocketPreviewStates.home, {}, {}, { _, _, _ -> }, {}, {}) }
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("登录")
        compose.onNodeWithContentDescription("收起搜索").performClick()
        compose.onNodeWithContentDescription("搜索对话").assertDoesNotExist()
        compose.onNodeWithText("搜索：登录").assertExists().performClick()
        compose.onNodeWithContentDescription("搜索对话").assertTextContains("登录")
    }
    @Test fun runtimeSwitchDoesNotRestoreAnotherRuntimesProject() {
        val first = chat("a", "电脑 A 会话").copy(runtimeId = "runtime-a", cwd = "D:/Projects/A")
        val second = chat("b", "电脑 B 会话").copy(runtimeId = "runtime-b", cwd = "D:/Projects/B")
        val runtime = PocketPreviewStates.home.runtimes.first()
        val state = PocketPreviewStates.home.copy(chats = listOf(first, second), runtimes = listOf(runtime.copy(id = "runtime-a"), runtime.copy(id = "runtime-b")))
        var shown by mutableStateOf(true)
        var selectedRuntime by mutableStateOf("runtime-a")
        content {
            val holder = rememberSaveableStateHolder()
            if (shown) holder.SaveableStateProvider("Chats") {
                ChatsHome(state, {}, {}, { _, _, _ -> }, {}, {}, runtimeFilter = selectedRuntime)
            }
        }
        compose.onNodeWithTag("home-project-filter").performClick()
        compose.onNodeWithText("D:/Projects/A").performClick()
        compose.onNodeWithTag("home-chat-${first.key.encoded}").assertExists()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { selectedRuntime = "runtime-b"; shown = true }
        compose.onNodeWithTag("home-chat-${second.key.encoded}").assertExists()
        compose.onNodeWithTag("home-chat-${first.key.encoded}").assertDoesNotExist()
    }

    @Test fun switchingProjectsRestoresIndependentQueryArchiveAndDisclosure() {
        val first = chat("a", "目标归档会话", archived = true).copy(cwd = "D:/A")
        val second = chat("b", "其他项目会话").copy(cwd = "D:/B")
        content { ChatsHome(PocketPreviewStates.home.copy(chats = listOf(first, second)), {}, {}, { _, _, _ -> }, {}, {}) }
        compose.onNodeWithTag("home-project-${HomeProject(first.key.computerId, first.cwd).id}").performClick()
        compose.onNodeWithTag("home-filter-archive").performClick()
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("目标")
        compose.onNodeWithTag("home-group-archive").performClick()
        compose.onNodeWithTag("home-project-${HomeProject(second.key.computerId, second.cwd).id}").performClick()
        compose.onNodeWithTag("home-filter-all").assertIsSelected()
        compose.onNodeWithTag("home-chat-${second.key.encoded}").assertExists()
        compose.onNodeWithContentDescription("搜索对话").assertDoesNotExist()
        compose.onNodeWithTag("home-project-${HomeProject(first.key.computerId, first.cwd).id}").performClick()
        compose.onNodeWithTag("home-filter-archive").assertIsSelected()
        compose.onNodeWithContentDescription("搜索对话").assertTextContains("目标")
        compose.onNodeWithTag("home-chat-${first.key.encoded}").assertDoesNotExist()
        compose.onNodeWithTag("home-group-archive").performClick()
        compose.onNodeWithTag("home-chat-${first.key.encoded}").assertExists()
    }

    @Test fun switchingProjectsRestoresReadingPosition() {
        val rows = (1..30).map { chat("a-$it", "项目 A 会话 $it").copy(cwd = "D:/A", updatedAt = (31 - it).toLong()) }
        val other = chat("b", "项目 B 会话").copy(cwd = "D:/B")
        content { ChatsHome(PocketPreviewStates.home.copy(chats = rows + other), {}, {}, { _, _, _ -> }, {}, {}) }
        val projectA = "home-project-${HomeProject(rows.first().key.computerId, "D:/A").id}"
        val projectB = "home-project-${HomeProject(other.key.computerId, "D:/B").id}"
        compose.onNodeWithTag(projectA).performClick()
        val target = "home-chat-${rows[20].key.encoded}"
        compose.onNodeWithTag("chats-home").performScrollToNode(hasTestTag(target))
        val before = compose.onNodeWithTag(target).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(projectB).performClick()
        compose.onNodeWithTag("home-chat-${other.key.encoded}").assertIsDisplayed()
        compose.onNodeWithTag(projectA).performClick()
        val after = compose.onNodeWithTag(target).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
    }

    @Test fun allProjectGroupsRemainVerticalAndRowsExposeSafeSummarySourceAndStatus() {
        val row = chat("a", "原生标题", pinned = true).copy(preview = "已完成登录校验", status = "failed")
        content { ChatsHome(PocketPreviewStates.home.copy(chats = listOf(row)), {}, {}, { _, _, _ -> }, {}, {}) }
        compose.onNodeWithTag("home-group-project:${HomeProject(row.key.computerId, row.cwd).id}").assertExists()
        compose.onNodeWithTag("home-shelf-pinned").assertDoesNotExist()
        compose.onNodeWithTag("home-group-pinned").assertDoesNotExist()
        compose.onNodeWithText("原生标题").assertExists()
        // A failed chat leads its preview line with the state, so attention states read at a glance.
        compose.onNodeWithText("${compose.activity.stateLabel("failed")} · 已完成登录校验").assertExists()
        compose.onNodeWithTag("home-chat-${row.key.encoded}").assert(hasStateDescription(compose.activity.stateLabel("failed")))
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun headerSearchAndIndependentDockKeepTouchGeometryAtLargeText() {
        var created = 0
        content {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                PocketHomeFrame(PocketPreviewStates.home, HomeTab.Chats, 2, false, false, {}, { created++ }, {}, {}) {
                    ChatsHome(PocketPreviewStates.home, {}, {}, { _, _, _ -> }, {}, {})
                }
            }
        }
        compose.onNodeWithTag("home-header-search").performClick()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("登录")
        compose.onNodeWithText("搜索结果").assertExists()
        val navigation = compose.onNodeWithTag("home-navigation").fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithTag("home-new-chat").fetchSemanticsNode().boundsInRoot
        assertTrue("New chat belongs to the header, away from the bottom navigation", create.bottom < navigation.top)
        compose.onNodeWithTag("home-settings").assertDoesNotExist()
        compose.onAllNodesWithTag("home-new-chat").assertCountEquals(1)
        compose.onNodeWithText("管理").assertExists()
        listOf("home-host", "home-header-search", "home-new-chat", "home-tab-chats", "home-tab-attention", "home-tab-devices").forEach { tag ->
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("home-new-chat").performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun returningToARuntimeRestoresItsProjectAndSearchWithoutLeakingToAnotherComputer() {
        val first = chat("a", "电脑 A 会话").copy(runtimeId = "runtime-a", cwd = "D:/A")
        val second = chat("b", "电脑 B 会话").copy(key = ThreadKey("other-pc", "store", "b"), runtimeId = "runtime-b", cwd = "D:/B")
        val runtime = PocketPreviewStates.home.runtimes.first()
        var state by mutableStateOf(PocketPreviewStates.home.copy(computerId = first.key.computerId, chats = listOf(first, second),
            runtimes = listOf(runtime.copy(id = "runtime-a"), runtime.copy(id = "runtime-b"))))
        var selectedRuntime by mutableStateOf("runtime-a")
        content { ChatsHome(state, {}, {}, { _, _, _ -> }, {}, {}, runtimeFilter = selectedRuntime) }
        val firstProject = "home-project-${HomeProject(first.key.computerId, first.cwd).id}"
        compose.onNodeWithTag(firstProject).performClick()
        compose.onNodeWithContentDescription("搜索会话").performClick()
        compose.onNodeWithContentDescription("搜索对话").performTextInput("电脑 A")
        compose.runOnIdle { state = state.copy(computerId = second.key.computerId); selectedRuntime = "runtime-b" }
        compose.onNodeWithTag("home-project-all").assertIsSelected()
        compose.onNodeWithContentDescription("搜索对话").assertDoesNotExist()
        compose.onNodeWithTag("home-chat-${second.key.encoded}").assertExists()
        compose.runOnIdle { state = state.copy(computerId = first.key.computerId); selectedRuntime = "runtime-a" }
        compose.onNodeWithTag(firstProject).assertIsSelected()
        compose.onNodeWithContentDescription("搜索对话").assertTextContains("电脑 A")
        compose.onNodeWithTag("home-chat-${first.key.encoded}").assertExists()
        compose.onNodeWithTag("home-chat-${second.key.encoded}").assertDoesNotExist()
    }

    @Test fun pendingEntryAndOfflineRowsRemainActionableAndLoadingDoesNotClaimEmpty() {
        val row = chat("pending", "需要确认的会话").copy(status = "pending")
        val question = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("question"))
            put("submitted", kotlinx.serialization.json.JsonPrimitive(false))
        }
        var opened = 0
        var attention = 0
        var state by mutableStateOf(PocketPreviewStates.home.copy(online = false, chats = listOf(row),
            pendingQuestions = mapOf(row.key.encoded to listOf(question))))
        content { ChatsHome(state, { opened++ }, {}, { _, _, _ -> }, {}, {}, onAttention = { attention++ }) }
        compose.onNodeWithTag("home-sync-state").assertExists()
        compose.onNodeWithTag("home-attention").performClick()
        compose.onNodeWithTag("home-chat-${row.key.encoded}").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, attention); state = state.copy(chats = emptyList(), busy = true) }
        compose.onNodeWithText(compose.activity.getString(dev.codexpocket.app.R.string.v03_home_loading)).assertDoesNotExist() // Offline remains honest even while an action is busy.
        compose.onNodeWithText(compose.activity.getString(dev.codexpocket.app.R.string.no_chats)).assertExists()
        compose.runOnIdle { state = state.copy(directoryLoading = true) }
        compose.onNodeWithText(compose.activity.getString(dev.codexpocket.app.R.string.no_chats)).assertDoesNotExist()
    }
}
