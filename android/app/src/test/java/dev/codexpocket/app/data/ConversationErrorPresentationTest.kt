package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationErrorPresentationTest {
    private fun error(message: String, retry: Boolean = false) = TimelineItem("turn", "error", "error", message,
        raw = buildJsonObject { put("params", buildJsonObject { put("willRetry", retry); put("error", buildJsonObject { put("message", message) }) }) })
    private fun active(item: TimelineItem) = NativeTimeline(linkedMapOf(item.key to item), activeTurn = "turn")

    @Test fun gatewayHtmlHasCompactReasonAndUnchangedRawDetail() {
        val raw = "unexpected status code 522: upstream_status: HTTP 522; cause: <!DOCTYPE html><html>" + "<style>long</style>".repeat(4000)
        val p = ConversationErrorPresentation.fromText(raw)
        assertEquals("上游连接超时 · HTTP 522", p.summary)
        assertEquals("请求未完成", p.title)
        assertEquals(raw, p.detail)
        assertFalse(p.retrying)
    }
    @Test fun retryRequiresNativeFlagAndLiveUnfinishedTurn() {
        val item = error("stream disconnected before completion: stream closed before response.completed", true)
        assertEquals("正在重新连接", ConversationErrorPresentation.from(item, active(item)).title)
        assertFalse(ConversationErrorPresentation.from(item, null).retrying)
        assertFalse(ConversationErrorPresentation.from(item, active(item).copy(completedTurns = setOf("turn"))).retrying)
        val ended = active(item).copy(turns = mapOf("turn" to NativeTurnInfo("turn", status = "failed")))
        assertFalse(ConversationErrorPresentation.from(item, ended).retrying)
    }
    @Test fun resumedContentAndLaterErrorsEndOldRetryLabel() {
        val item = error("Reconnecting... 1/5", true)
        val later = TimelineItem("turn", "reply", "agentMessage", "back online")
        val timeline = active(item).copy(items = linkedMapOf(item.key to item, later.key to later))
        assertFalse(ConversationErrorPresentation.from(item, timeline).retrying)
        assertEquals("正在重新连接 1/5", ConversationErrorPresentation.from(item, active(item)).title)
    }
    @Test fun ordinaryNumbersNeverBecomeInventedRetryCounts() {
        val item = error("HTTP 503; request failed 1/5", true)
        assertEquals("正在重新连接", ConversationErrorPresentation.from(item, active(item)).title)
        val terminal = error("Reconnecting... 1/5", false)
        assertEquals("请求未完成", ConversationErrorPresentation.from(terminal, active(terminal)).title)
    }
    @Test fun structuredNativeCodeSurvivesTurnSnapshotAndCompletion() {
        val turn = pocketJson.parseToJsonElement("""{"id":"turn","status":"failed","items":[],"error":{"message":"Gateway failed","codexErrorInfo":{"httpConnectionFailed":{"httpStatusCode":522}},"additionalDetails":"detail"}}""").jsonObject
        val snapshot = NativeReducer.snapshot(buildJsonObject { put("turns", JsonArray(listOf(turn))) })
        val event = NativeReducer.reduce(NativeTimeline(), buildJsonObject { put("method", "turn/completed"); put("params", buildJsonObject { put("turn", turn) }) })
        for (timeline in listOf(snapshot, event)) {
            val result = ConversationErrorPresentation.from(timeline.items.values.single(), timeline)
            assertEquals("上游连接超时 · HTTP 522", result.summary)
            assertEquals("Gateway failed\n\ndetail", result.detail)
            assertFalse(result.retrying)
        }
    }
    @Test fun unknownErrorIsBoundedWithoutHidingRawContent() {
        val raw = "long private explanation ".repeat(1000)
        val p = ConversationErrorPresentation.fromText(raw)
        assertTrue(p.summary.length <= 240)
        assertEquals(raw, p.detail)
        assertEquals("上游返回了错误页面", ConversationErrorPresentation.fromText("<!doctype html><script>bad()</script>").summary)
    }
}
