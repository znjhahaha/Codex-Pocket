package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantPresentationTest {
    @Test fun sameNativeIdInDifferentRuntimeOrAccountNeverAliasesProjectSelection() {
        val choice=AssistantWorkspaceChoice("desktop-a","thread","Project",configStoreId="store-a")
        assertNotEquals(choice.selectionKey,choice.copy(runtimeId="desktop-b").selectionKey)
        assertNotEquals(choice.selectionKey,choice.copy(configStoreId="store-b").selectionKey)
        assertEquals(choice.selectionKey,choice.copy(label="Renamed").selectionKey)
    }
    private fun obj(text: String) = pocketJson.parseToJsonElement(text).jsonObject
    private val native = NativeModel("directory-id", "actual-model", "电脑模型", "", emptyList(), "", false, true)

    @Test fun readsActualSerdeShapesWithoutProjectingPrivatePromptsOrPaths() {
        val snapshot = AssistantSnapshot.from(obj("""{"enabled":true,"runtimeId":"runtime-a","coordinatorThreadId":"coordinator","settings":{"personality":"认真","tone":"简洁","model":"actual-model","timezone":"Asia/Shanghai","notifications":{"completed":true,"failed":false,"waiting_user":true}},"tasks":[{"id":"task-a","status":"waiting_user","prompt":"整理项目","active_run_id":"operation-a","reconciliation_required":false,"detail":"请选择","directory":"DO_NOT_PROJECT"}],"operations":[{"id":"operation-a","role":"Execution","task_id":"task-a","phase":"Waiting","suspended":false,"mapping":{"thread_id":"original-thread","turn_id":"turn-a","host_generation":"private"},"question":{"request_id":"question-a","kind":"Approval","text":"允许这次写入？"},"authorized_directories":["DO_NOT_PROJECT"],"prompt":"DO_NOT_PROJECT"}],"events":[{"sequence":9,"id":"e9","kind":"user_message","detail":"Explicit user handoff queued","at_ms":1000}],"memories":[{"id":"m1","text":"使用中文"}],"schedules":[{"id":"s1","spec":{"kind":{"Weekly":{"weekday":0,"hour":9,"minute":30}},"zone":{"name":"Asia/Shanghai"},"prompt":"周报","directory":"DO_NOT_PROJECT"},"next_ms":2000,"missed":true,"error":null}]}"""))
        assertEquals(true, snapshot.enabled)
        assertEquals(AssistantTaskStatus.WaitingUser, snapshot.tasks.single().status)
        assertEquals(AssistantOperationPhase.Waiting, snapshot.operations.single().phase)
        assertEquals("original-thread", snapshot.operations.single().threadId)
        assertEquals(AssistantQuestionKind.Approval, snapshot.operations.single().question!!.kind)
        assertEquals(9L, snapshot.after)
        assertTrue(snapshot.coordinatorReplies.isEmpty())
        assertFalse(snapshot.toString().contains("DO_NOT_PROJECT"))
        assertEquals("使用中文", snapshot.memories.single().text)
        assertTrue(snapshot.schedules.single().missed)
    }

    @Test fun coordinatorReplyRequiresActualResultAndUnknownNeverLooksReady() {
        val snapshot = AssistantSnapshot.from(obj("""{"operations":[{"id":"c1","role":"Coordinator","phase":"Completed","result":"已整理 **两项** 待办","mapping":{"thread_id":"coordinator"}},{"id":"x1","role":"Execution","phase":"Unknown","result":null}],"events":[{"sequence":1,"id":"e1","operation_id":"c1","kind":"summary","detail":"接下来核对结果","at_ms":1000}],"tasks":[{"id":"t","status":"future_status","prompt":"继续"}]}"""))
        assertNull(snapshot.enabled)
        assertEquals(AssistantTaskStatus.Unknown, snapshot.tasks.single().status)
        assertEquals(listOf("接下来核对结果", "已整理 **两项** 待办"), snapshot.coordinatorReplies.map { it.text })
        assertTrue(AssistantSnapshot.from(buildJsonObject {}).coordinatorReplies.isEmpty())
    }

    @Test fun actionsPreserveNativeEnumsAndOnlyAnAuthorizedThreadChoice() {
        val choice = AssistantWorkspaceChoice("runtime-a", "thread-a", "当前项目", true)
        val send = AssistantActions.send("runtime-a", "真实用户消息", choice)
        assertEquals(obj("""{"message":"真实用户消息","runtimeId":"runtime-a","threadId":"thread-a","sourceRuntimeId":"runtime-a"}"""), send.params)
        assertFalse(AssistantActions.send("runtime-a", "你好", null).params.containsKey("threadId"))
        val cross=AssistantActions.send("assistant", "你好", choice.copy(configStoreId="store-a"))
        assertEquals("assistant",cross.params.string("runtimeId"))
        assertEquals("runtime-a",cross.params.string("sourceRuntimeId"))
        assertEquals("store-a",cross.params.string("sourceConfigStoreId"))
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.send("assistant", "你好", choice.copy(runtimeId="")) }
        assertEquals(obj("""{"Answer":"使用现有配置"}"""), AssistantActions.respond("r", "o", "q", "使用现有配置").params["response"])
        assertEquals(obj("""{"Approval":{"approved":false}}"""), AssistantActions.approve("r", "o", "q", false).params["response"])
        val settings = AssistantSettings("认真", "简洁", "actual-model", "Asia/Shanghai", AssistantNotifications(true, false, true))
        assertEquals(JsonPrimitive("actual-model"), AssistantActions.settings("r", settings, listOf(native)).params.obj("settings")["model"])
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.settings("r", settings, emptyList()) }
        assertEquals(7L, AssistantActions.read(7).params.number("after"))
    }

    @Test fun schedulesUseActualZoneTransitionsAndRejectGapChooseEarlierFold() {
        val now = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        val zone = "America/New_York"
        val rules = AssistantScheduleTime.zoneRules(zone, now)
        assertEquals(zone, rules.string("name"))
        assertFalse(rules.array("transitions").isEmpty())
        assertEquals(-18000L, rules.number("initial_offset_seconds"))
        assertThrows(IllegalArgumentException::class.java) {
            AssistantScheduleTime.once("2030-03-10", "02:30", zone, now)
        }
        assertEquals(Instant.parse("2030-11-03T05:30:00Z").toEpochMilli(), AssistantScheduleTime.once("2030-11-03", "01:30", zone, now))
        val choice = AssistantWorkspaceChoice("r", "authorized", "项目", false)
        val action = AssistantActions.schedule("assistant", choice.copy(configStoreId="source-store"), AssistantScheduleDraft("汇总", "Daily", "2030-01-02", "09:30", 0, "60", zone, false), now)
        assertEquals("assistant/schedule/create", action.method)
        assertEquals(obj("""{"Daily":{"hour":9,"minute":30}}"""), action.params.obj("spec")["kind"])
        assertFalse(action.params.obj("spec").containsKey("directory"))
        assertEquals("authorized", action.params.string("threadId"))
        assertEquals("assistant", action.params.string("runtimeId"))
        assertEquals("r", action.params.string("sourceRuntimeId"))
        assertEquals("source-store", action.params.string("sourceConfigStoreId"))
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.schedule("r", choice, AssistantScheduleDraft("汇总", "Daily", "2030-01-02", "09:30", 0, "60", zone, true), now) }
    }

    @Test fun coordinatorModelFollowsTheSettingsSerdeShapeAndIsLeftOutWhenUnset() {
        fun settings(extra: String) = AssistantSnapshot.from(obj("""{"settings":{"personality":"认真","tone":"简洁","model":"actual-model","timezone":"Asia/Shanghai","notifications":{"completed":true,"failed":false,"waiting_user":true}$extra}}""")).settings!!
        assertNull("A computer that never sends the field means the coordinator follows `model`", settings("").coordinatorModel)
        assertNull(settings(""","coordinator_model":null""").coordinatorModel)
        val chosen = settings(""","coordinator_model":"actual-model"""")
        assertEquals("actual-model", chosen.coordinatorModel)
        assertEquals(chosen.copy(coordinatorModel = null), settings(""))
        val saved = AssistantActions.settings("r", chosen, listOf(native)).params.obj("settings")
        assertEquals(JsonPrimitive("actual-model"), saved["coordinator_model"])
        assertEquals(setOf("personality", "tone", "model", "timezone", "notifications", "coordinator_model"), saved.keys)
        val unset = AssistantActions.settings("r", chosen.copy(coordinatorModel = null), listOf(native)).params.obj("settings")
        assertEquals("An older computer receives exactly what it always did", setOf("personality", "tone", "model", "timezone", "notifications"), unset.keys)
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.settings("r", chosen.copy(coordinatorModel = "not-in-directory"), listOf(native)) }
    }

    @Test fun jobsExposeTheirBranchAndOnlyNameOneTaskWhenMergedOrDiscarded() {
        val jobs = AssistantSnapshot.from(obj("""{"runtimeId":"r","jobs":[
            {"taskId":"task-a","title":"加一个文件","directory":"D:/project","writes":true,"status":"done","activeTaskId":"task-b","threadId":"job-thread","model":null,"workspaceMode":"worktree","modeReason":"clean","branch":"pocket/job-1a2b","diffStats":{"files":1,"insertions":3,"deletions":0},"readyToMerge":true,"lastMerge":{"outcome":"conflict","files":["a.txt"],"atMs":5},"mergedAtMs":null,"discardedAtMs":null,"createdAtMs":1,"detail":"已添加 b.txt"},
            {"taskId":"task-c","title":"查资料","directory":"D:/project","writes":false,"status":"running","activeTaskId":"task-c","threadId":null,"workspaceMode":"direct","modeReason":"read_only","branch":null,"diffStats":null,"readyToMerge":false,"lastMerge":null,"mergedAtMs":null,"discardedAtMs":null,"createdAtMs":2,"detail":null},
            {"taskId":"task-d","title":"已合并","status":"done","workspaceMode":"worktree","branch":"pocket/job-3c4d","diffStats":{"files":2,"insertions":"many","deletions":1},"readyToMerge":"true","lastMerge":{"outcome":"merged","commit":"abc123","atMs":9},"mergedAtMs":9,"discardedAtMs":9},
            {"title":"没有标识"}]}""")).let { snapshot -> assertSame(snapshot.jobs[0], snapshot.job("task-b")); assertNull(snapshot.job("missing")); snapshot.jobs }
        assertEquals(listOf("task-a", "task-c", "task-d"), jobs.map { it.taskId })
        assertEquals(AssistantJob("task-a", "加一个文件", "done", true, "D:/project", "task-b", "job-thread", null, "worktree", "clean", "pocket/job-1a2b",
            AssistantJobDiffStats(1, 3, 0), true, AssistantJobMerge("conflict", 5, files = listOf("a.txt")), null, null, 1, "已添加 b.txt"), jobs[0])
        assertEquals("direct" to "read_only", jobs[1].workspaceMode to jobs[1].modeReason)
        assertNull(jobs[1].branch); assertNull(jobs[1].diffStats); assertNull(jobs[1].lastMerge); assertFalse(jobs[1].readyToMerge)
        assertEquals(AssistantJobMerge("merged", 9, "abc123"), jobs[2].lastMerge)
        assertEquals(9L to 9L, jobs[2].mergedAtMs to jobs[2].discardedAtMs)
        assertNull("Counts that are not numbers are not shown as counts", jobs[2].diffStats)
        assertFalse("Only the computer's own boolean offers a merge", jobs[2].readyToMerge)
        assertTrue(AssistantSnapshot.from(buildJsonObject {}).jobs.isEmpty())

        val merge = AssistantActions.mergeTask("r", "task-a"); val discard = AssistantActions.discardWorktree("r", "task-a")
        assertEquals("assistant/v2/task/merge" to obj("""{"runtimeId":"r","taskId":"task-a"}"""), merge.method to merge.params)
        assertEquals("assistant/v2/task/discard_worktree" to obj("""{"runtimeId":"r","taskId":"task-a"}"""), discard.method to discard.params)
        assertNull("A merge that ended in a conflict is tried again as a new command", merge.commandId)
        for (bad in listOf("", " ", "t".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { AssistantActions.mergeTask("r", bad) }
            assertThrows(IllegalArgumentException::class.java) { AssistantActions.discardWorktree("r", bad) }
            assertThrows(IllegalArgumentException::class.java) { AssistantActions.mergeTask(bad, "task-a") }
            assertThrows(IllegalArgumentException::class.java) { AssistantActions.discardWorktree(bad, "task-a") }
        }
    }
}
