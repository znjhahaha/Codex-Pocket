package dev.codexpocket.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AssistantOutboxTest {
    private val preferences = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("outbox-test-${UUID.randomUUID()}", 0)
    private val binding = AssistantComposerBinding("pc", "scope", "chat-a")
    private fun action(id: String, text: String) = AssistantAction("assistant/send", buildJsonObject {
        put("message", text); put("conversationId", "chat-a")
    }, id, binding, listOf(LocalAttachment(UUID.randomUUID().toString(), "note.txt", "text/plain")))

    @Test fun persistContentAttachmentsAndIdentityBeforeClearingDraft() {
        val store = AssistantOutbox(preferences)
        preferences.edit().putString("draft", "first").commit()
        store.enqueue(action("one", "first"), "draft", "attachments")
        assertEquals("", preferences.getString("draft", null))
        val restored = AssistantOutbox(preferences).messages(binding).single()
        assertEquals("one", restored.commandId)
        assertEquals("first", restored.text)
        assertEquals("note.txt", restored.attachments.single().name)
        assertEquals("chat-a", restored.binding.conversationId)
    }

    @Test fun uncertainReceiptNeverReplaysAndLateReceiptCannotClearNewDraft() {
        val store = AssistantOutbox(preferences)
        store.enqueue(action("one", "first"), "draft", "attachments")
        preferences.edit().putString("draft", "second").commit()
        store.settle(binding.receiptKey("one"), "unknown", "核对中")
        assertFalse(store.messages(binding).single().canRetry)
        store.settle(binding.receiptKey("one"), "accepted")
        assertEquals("second", preferences.getString("draft", null))
        assertEquals("accepted", store.messages(binding).single().status)
    }

    @Test fun retryRequiresDefiniteFailureAndKeepsMessageWhileCreatingANewAdmission() {
        val store = AssistantOutbox(preferences)
        val original = action("one", "first")
        store.enqueue(original, "draft", "attachments")
        val wire=buildJsonObject {put("message","first");put("attachments",buildJsonArray {add(buildJsonObject {put("attachmentId","uploaded-id")})})}
        store.dispatched(binding.receiptKey("one"),wire)
        assertNull(store.retry(binding.receiptKey("one")))
        store.settle(binding.receiptKey("one"), "rejected", "模型不可用")
        val retry = checkNotNull(store.retry(binding.receiptKey("one")))
        assertNotEquals(original.commandId, retry.commandId)
        assertEquals(original.commandId, store.messages(binding).single().messageId)
        assertEquals(listOf(original.commandId), store.messages(binding).single().previousCommandIds)
        assertEquals(original.params, retry.params)
        assertEquals(original.attachments, retry.attachments)
        assertTrue(retry.retry)
        assertEquals(wire,AssistantOutbox(preferences).messages(binding).single().wireParams)
        assertNull(store.retry(binding.receiptKey("one")))
    }

    @Test fun postDispatchTimeoutAndUnknownWireErrorsNeverEnableReplay() {
        for (code in listOf("unknown", "timeout", "request_timeout", "runtime_offline", "runtime_unavailable")) {
            assertEquals(code, "unknown", assistantSendFailureStatus(true, dev.codexpocket.wire.WireException(code, "fixture")))
        }
        assertEquals("rejected", assistantSendFailureStatus(false, java.io.IOException("not sent")))
        assertEquals("rejected", assistantSendFailureStatus(true, dev.codexpocket.wire.WireException("rejected", "fixture")))
    }

    @Test fun nativeObservationStopsAcceptedRowsReappearingOutsideTheirHistoryPage() {
        val store = AssistantOutbox(preferences)
        store.enqueue(action("one", "first"), "draft", "attachments")
        store.observe(binding, setOf("user:one"))
        val restored = AssistantOutbox(preferences).messages(binding).single()
        assertTrue(restored.observed)
        assertEquals("accepted", restored.status)
        store.receipt(AssistantSendReceipt("one", "pc", "scope", "chat-a", "rejected", 1))
        assertEquals("accepted", store.messages(binding).single().status)
    }

    @Test fun conversationsCannotSeeOrSettleEachOthersMessages() {
        val store = AssistantOutbox(preferences)
        store.enqueue(action("one", "first"), "draft", "attachments")
        val other = binding.copy(conversationId = "chat-b")
        assertTrue(store.messages(other).isEmpty())
        store.settle(other.receiptKey("one"), "accepted")
        assertEquals("submitted", store.messages(binding).single().status)
    }

    @Test fun aLegacyMainMessageKeepsItsReceiptIdentityAfterPublicConversationMigration() {
        val store=AssistantOutbox(preferences)
        val legacy=binding.copy(conversationId=null)
        store.enqueue(action("legacy","first").copy(composerBinding=legacy),"draft","attachments")
        assertEquals("legacy",store.messages(binding.copy(conversationId="main:assistant")).single().commandId)
        assertTrue(store.messages(binding.copy(conversationId="chat-new")).isEmpty())
        store.settle(legacy.receiptKey("legacy"),"accepted")
        assertEquals("accepted",store.messages(binding.copy(conversationId="main:assistant")).single().status)
    }

    @Test fun staleRejectedReceiptCannotReopenRetryWhileNewAttemptWaits() {
        val store=AssistantOutbox(preferences)
        store.enqueue(action("one","first"),"draft","attachments")
        store.settle(binding.receiptKey("one"),"rejected","rejected")
        store.retry(binding.receiptKey("one"))
        store.receipt(AssistantSendReceipt("one","pc","scope","chat-a","rejected",1))
        assertFalse(store.messages(binding).single().canRetry)
    }

    @Test fun upgradeRetainsUnknownLegacySendWithoutOfferingAnIncompleteReplay() {
        val store=AssistantOutbox(preferences)
        preferences.edit().putString("draft","newer text").putString("attachment-pending:files","old").commit()
        store.adoptLegacy(binding,"old","sent text",emptyList(),"draft","files")
        val retained=store.messages(binding).single()
        assertEquals("unknown",retained.status)
        assertEquals("sent text",retained.text)
        assertEquals("newer text",preferences.getString("draft",null))
        assertNull(preferences.getString("attachment-pending:files",null))
        store.settle(retained.key,"rejected")
        assertNull(store.retry(retained.key))
    }
}
