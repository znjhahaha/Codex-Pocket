package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectCreationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var state by mutableStateOf(PocketState(online = true, computerId = "pc", computerName = "工作电脑",
        runtimes = listOf(runtime("desktop"), runtime("cli")), capabilities = setOf("thread/start")))
    private val submitted = mutableListOf<List<Any?>>()
    private fun runtime(source: String) = RuntimeInfo(source, source, "0.170.0", "store", true, emptySet(), setOf("thread/start"))
    private fun show(theme: String = "light") {
        compose.activity.getSharedPreferences("settings", 0).edit().clear().commit()
        compose.activity.runOnUiThread {
            compose.activity.setContent { PocketTheme(theme) {
                PocketBackdrop {
                NewChatDialog(state, {}) { mode, path, runtime, create -> submitted.add(listOf(mode, path, runtime, create)) }
            } } }
        }
        compose.waitForIdle()
    }
    private fun click(text: String) = compose.onNodeWithText(text, substring = false).performScrollTo().performClick()
    private fun submit() = compose.onNodeWithTag("project-submit").performClick()
    private fun openProjects() = compose.onNodeWithTag("project-picker-open").performScrollTo().performClick()
    private fun chooseProject(path: String) {
        compose.onNodeWithTag("project-list").performScrollToNode(hasText(projectPathLabel(path)))
        compose.onNode(hasText(projectPathLabel(path)) and hasAnyAncestor(hasTestTag("project-list"))).performClick()
    }

    @Test fun expandingAdvancedKeepsTheModeAndRuntimeUsedByCollapsedForm() {
        state = state.copy(sessionPreferences = SessionPreferences(SessionMode.Cli))
        show()
        submit()
        click("高级选项")
        submit()
        assertEquals(submitted.first(), submitted.last())
    }

    @Test fun explicitModeSelectionSurvivesCollapsingAdvanced() {
        show()
        click("高级选项")
        compose.onNodeWithTag("session-mode:cli").performScrollTo().performClick()
        click("收起高级选项")
        submit()
        assertEquals(SessionMode.Cli, submitted.single()[0])
        assertEquals("cli", submitted.single()[2])
    }

    @Test fun searchFindsProjectsBeyondSixAndSubmitsTheirFullPath() {
        state = state.copy(chats = (1..12).map { n -> ChatEntity(ThreadKey("pc", "store", "t$n"),
            "desktop", "desktop", "会话$n", "", "D:\\work\\project-$n", n.toLong(), "idle", false) })
        show()
        openProjects()
        compose.onNodeWithTag("project-search").performTextInput("project-12")
        chooseProject("D:\\work\\project-12")
        compose.onNodeWithTag("project-list").assertDoesNotExist()
        compose.onNodeWithTag("project-picker-open").assertTextContains("D:\\work\\project-12")
        submit()
        assertEquals("D:\\work\\project-12", submitted.single()[1])
        assertEquals(false, submitted.single()[3])
    }

    @Test fun directoryKindsRetainSeparateDraftsAndSubmitExplicitCreation() {
        show()
        compose.onNodeWithTag("project-path").performTextInput("D:\\existing")
        click("新建目录")
        compose.onNodeWithTag("project-submit").assertIsNotEnabled()
        compose.onNodeWithTag("project-path").performTextInput("D:\\new-project")
        click("已有目录")
        compose.onNodeWithTag("project-path").assertTextContains("D:\\existing")
        click("新建目录")
        compose.onNodeWithTag("project-path").assertTextContains("D:\\new-project")
        submit()
        assertEquals("D:\\new-project", submitted.single()[1])
        assertEquals(true, submitted.single()[3])
    }

    @Test fun failureKeepsInputsAndInflightCreationLocksAllChoices() {
        show()
        compose.onNodeWithTag("project-path").performTextInput("D:\\keep-me")
        submit()
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(error = "原始创建失败"))) }
        compose.onNodeWithTag("project-create-error").assertTextEquals("原始创建失败")
        compose.onNodeWithTag("project-path").assertTextContains("D:\\keep-me")
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(loading = true, phase = "正在创建"))) }
        compose.onNodeWithTag("project-submit").assertIsNotEnabled()
        compose.onNodeWithTag("project-path").assertIsNotEnabled()
        compose.onNodeWithText("新建目录").assertIsNotEnabled()
        compose.onNodeWithTag("project-advanced").performScrollTo().assertIsNotEnabled()
        assertEquals(1, submitted.size)
    }

    @Test fun offlineAndUnavailableRuntimeNeverEnableCreation() {
        state = state.copy(online = false)
        show()
        compose.onNodeWithTag("project-submit").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(online = true, runtimes = emptyList()) }
        compose.onNodeWithTag("project-submit").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(capabilities = setOf("runtime/ensure")) }
        compose.onNodeWithTag("project-submit").assertIsEnabled()
    }

    @Test fun runtimeArrivalDoesNotSilentlyChangeSelectedMode() {
        state = state.copy(runtimes = listOf(runtime("cli")))
        show()
        compose.runOnIdle { state = state.copy(runtimes = listOf(runtime("cli"), runtime("desktop"))) }
        submit()
        assertEquals(SessionMode.Cli, submitted.single()[0])
    }

    @Test fun readyCliTakesPriorityOverPreparingTheDefaultDesktop() {
        state = state.copy(runtimes = listOf(runtime("cli").copy(readinessStage = "ready")),
            capabilities = setOf("thread/start", "runtime/ensure", "runtime.ensure.cli"))
        show()
        submit()
        assertEquals(SessionMode.Cli, submitted.single()[0])
        assertEquals("cli", submitted.single()[2])
    }

    @Test fun preparationIsOnlyConsideredAfterEligibleExistingRuntimes() {
        val preparable = state.copy(capabilities = setOf("runtime/ensure", "runtime.ensure.cli"))
        assertEquals(SessionMode.Desktop, initialCreationMode(preparable))
        assertEquals(SessionMode.Cli, initialCreationMode(preparable.copy(sessionPreferences = SessionPreferences(SessionMode.Cli))))
        assertEquals(SessionMode.Desktop, initialCreationMode(preparable.copy(runtimes = emptyList())))
        assertEquals(SessionMode.Desktop, initialCreationMode(preparable.copy(
            runtimes = listOf(runtime("cli").copy(readinessStage = "starting")))))
        assertEquals(SessionMode.Desktop, initialCreationMode(preparable.copy(
            runtimes = listOf(runtime("cli").copy(capabilities = emptySet())))))
    }

    @Test fun extendedPathsAreDeduplicatedAndDisplayedCleanlyButSubmittedVerbatim() {
        val raw = "\\\\?\\D:\\work\\project-12"
        state = state.copy(chats = listOf(raw, "D:\\work\\project-12").mapIndexed { n, path ->
            ChatEntity(ThreadKey("pc", "store", "t$n"), "desktop", "desktop", "项目", "", path, n.toLong(), "idle", false)
        })
        show()
        openProjects()
        compose.onAllNodes(hasText("D:\\work\\project-12") and hasAnyAncestor(hasTestTag("project-list"))).assertCountEquals(1)
        compose.onAllNodes(hasText("\\\\?\\", substring = true)).assertCountEquals(0)
        chooseProject(raw)
        compose.onNodeWithTag("project-picker-open").assertTextContains("D:\\work\\project-12")
        openProjects()
        compose.onNode(hasText("D:\\work\\project-12") and hasAnyAncestor(hasTestTag("project-list"))).assertIsSelected()
        chooseProject(raw)
        submit()
        assertEquals(raw, submitted.single()[1])
        assertEquals(false, submitted.single()[3])
    }

    @Test fun uncPathDisplayDoesNotChangeServerOrShareAndOtherNamespacesStayIntact() {
        assertEquals("\\\\server\\share\\project", projectPathLabel("\\\\?\\UNC\\server\\share\\project"))
        assertEquals("D:\\work", projectPathLabel("\\\\?\\D:\\work"))
        assertEquals("\\\\?\\Volume{abc}\\work", projectPathLabel("\\\\?\\Volume{abc}\\work"))
        assertEquals("/home/user/project", projectPathLabel("/home/user/project"))
    }

    @Test fun leavingSearchPreservesDraftAndSelectionAndDoesNotDispatch() {
        state = state.copy(chats = listOf(ChatEntity(ThreadKey("pc", "store", "t"), "desktop", "desktop", "项目", "",
            "D:\\work\\existing", 1, "idle", false)))
        show()
        click("新建目录")
        compose.onNodeWithTag("project-path").performTextInput("D:\\work\\draft")
        click("已有目录")
        openProjects()
        compose.onNodeWithTag("project-search").performTextInput("nothing-matches")
        compose.onNodeWithText("没有匹配的项目").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        click("新建目录")
        compose.onNodeWithTag("project-path").assertTextContains("D:\\work\\draft")
        assertTrue(submitted.isEmpty())
    }

    @Test fun selectedProjectAndOriginalPathSurviveFailureWithoutAnotherSelection() {
        val raw = "\\\\?\\UNC\\server\\share\\project"
        state = state.copy(chats = listOf(ChatEntity(ThreadKey("pc", "store", "t"), "desktop", "desktop", "项目", "", raw, 1, "idle", false)))
        show()
        openProjects()
        chooseProject(raw)
        submit()
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(error = "项目已创建，关联尚未确认"))) }
        compose.onNodeWithTag("project-create-error").assertTextEquals("项目已创建，关联尚未确认")
        compose.onNodeWithTag("project-picker-open").assertTextContains("\\\\server\\share\\project")
        assertEquals(raw, submitted.single()[1])
        assertEquals(1, submitted.size)
    }

    @Test @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun rendersCompactLightFormWithReachableAction() {
        state = state.copy(chats = (1..12).map { n -> ChatEntity(ThreadKey("pc", "store", "t$n"),
            "desktop", "desktop", "会话$n", "", "D:\\Projects\\pocket-$n", n.toLong(), "idle", false) })
        show()
        compose.onNodeWithTag("project-submit").assertIsDisplayed()
        compose.onNodeWithTag("project-computer").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("project-advanced").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).assertCountEquals(0)
        compose.onNodeWithTag("project-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("session-mode:cli").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("project-submit").assertIsDisplayed()
        openProjects()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun rendersDarkNewDirectoryAndPreservesRealError() {
        show("dark")
        click("新建目录")
        compose.onNodeWithTag("project-path").performTextInput("D:\\Projects\\新项目")
        compose.runOnIdle { state = state.copy(actionStatus = mapOf("create" to ActionStatus(error = "安全连接请求失败，请检查电脑助手和配对状态"))) }
        compose.onNodeWithTag("project-create-error").assertIsDisplayed()
        compose.onNodeWithTag("project-submit").assertIsDisplayed()
    }

    @Test fun recentProjectSelectionWaitsForThePrimaryAction() {
        val raw = "\\\\?\\D:\\work\\recent"
        state = state.copy(chats = listOf(ChatEntity(ThreadKey("pc", "store", "t"), "desktop", "desktop", "项目", "", raw, 1, "idle", false)))
        show()
        compose.onNodeWithTag("project-recent:${nativeProjectPathKey(raw)}").performScrollTo().performClick()
        compose.onNodeWithTag("project-picker-open").assertTextContains("D:\\work\\recent")
        assertTrue(submitted.isEmpty())
        submit()
        assertEquals(raw, submitted.single()[1])
    }

    @Test fun closingPickerDuringTheWavePreservesDraftAndNeverCreates() {
        show()
        compose.onNodeWithTag("project-path").performTextInput("D:\\keep-this-draft")
        compose.mainClock.autoAdvance = false
        openProjects()
        compose.mainClock.advanceTimeBy(180)
        // At this point the close glyph is still outside the revealed area; use the real Back action.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("project-picker").assertDoesNotExist()
        compose.onNodeWithTag("project-path").assertTextContains("D:\\keep-this-draft")
        assertTrue(submitted.isEmpty())
    }
}
