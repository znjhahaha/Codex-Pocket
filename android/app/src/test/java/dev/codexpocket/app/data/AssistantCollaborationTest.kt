package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantCollaborationTest {
    @Test fun taskAssignmentAndMemberAuthorshipSurviveHistoryRead() {
        val raw = pocketJson.parseToJsonElement("""{
          "enabled":true,"members":[{"id":"m","name":"研究员","duty":"核对来源","main_thread_id":"native-main","thread_id":"native-task","directory":"D:/project","active":true}],
          "groups":[{"id":"g","title":"核查","task_ids":["t"],"requires_summary":true,"sealed":true,"status":"running"}],
          "tasks":[{"id":"t","prompt":"核查资料","status":"running","assignment":{"member_id":"m","group_id":"g","dependencies":["prior"],"completion_criteria":"引用可验证","continue_from_task":"prior"}}],
          "messages":[{"id":"reply","role":"Assistant","text":"已核对","at_ms":42,"memberId":"m","taskId":"t","groupId":"g","sourceThreadId":"native-task","authorName":"研究员","eventId":"e"}],
          "deliveries":[{"id":"d","message":"补充要求","member_id":"m","task_id":"t","status":"queued"}]
        }""").jsonObject
        val snapshot = AssistantSnapshot.from(raw)
        assertEquals("native-main", snapshot.members.single().mainThreadId)
        assertEquals("引用可验证", snapshot.assignmentFor("t")!!.completionCriteria)
        assertEquals(listOf("prior"), snapshot.assignmentFor("t")!!.dependencies)
        assertEquals("prior", snapshot.assignmentFor("t")!!.continueFromTask)
        assertEquals(listOf("t"), snapshot.groups.single().taskIds)
        assertEquals("待送达", snapshot.deliveries.single().label)
        val message = AssistantChatMessage.from(raw.array("messages").single().asObject())!!
        assertEquals("Assistant", message.role)
        assertEquals("研究员", message.authorName)
        assertEquals("native-task", message.sourceThreadId)
        assertEquals(42L, message.atMs)
        assertEquals("e", message.eventId)
    }

    @Test fun flattenedAssignmentsAlsoWorkAndTargetSendDoesNotRequireRuntime() {
        val raw = pocketJson.parseToJsonElement("""{"assignments":[{"task_id":"t","member_id":"m","dependencies":[],"completion_criteria":"完成"}]}""").jsonObject
        assertEquals("m", AssistantSnapshot.from(raw).assignmentFor("t")!!.memberId)
        val action = AssistantActions.send("", "补充要求", null, memberId="m", taskId="t")
        assertFalse(action.params.containsKey("runtimeId"))
        assertEquals("m", action.params.string("memberId"))
        assertEquals("t", action.params.string("taskId"))
        assertTrue(AssistantActions.prepare().params.isEmpty())
    }

    @Test fun explicitAnsweredHistoryUsesCanonicalSecretRedactionAndUncertainDeliveryLabels() {
        val raw=pocketJson.parseToJsonElement("""{"questionHistory":[{"id":"h","operationId":"op","requestId":"q","atMs":42,"item":{
            "type":"dynamicToolCall","tool":"request_user_input","status":"completed","success":true,
            "arguments":{"questions":[{"id":"q","question":"访问口令","isSecret":true}]},
            "contentItems":[{"type":"inputText","text":"{\"answers\":{\"q\":{\"answers\":[\"sensitive-answer-fixture\"]}}}"}]
        }}]}""").jsonObject
        val snapshot=AssistantSnapshot.from(raw)
        val item=snapshot.questionHistory.single()
        assertEquals("op",item.operationId)
        assertEquals(42L,item.atMs)
        assertEquals("敏感回答已隐藏",NativeQuestionHistory.from(item.item)!!.answers["q"])
        assertFalse(snapshot.toString().contains("sensitive-answer-fixture"))
        assertEquals("待确认送达",AssistantDelivery("d","text",null,null,"unknown",null).label)
        assertEquals("正在送达",AssistantDelivery("d","text",null,null,"sending",null).label)
        assertEquals("暂停送达",AssistantDelivery("d","text",null,null,"paused",null).label)
        assertTrue(runCatching {AssistantActions.read(0,limit=101)}.isFailure)
    }

    @Test fun reassignedTaskCannotReplaceHistoricOperationAuthorIncludingExplicitNoMember() {
        val base=AssistantSnapshot(assignments=listOf(AssistantAssignment("task","new")),events=listOf(
            AssistantEvent(1,"original","op","completed","",1,taskId="task",memberId="old")))
        assertEquals("old",base.operationMemberId("op","task"))
        val explicitNone=AssistantSnapshot.from(pocketJson.parseToJsonElement("""{
            "assignments":[{"task_id":"task","member_id":"new"}],
            "events":[{"id":"e","sequence":1,"operation_id":"op","kind":"completed","member_id":null}]
        }""").jsonObject)
        assertNull(explicitNone.operationMemberId("op","task"))
        assertEquals("new",base.copy(events=emptyList()).operationMemberId("op","task"))
    }
}
