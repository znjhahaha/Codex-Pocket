package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V051NativeActivityPresentationTest {
    private fun call(action: String, status: String, vararg children: String) = TimelineItem.from("turn", buildJsonObject {
        put("id", action); put("type", "collabAgentToolCall"); put("tool", action); put("status", status)
        put("receiverThreadIds", JsonArray(children.map(::JsonPrimitive)))
        put("agentsStates", buildJsonObject { children.forEach { put(it, buildJsonObject { put("status", "errored") }) } })
    })

    @Test fun createdAndSentLabelsCountReceiversNotCallEventsOrChildFailures() {
        assertEquals("已创建 2 个智能体", NativeActivityPresentation.agentCallLabel(NativeActivityPresentation.describe(call("spawnAgent", "completed", "a", "b"))))
        assertEquals("已发送消息 1 个智能体", NativeActivityPresentation.agentCallLabel(NativeActivityPresentation.describe(call("sendInput", "completed", "a"))))
        assertEquals("正在创建 1 个智能体", NativeActivityPresentation.agentCallLabel(NativeActivityPresentation.describe(call("spawnAgent", "inProgress", "a"))))
        assertEquals("创建 1 个智能体失败", NativeActivityPresentation.agentCallLabel(NativeActivityPresentation.describe(call("spawnAgent", "failed", "a"))))
        assertEquals("创建智能体", NativeActivityPresentation.agentCallLabel(NativeActivityPresentation.describe(call("spawnAgent", "futureState"))))
    }

    @Test fun agentCallsStaySeparateFromContiguousEditAndCommandGroups() {
        val before = TimelineItem("turn", "c1", "commandExecution", "git status")
        val created = call("spawnAgent", "completed", "native-child")
        val after = TimelineItem("turn", "c2", "commandExecution", "rg -n layout src")
        val file = TimelineItem("turn", "f", "fileChange")
        val sent = call("sendInput", "completed", "native-child")
        val items = listOf(before, created, after, file, sent)
        val rows = ConversationPresentation.rows(NativeTimeline(items.associateTo(linkedMapOf()) { it.key to it }), foldTasks = false)
        assertEquals(listOf(listOf(before.key), listOf(created.key), listOf(after.key, file.key), listOf(sent.key)), rows.map { it.items.map { value -> value.key } })
        assertEquals(items.map { it.key }, rows.flatMap { it.items }.map { it.key })
    }

    @Test fun projectionPreservesLongCommandOutputPathsAndActionArguments() {
        val output = "中文🙂\n".repeat(5000)
        val query = "layout".repeat(300)
        val command = "rg -n '$query' src"
        val path = "C:/work/" + "folder/".repeat(110)
        val item = TimelineItem.from("turn", buildJsonObject {
            put("id", "command"); put("type", "commandExecution"); put("command", command)
            put("cwd", path); put("aggregatedOutput", output); put("status", "completed"); put("exitCode", 0)
            put("commandActions", buildJsonArray { add(buildJsonObject { put("type", "search"); put("query", query); put("path", path); put("command", command) }) })
        })
        val value = NativeActivityPresentation.describe(item)
        assertEquals(command, value.command!!.command)
        assertEquals(command, value.command.display)
        assertEquals(path, value.command.cwd)
        assertEquals(query, value.command.steps.single().query)
        assertEquals(output, value.output.text)
        assertFalse(value.output.truncated)
    }

    @Test fun onlyTransportTruncationIsReportedAndUnknownOutputObjectsStayPrivate() {
        val raw = buildJsonObject { put("id", "c"); put("type", "commandExecution"); put("command", "check"); put("pocketOutputTruncated", true); put("aggregatedOutput", "kept") }
        assertTrue(NativeActivityPresentation.describe(TimelineItem.from("t", raw)).output.truncated)
        val unknown = TimelineItem.from("t", buildJsonObject {
            put("id", "m"); put("type", "mcpToolCall")
            put("result", buildJsonObject { put("structuredContent", buildJsonObject { put("credential", "PRIVATE") }) })
        })
        assertEquals("", NativeActivityPresentation.describe(unknown).output.text)
    }

    @Test fun structuredSummaryWithoutLegacyTextIsExposedWithoutMergingParagraphs() {
        val parts = listOf("**Assigning new data/UI work**", "## Checking NativeSettings length")
        val reasoning = TimelineItem("t", "reason", "reasoning", raw = buildJsonObject { put("summary", JsonArray(parts.map(::JsonPrimitive))) })
        val timeline = NativeTimeline(linkedMapOf(reasoning.key to reasoning), activeTurn = "t")
        assertEquals(reasoning, ConversationPresentation.liveReasoning(timeline))
        assertEquals(parts, ConversationPresentation.liveReasoning(timeline)!!.summaryParts)
        val command = TimelineItem("t", "c", "commandExecution", "check")
        assertEquals(reasoning, ConversationPresentation.liveReasoning(timeline.copy(items = timeline.items + (command.key to command))))
    }
}
