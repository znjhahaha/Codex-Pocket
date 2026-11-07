package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentParsingTest {
    private fun agents(json: String) = NativeActivityPresentation.describe(
        TimelineItem.from("turn", pocketJson.parseToJsonElement(json).jsonObject)
    ).agents

    @Test fun onlyStringNativeReceiverIdentitiesAreAccepted() {
        val result = agents("""{"id":"call","type":"collabAgentToolCall","receiverThreadIds":[42,true,null,"native-id"],"senderThreadId":"parent","agentsStates":{"parent":{"status":"running"}}}""")
        assertEquals(listOf("native-id"), result.map { it.threadId })
    }

    @Test fun interactionIsNotAnExplicitChildStateUpdate() {
        val result = agents("""{"id":"event","type":"subAgentActivity","agentThreadId":"child","agentPath":"root/reviewer","kind":"interacted"}""").single()
        assertEquals(ActivityStatus.Unknown, result.status)
        assertEquals("", result.nativeStatus)
        assertEquals("root/reviewer", result.name)
    }

    @Test fun unknownProtocolObjectsNeverBecomeNamesMessagesOrIdentities() {
        val result = agents("""{"id":"call","type":"collabAgentToolCall","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running","name":false,"nickname":123,"message":{"text":"private"}}},"arguments":{"receiverThreadIds":["invented"]}}""").single()
        assertEquals("child", result.threadId)
        assertEquals("", result.name)
        assertEquals("", result.message.text)
    }
}
