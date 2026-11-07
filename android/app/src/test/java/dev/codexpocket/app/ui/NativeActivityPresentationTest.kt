package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeActivityPresentationTest {
    private fun item(json: String, complete: Boolean = true) = TimelineItem.from("turn", pocketJson.parseToJsonElement(json).jsonObject, complete)

    @Test fun activeTurnSnapshotDoesNotInventRunningStatusesForRecordedResults() {
        val thread = pocketJson.parseToJsonElement("""{
            "id":"thread","status":{"type":"active"},"turns":[{
                "id":"turn","status":"inProgress","items":[
                    {"id":"result","type":"functionCallOutput","name":"lookup","output":[{"type":"input_text","text":"Found"}]},
                    {"id":"search","type":"webSearch","query":"docs","results":[{"title":"Docs","url":"https://example.test"}]},
                    {"id":"image","type":"imageView","path":"/work/chart.png"},
                    {"id":"command","type":"commandExecution","command":"./check","cwd":"/work","status":"inProgress"}
                ]
            }]
        }""").jsonObject
        val snapshot = NativeReducer.snapshot(thread)
        val items = snapshot.items.values.toList()
        assertEquals("turn", snapshot.activeTurn)
        assertEquals(listOf("result", "search", "image", "command"), items.map { it.itemId })
        assertEquals(listOf(ActivityStatus.Completed, ActivityStatus.Unknown, ActivityStatus.Unknown, ActivityStatus.Running),
            items.map { NativeActivityPresentation.describe(it).status })
        assertEquals("Found", NativeActivityPresentation.describe(items.first()).output.text)
        assertEquals(thread.array("turns").first().asObject().array("items").toList(), items.map { it.raw })
        assertEquals(items.map { it.key }, ConversationPresentation.rows(snapshot).flatMap { it.items }.map { it.key })
    }

    @Test fun statuslessLiveToolStillTransitionsFromRunningToCompleted() {
        val started = pocketJson.parseToJsonElement("""{"method":"item/started","params":{"threadId":"thread","turnId":"turn","item":{"id":"search","type":"webSearch","query":"docs"}}}""").jsonObject
        val running = NativeReducer.reduce(NativeTimeline(activeTurn = "turn"), started, "epoch:1")
        assertEquals(ActivityStatus.Running, NativeActivityPresentation.state(running.items.values.single()))
        val completed = JsonObject(started + ("method" to JsonPrimitive("item/completed")))
        val done = NativeReducer.reduce(running, completed, "epoch:2")
        assertEquals(running.items.keys, done.items.keys)
        assertEquals(ActivityStatus.Completed, NativeActivityPresentation.state(done.items.values.single()))
    }

    private fun diffEvent(turn: String, vararg paths: String): JsonObject = buildJsonObject {
        put("method", "turn/diff/updated")
        put("params", buildJsonObject {
            put("threadId", "thread"); put("turnId", turn)
            put("diff", paths.joinToString("\n") { path -> "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n-old\n+new" })
        })
    }

    @Test fun cumulativeDiffEventsDoNotMultiplyNativeFileChangesOrLoseEventIdentity() {
        val change = pocketJson.parseToJsonElement("""{"method":"item/completed","params":{"threadId":"thread","turnId":"turn","item":{"id":"change","type":"fileChange","status":"completed","changes":[{"path":"/work/a.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-old\n+new"}]}}}""").jsonObject
        val native = NativeReducer.reduce(NativeTimeline(), change, "epoch:1")
        val first = NativeReducer.reduce(native, diffEvent("turn", "a.kt"), "epoch:2")
        val second = NativeReducer.reduce(first, diffEvent("turn", "a.kt"), "epoch:3")
        val keys = second.items.keys.toList()
        assertEquals(listOf("change", "event:epoch:2", "event:epoch:3"), second.items.values.map { it.itemId })
        assertEquals(1, NativeActivityPresentation.summary(second.items.values.toList()).files)
        assertEquals(keys, second.items.keys.toList())
        assertEquals(1, activityFiles(second.items.values.first()).single().additions)
        assertEquals(1, activityFiles(second.items.values.first()).single().deletions)
    }

    @Test fun addedFileWithBareContentCountsAndNumbersEveryLineAsAdded() {
        val file = activityFiles(item("""{"id":"f","type":"fileChange","status":"completed","changes":[{"path":"a.mjs","kind":{"type":"add"},"diff":"one\ntwo\nthree\n"}]}""")).single()
        assertEquals(3, file.additions)
        assertEquals(0, file.deletions)
        val lines = parseReviewLines(file.diff, true).filter { !it.hunk }
        assertEquals(listOf(1, 2, 3), lines.map { it.newLine })
        assertTrue(lines.all { it.oldLine == null })
        // A real unified diff for an added file is left untouched.
        assertEquals("@@ -0,0 +1 @@\n+x", wholeFileDiff("add", "@@ -0,0 +1 @@\n+x"))
    }

    @Test fun aggregateOnlySummaryUsesLatestDiffPerTurnIncludingEmptyDiff() {
        val first = NativeReducer.reduce(NativeTimeline(), diffEvent("one", "a.kt", "b.kt"), "epoch:1")
        val latest = NativeReducer.reduce(first, diffEvent("one", "a.kt"), "epoch:2")
        assertEquals(1, NativeActivityPresentation.summary(latest.items.values.toList()).files)
        val otherTurn = NativeReducer.reduce(latest, diffEvent("two", "a.kt"), "epoch:3")
        assertEquals(2, NativeActivityPresentation.summary(otherTurn.items.values.toList()).files)
        val cleared = NativeReducer.reduce(otherTurn, diffEvent("one"), "epoch:4")
        assertEquals(1, NativeActivityPresentation.summary(cleared.items.values.toList()).files)
        assertEquals(4, cleared.items.size)
    }

    @Test fun commandKeepsIdentityCwdExitAndStreamWithoutRepeatingCommand() {
        val command = item("""{"id":"cmd","type":"commandExecution","command":"./check","cwd":"/work","status":"inProgress","aggregatedOutput":"first\n"}""", false)
        val view = NativeActivityPresentation.describe(command.copy(text = command.text + "second\n"))
        assertEquals(command.key, view.key)
        assertEquals(ActivityStatus.Running, view.status)
        assertEquals("./check", view.command!!.command)
        assertEquals("/work", view.command.cwd)
        assertEquals("first\nsecond\n", view.output.text)
        assertNull(view.command.exitCode)
        val failed = NativeActivityPresentation.describe(item("""{"id":"cmd","type":"commandExecution","command":"./check","status":"completed","exitCode":7,"aggregatedOutput":"denied"}"""))
        assertEquals(ActivityStatus.Failed, failed.status)
        assertEquals(7, failed.command!!.exitCode)
    }

    @Test fun explicitFailureAndUnknownStateAreNeverShownAsSuccess() {
        assertEquals(ActivityStatus.Failed, NativeActivityPresentation.describe(item("""{"id":"m","type":"mcpToolCall","status":"completed","result":{"isError":true,"content":[{"type":"text","text":"denied"}]}}""")).status)
        assertEquals(ActivityStatus.Failed, NativeActivityPresentation.describe(item("""{"id":"d","type":"dynamicToolCall","status":"completed","success":false}""")).status)
        assertEquals(ActivityStatus.Unknown, NativeActivityPresentation.describe(item("""{"id":"d","type":"dynamicToolCall","status":"futureState"}""")).status)
        assertEquals(ActivityStatus.Unknown, NativeActivityPresentation.describe(item("""{"id":"c","type":"commandExecution","status":"inProgress"}""", false), completedTurn = true).status)
    }

    @Test fun typedToolResultsAreBoundedAndUnknownObjectsNeverSerialize() {
        val mcp = item("""{"id":"m","type":"mcpToolCall","server":"docs","tool":"lookup","result":{"content":[{"type":"text","text":"Found"},{"type":"image","data":"SECRET"}],"structuredContent":{"secret":"HIDDEN"}}}""")
        val view = NativeActivityPresentation.describe(mcp)
        assertEquals("lookup", view.title)
        assertEquals("docs", view.subtitle)
        assertEquals("Found", view.output.text)
        assertFalse(ConversationPresentation.detail(mcp).contains("SECRET"))
        val unknown = item("""{"id":"m","type":"mcpToolCall","result":{"newShape":{"private":"DO_NOT_RENDER"}}}""")
        assertTrue(NativeActivityPresentation.describe(unknown).output.text.isBlank())
        assertFalse(ConversationPresentation.detail(unknown).contains("DO_NOT_RENDER"))
        val large = mcp.copy(raw = buildJsonObject {
            put("type", "mcpToolCall")
            put("result", buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "a".repeat(30_000)) })
                })
            })
        })
        val output = NativeActivityPresentation.describe(large).output
        assertTrue(output.truncated)
        assertTrue(output.text.length <= NativeActivityPresentation.OUTPUT_LIMIT)
    }

    @Test fun structuredResultsExposeOnlyAllowlistedHumanFields() {
        val structured = item("""{"id":"m","type":"mcpToolCall","result":{"content":[],"structuredContent":{"title":"Lookup result","summary":"Two matches","token":"SECRET","nested":{"text":"NOT_RECURSIVE"}}}}""")
        assertEquals("Lookup result\nTwo matches", NativeActivityPresentation.describe(structured).output.text)
        val content = item("""{"id":"m","type":"mcpToolCall","result":{"content":[{"type":"text","text":"Canonical text"}],"structuredContent":{"summary":"Duplicate summary"}}}""")
        assertEquals("Canonical text", NativeActivityPresentation.describe(content).output.text)
    }

    @Test fun missingCommandFieldDoesNotPromoteOutputToAnInventedCommand() {
        val missing = item("""{"id":"c","type":"commandExecution","status":"failed","aggregatedOutput":"Permission denied"}""")
        val descriptor = NativeActivityPresentation.describe(missing)
        assertEquals("", descriptor.command!!.command)
        assertEquals("Permission denied", descriptor.output.text)
    }

    @Test fun dynamicAndAsyncResultsReadOnlyTextContent() {
        val dynamic = item("""{"id":"d","type":"dynamicToolCall","namespace":"functions","tool":"test","contentItems":[{"type":"inputText","text":"Passed"},{"type":"inputImage","imageUrl":"secret"}],"success":true}""")
        assertEquals("Passed", NativeActivityPresentation.describe(dynamic).output.text)
        val async = item("""{"id":"a","type":"functionCallOutput","name":"test","output":[{"type":"input_text","text":"Later"}]}""")
        assertEquals(ActivityKind.Tool, NativeActivityPresentation.describe(async).kind)
        assertEquals("Later", NativeActivityPresentation.describe(async).output.text)
    }

    @Test fun webActionsExposeQueriesUrlsAndFindPatternWithoutArgumentsDump() {
        val search = NativeActivityPresentation.describe(item("""{"id":"w","type":"webSearch","action":{"type":"search","queries":["first","second"]}}"""))
        assertEquals(WebAction.Search, search.webAction)
        assertEquals("first · second", search.subtitle)
        val find = NativeActivityPresentation.describe(item("""{"id":"w","type":"webSearch","action":{"type":"findInPage","url":"https://example.test","pattern":"install"}}"""))
        assertEquals(WebAction.Find, find.webAction)
        assertTrue(find.subtitle.contains("install"))
        assertTrue(find.subtitle.contains("https://example.test"))
    }

    @Test fun agentsKeepActualIdsNamesAndIndependentStatusWithoutInventedReceivers() {
        val view = NativeActivityPresentation.describe(item("""{"id":"call","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child-1","child-2"],"agentsStates":{"child-1":{"name":"Reviewer","status":"running"},"child-2":{"status":"errored","message":"failed"}}}"""))
        assertEquals(listOf("child-1", "child-2"), view.agents.map { it.threadId })
        assertEquals("Reviewer", view.agents.first().name)
        assertEquals(ActivityStatus.Running, view.agents.first().status)
        assertEquals(ActivityStatus.Failed, view.agents.last().status)
        assertEquals("", view.agents.last().name)
        assertTrue(NativeActivityPresentation.describe(item("""{"id":"call","type":"collabAgentToolCall","tool":"spawnAgent","prompt":"Invent nothing"}""")).agents.isEmpty())
    }

    @Test fun nativeSubAgentActivityUsesRecordedPathIdentityAndKind() {
        val activity = item("""{"id":"event","type":"subAgentActivity","agentThreadId":"native-child","agentPath":"root/reviewer","kind":"interrupted"}""")
        val descriptor = NativeActivityPresentation.describe(activity)
        assertEquals(ActivityKind.Agents, descriptor.kind)
        assertEquals("native-child", descriptor.agents.single().threadId)
        assertEquals("root/reviewer", descriptor.agents.single().name)
        assertEquals(ActivityStatus.Interrupted, descriptor.agents.single().status)
        assertTrue(ConversationPresentation.detail(activity).contains("root/reviewer"))
        assertTrue(NativeActivityPresentation.describe(activity.copy(raw = buildJsonObject { put("type", "subAgentActivity"); put("agentPath", "name without identity") })).agents.isEmpty())
    }

    @Test fun malformedKnownItemsAndHiddenHookDescriptorsNeverExposeObjects() {
        val malformed = item("""{"id":"x","type":"dynamicToolCall","tool":{"private":"secret"},"contentItems":[42,{"type":"inputText","text":{"secret":"hidden"}}]}""")
        val descriptor = NativeActivityPresentation.describe(malformed)
        assertEquals("", descriptor.title)
        assertEquals("", descriptor.output.text)
        val hook = malformed.copy(raw = buildJsonObject { put("type", "hookPrompt"); put("text", "secret") })
        assertEquals(ActivityKind.Unknown, NativeActivityPresentation.describe(hook).kind)
        assertEquals("", ConversationPresentation.detail(hook))
    }

    @Test fun summaryCountsCommandsFilesToolsAndFailuresInsteadOfCountingCommentaryAsTools() {
        val items = listOf(
            item("""{"id":"c","type":"commandExecution","status":"failed","exitCode":1}"""),
            item("""{"id":"f","type":"fileChange","changes":[{"path":"a","diff":"@@ -1 +1 @@\n-old\n+new"},{"path":"b","diff":""}]}"""),
            item("""{"id":"m","type":"mcpToolCall","tool":"check"}"""),
            item("""{"id":"s","type":"agentMessage","phase":"commentary","text":"Checked"}"""),
        )
        val summary = NativeActivityPresentation.summary(items)
        assertEquals(1, summary.commands)
        assertEquals(2, summary.files)
        assertEquals(1, summary.tools)
        assertEquals(1, summary.commentary)
        assertEquals(1, summary.failed)
    }
}
