package dev.codexpocket.app.data

import java.net.URI

/** The desktop URI contains only a native ID. Pairing/profile authority comes from the reader. */
data class ThreadDeepLink(val nativeId: String) {
    val url: String get() = "codex://threads/$nativeId"

    companion object {
        private val nativeIdPattern = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        fun parse(value: String): ThreadDeepLink? {
            if (value.length > 80 || value.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("codex", true) || !uri.host.equals("threads", true) ||
                !uri.rawAuthority.equals("threads", true) || uri.rawQuery != null || uri.rawFragment != null) return null
            val id = uri.rawPath?.removePrefix("/") ?: return null
            if (uri.rawPath != "/$id" || !nativeIdPattern.matches(id)) return null
            return ThreadDeepLink(id)
        }
        fun isCodexUri(value: String): Boolean = value.substringBefore(':').equals("codex", true)
        fun copyUrl(chat: ChatEntity?): String? = chat?.let { parse("codex://threads/${it.key.nativeId}")?.url }
    }
}

sealed interface ThreadLinkResolution {
    data class Open(val chat: ChatEntity) : ThreadLinkResolution
    data class Rejected(val message: String, val missing: Boolean = false) : ThreadLinkResolution
}

/** Never pick a native ID from another computer or fall back to another configuration profile. */
fun resolveThreadDeepLink(nativeId: String, computerId: String, origin: ThreadKey?, chats: List<ChatEntity>,
    runtimes: List<RuntimeInfo>): ThreadLinkResolution {
    fun reject(message: String, missing: Boolean = false) = ThreadLinkResolution.Rejected(message, missing)
    if (computerId.isBlank() || origin?.computerId?.let { it != computerId } == true)
        return reject("此链接的来源电脑与当前配对不一致，请切回原电脑")
    val known = chats.filter { it.key.computerId == computerId && it.key.nativeId == nativeId }
    val loaded = runtimes.filter { nativeId in it.loadedThreadIds && it.online }
    val stores = (known.map { it.key.configStoreId } + loaded.map { it.configStoreId }).distinct()
    val store = origin?.configStoreId ?: stores.singleOrNull()
    if (store.isNullOrBlank()) return reject(if (stores.isEmpty()) "当前电脑未找到此会话，链接也可能来自其他电脑" else "此会话 ID 有多个配置档案归属，请从原会话打开", stores.isEmpty())
    if (known.isNotEmpty() && known.none { it.key.configStoreId == store })
        return reject("此会话属于其他配置档案，请从原会话打开")
    val candidates = known.filter { it.key.configStoreId == store }.distinctBy { it.key to it.runtimeId }
    val owners = loaded.filter { it.configStoreId == store }
    if (candidates.size > 1 || owners.size > 1 || candidates.any { it.conflict })
        return reject("此会话的运行入口归属冲突，请在电脑端确认后重试")
    val chat = candidates.singleOrNull()
    val runtime = if (chat != null) runtimes.firstOrNull { it.id == chat.runtimeId && it.configStoreId == store && it.source == chat.source }
        else owners.singleOrNull()
    if (chat == null && runtime == null) return reject("当前电脑未找到此会话，链接也可能来自其他电脑", true)
    if (runtime == null || !runtime.online) return reject("此会话的原运行入口离线，请在当前电脑恢复后重试")
    if (owners.singleOrNull()?.id?.let { it != runtime.id } == true)
        return reject("此会话已由其他运行入口持有，请刷新会话列表后重试")
    val capabilities = runtime.capabilities
    if (capabilities != null && "thread/read" !in capabilities && "native:thread/read" !in capabilities)
        return reject("此会话的运行入口不支持读取，请更新电脑助手")
    return ThreadLinkResolution.Open(chat ?: ChatEntity(ThreadKey(computerId, store, nativeId), runtime.id,
        runtime.source, "会话", "", "", 0, "", false))
}
