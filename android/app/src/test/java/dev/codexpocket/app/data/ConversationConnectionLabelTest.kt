package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class ConversationConnectionLabelTest {
    private val chat=ChatEntity(ThreadKey("pc","store","chat"),"runtime","desktop","Chat","","",1,"active",false)
    private val runtime=RuntimeInfo("runtime","desktop","1","store",true,setOf("chat"),emptySet())
    @Test fun readingConnectionDoesNotWaitForSendAuthority() {
        val state=PocketState(online=true,selected=chat,runtimes=listOf(runtime),capabilities=setOf(BrokerCapability.AccessRead.method),
            conversationLoadState=ConversationLoadState.Ready,conversationReadSource="native",timeline=NativeTimeline(activeTurn="turn",status="active"))
        assertNull(state.automaticConnectionLabel())
        assertFalse(state.accessAllowsSending())
        assertTrue(state.needsConversationControlRefresh())
    }
    @Test fun actualConnectionLossAndUnknownWritesRemainVisible() {
        val state=PocketState(online=false,selected=chat,connectionPhase="recovering")
        assertEquals("连接中",state.automaticConnectionLabel())
        assertEquals("离线",state.copy(connectionPhase="offline").automaticConnectionLabel())
        val recovery=ConversationRecovery("attaching","resume_result_unknown")
        assertEquals("确认结果中",state.copy(online=true,conversationRecovery=mapOf(chat.key to recovery)).automaticConnectionLabel())
    }
    @Test fun localRuntimeStartupAndSessionRecoveryHaveSeparateLabels() {
        val state = PocketState(selected = chat, connectionPhase = "recovering")
        assertEquals("正在启动本机 Codex", state.automaticConnectionLabel(local = true))
        assertEquals("本机运行时未启动", state.copy(connectionPhase = "offline").automaticConnectionLabel(local = true))
        val reading = state.copy(online = true, conversationLoadState = ConversationLoadState.Ready,
            conversationReadSource = "persisted")
        assertNull(reading.automaticConnectionLabel(local = true))
        assertTrue(reading.needsConversationControlRefresh())
        assertEquals("正在恢复会话", reading.copy(conversationRecovery = mapOf(chat.key to
            ConversationRecovery("attaching"))).automaticConnectionLabel(local = true))
        assertEquals("会话待恢复", reading.copy(conversationRecovery = mapOf(chat.key to
            ConversationRecovery("blocked"))).automaticConnectionLabel(local = true))
    }
}
