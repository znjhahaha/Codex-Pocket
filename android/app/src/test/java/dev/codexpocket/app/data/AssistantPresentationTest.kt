package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantPresentationTest {
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
        assertEquals(obj("""{"message":"真实用户消息","runtimeId":"runtime-a","threadId":"thread-a"}"""), send.params)
        assertFalse(AssistantActions.send("runtime-a", "你好", null).params.containsKey("threadId"))
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.send("runtime-b", "你好", choice) }
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
        val action = AssistantActions.schedule("r", choice, AssistantScheduleDraft("汇总", "Daily", "2030-01-02", "09:30", 0, "60", zone, false), now)
        assertEquals("assistant/schedule/create", action.method)
        assertEquals(obj("""{"Daily":{"hour":9,"minute":30}}"""), action.params.obj("spec")["kind"])
        assertFalse(action.params.obj("spec").containsKey("directory"))
        assertEquals("authorized", action.params.string("threadId"))
        assertThrows(IllegalArgumentException::class.java) { AssistantActions.schedule("r", choice, AssistantScheduleDraft("汇总", "Daily", "2030-01-02", "09:30", 0, "60", zone, true), now) }
    }
}
