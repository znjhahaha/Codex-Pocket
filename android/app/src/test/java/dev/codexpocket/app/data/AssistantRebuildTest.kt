package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantRebuildTest {
    @Test fun v2AcceptsNewGoalsWhileAnOlderAttemptNeedsVerification() {
        val snapshot=AssistantSnapshot.from(pocketJson.parseToJsonElement("""{
          "enabled":true,"ready":true,"runtimeId":"r","assistantId":"a","protocolVersion":2,
          "acceptingMessages":true,"preparationStatus":"ready",
          "startupStages":[{"id":"coordinator","status":"reconciling"}],
          "domain":{"schema":2,"conversationId":"main:a","attention":[{"id":"verify:old","state":"open","attemptId":"old","kind":"verification"}]}
        }""").jsonObject)
        assertTrue("One unresolved attempt must not lock the main conversation",snapshot.prepared)
        assertNull(snapshot.preparationIssue)
    }
    @Test fun oldProtocolKeepsItsOriginalPreparationGate() {
        val snapshot=AssistantSnapshot(enabled=true,ready=true,startupStages=listOf(AssistantStartupStage("coordinator","reconciling")))
        assertFalse(snapshot.prepared)
    }
    @Test fun orderingDoesNotJumpWhenAnOlderTaskGetsANewEvent() {
        val first=AssistantTask("one","First",AssistantTaskStatus.Running,"o1",false,null)
        val second=first.copy(id="two",prompt="Second")
        val domain=AssistantDomain("main:a",listOf(AssistantTaskRecord("one",1,"running",null,null,10,true),
            AssistantTaskRecord("two",2,"running",null,null,20,true)),emptyList(),emptyList())
        val snapshot=AssistantSnapshot(tasks=listOf(second,first),domain=domain)
        assertEquals(listOf("one","two"),snapshot.orderedTasks().map {it.id})
        assertEquals(listOf("one","two"),snapshot.copy(domain=domain.copy(tasks=domain.tasks.map {it.copy(activitySequence=99)})).orderedTasks().map {it.id})
    }
    @Test fun unknownDomainStateNeverBecomesConfirmedStop() {
        val task=AssistantTask("t","Work",AssistantTaskStatus.Running,"o",false,null)
        val domain=AssistantDomain("main:a",listOf(AssistantTaskRecord("t",1,"stop_requested",null,null,0,false)),emptyList(),emptyList())
        assertEquals("正在停止，等待确认",AssistantSnapshot(domain=domain).taskStateLabel(task))
    }
}
