package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class ThreadLinkBadgePolicyTest {
    @Test fun aNativeConversationEntryHasABadge() {
        val badge = markdownLinkBadge("codex://threads/01a1c7fb-7bda-7a71-a782-a020229c9573")
        assertNotNull("A native conversation must have a visible conversation entry", badge)
        assertFalse(badge!!.web)
    }
}
