package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class ThreadDeepLinkTest {
    private val id = "01a1c7fb-7bda-7a71-a782-a020229c9573"
    private val owner = ThreadKey("pc", "store", "01a1c7fb-7bda-7a71-a782-a020229c9574")
    private val target = ChatEntity(owner.copy(nativeId = id), "runtime", "cli", "Target", "", "D:/work", 0, "idle", false)
    private val runtime = RuntimeInfo("runtime", "cli", "fixture", "store", true, setOf(id), setOf("thread/read"))

    @Test fun copyUsesNativeIdWithoutComputerOrProfileEncoding() {
        assertEquals("codex://threads/$id", ThreadDeepLink.copyUrl(target))
        assertEquals("codex://threads/$id", ThreadDeepLink.copyUrl(target.copy(isSubagent = true, parentThreadId = owner.nativeId)))
        assertNull(ThreadDeepLink.copyUrl(null))
        assertNull(ThreadDeepLink.copyUrl(target.copy(key = target.key.copy(nativeId = ""))))
        assertNull(ThreadDeepLink.copyUrl(target.copy(key = target.key.copy(nativeId = "../rpc"))))
    }

    @Test fun parserRejectsAuthorityPathAndRpcInjection() {
        assertEquals(id, ThreadDeepLink.parse("CODEX://THREADS/$id")?.nativeId)
        listOf("https://threads/$id", "codex://other/$id", "codex://threads@evil/$id", "codex://threads:80/$id",
            "codex://threads/$id/", "codex://threads/%30${id.drop(1)}", "codex://threads/../$id",
            "codex://threads/$id?method=turn/start", "codex://threads/$id#fragment", " codex://threads/$id",
            "codex://threads/", "codex://threads/not-a-native-id", "codex://threads/$id\n").forEach {
            assertNull(it, ThreadDeepLink.parse(it))
        }
    }

    private fun resolve(chats: List<ChatEntity> = listOf(target), runtimes: List<RuntimeInfo> = listOf(runtime),
        origin: ThreadKey? = owner, computer: String = "pc") = resolveThreadDeepLink(id, computer, origin, chats, runtimes)

    @Test fun selectsExactOriginAndPreservesChildSourceIdentity() {
        val child = target.copy(isSubagent = true, parentThreadId = owner.nativeId, relationSource = "native")
        val other = target.copy(key = target.key.copy(configStoreId = "other"), runtimeId = "other")
        val result = resolve(listOf(other, child)) as ThreadLinkResolution.Open
        assertEquals(child, result.chat)
    }

    @Test fun ambiguousOrMismatchedProfilesNeverChooseFirstMatch() {
        val other = target.copy(key = target.key.copy(configStoreId = "other"), runtimeId = "other")
        assertTrue(resolve(listOf(target, other), origin = null) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(listOf(other)) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(origin = owner.copy(computerId = "different")) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(computer = "different") is ThreadLinkResolution.Rejected)
    }

    @Test fun conflictsOfflineOwnersAndUnknownTargetsHaveClearFailures() {
        assertTrue(resolve(listOf(target.copy(conflict = true))) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(runtimes = listOf(runtime.copy(online = false))) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(runtimes = listOf(runtime.copy(configStoreId = "other"))) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(runtimes = listOf(runtime.copy(capabilities = emptySet()))) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(emptyList(), listOf(runtime.copy(loadedThreadIds = emptySet()))) is ThreadLinkResolution.Rejected)
        assertTrue(resolve(runtimes = listOf(runtime, runtime.copy(id = "duplicate"))) is ThreadLinkResolution.Rejected)
    }

    @Test fun loadedNativeIdCanBeLocatedWithoutInventingAnotherProfile() {
        val result = resolve(emptyList()) as ThreadLinkResolution.Open
        assertEquals(target.key, result.chat.key)
        assertEquals("runtime", result.chat.runtimeId)
        assertEquals("cli", result.chat.source)
    }
}
