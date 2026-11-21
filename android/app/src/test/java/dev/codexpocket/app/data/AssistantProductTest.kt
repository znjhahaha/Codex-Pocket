package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantProductTest {
    @Test fun generalScheduleDoesNotInventProjectPermission() {
        val action=AssistantActions.schedule("runtime",null,AssistantScheduleDraft(prompt="整理今日资料",kind="Daily"),1_800_000_000_000)
        assertEquals("assistant/schedule/create",action.method)
        assertFalse(action.params.containsKey("threadId"))
        assertEquals(JsonPrimitive("Asia/Shanghai"),action.params.obj("spec").obj("zone")["name"])
        assertThrows(IllegalArgumentException::class.java) {
            AssistantActions.schedule("runtime",null,AssistantScheduleDraft(prompt="修改项目",kind="Daily",writes=true),1_800_000_000_000)
        }
    }
    @Test fun snapshotKeepsUnknownAvailabilityAndOriginalTaskProvenance() {
        val snapshot=AssistantSnapshot.from(pocketJson.parseToJsonElement("""{"artifacts":[{"id":"a","taskId":"t","operationId":"o","kind":"file","uri":"C:/private/report.pdf","version":"o","availability":"reference"}],"scheduleRuns":[{"id":"run","schedule_id":"schedule","task_id":"t","status":"future_status"}],"memories":[{"id":"m","text":"中文"}],"memoryMetadata":{"m":{"source":"user:1","scope":"personal","automatic":true}}}""").jsonObject)
        assertEquals("t",snapshot.artifacts.single().taskId)
        assertFalse(snapshot.artifacts.single().canExportText)
        assertNull(snapshot.artifacts.single().safeWebUri)
        assertEquals("待核对",snapshot.scheduleRuns.single().statusLabel)
        assertEquals("user:1",snapshot.memories.single().source)
        assertTrue(snapshot.memories.single().automatic)
    }
    @Test fun taskOrderDoesNotFollowStreamEventsAndPendingDoesNotIncludeUnknown() {
        val tasks=listOf(AssistantTask("a","整理资料",AssistantTaskStatus.Running,"oa",false,null),AssistantTask("b","写总结",AssistantTaskStatus.WaitingUser,"ob",false,null))
        val snapshot=AssistantSnapshot(tasks=tasks,operations=listOf(AssistantOperation("oa","Execution","a",AssistantOperationPhase.Unknown,false,null,AssistantQuestion("q","Input".let {AssistantQuestionKind.Input},"确认"),null)))
        assertEquals(listOf("a","b"),snapshot.visibleTasks("",AssistantTaskView.All).map {it.id})
        assertEquals(listOf("b"),snapshot.visibleTasks("",AssistantTaskView.Pending).map {it.id})
        assertTrue(snapshot.pendingOperations().isEmpty())
    }
    @Test fun unsafeResultLinksAreNeverOpenedAsWebLinks() {
        listOf("javascript:alert(1)","file:///C:/private/key","intent://x","https:foo").forEach {uri ->
            val a=AssistantArtifact("a",null,null,"x","link",null,uri,"v",null,"external")
            assertNull(a.safeWebUri)
        }
    }
}
