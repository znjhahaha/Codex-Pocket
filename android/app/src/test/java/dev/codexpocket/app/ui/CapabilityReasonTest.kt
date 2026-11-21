package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityReasonTest {
    @Test fun desktopFollowerLimitationIsNotReportedAsOldNativeVersion() {
        val key=ThreadKey("pc","store","t")
        val runtime=RuntimeInfo("r","desktop","0.170.0","store",true,setOf("t"),setOf("thread/read"),role="desktopFollower")
        val chat=ChatEntity(key,"r","desktop","Title","","D:/Notes",0,"idle",false)
        val state=PocketState(online=true,runtimes=listOf(runtime),selected=chat)
        assertEquals("此桌面连接尚未完整接入，请在电脑空闲后重启 Codex",actionUnavailableReason(state,"thread/fork"))
        assertEquals("等待 Codex 就绪",actionUnavailableReason(state.copy(runtimes=listOf(runtime.copy(online=false))),"thread/fork"))
    }
}
