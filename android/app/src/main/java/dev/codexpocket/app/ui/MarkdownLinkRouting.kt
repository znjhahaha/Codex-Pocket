package dev.codexpocket.app.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.codexpocket.app.data.PocketRepository
import dev.codexpocket.app.data.ThreadDeepLink
import dev.codexpocket.app.data.ThreadKey

/** Immutable message source; never derive a profile from the URI itself. */
internal data class ThreadLinkBinding(val repository: PocketRepository, val origin: ThreadKey?)
internal val LocalThreadLinkBinding = staticCompositionLocalOf<ThreadLinkBinding?> { null }

@Composable
internal fun rememberMarkdownLinkHandler(fallback: ((String) -> Unit)?): ((String) -> Unit)? {
    val context = LocalContext.current
    val binding = LocalThreadLinkBinding.current
    return remember(context, binding, fallback) {
        { href: String ->
            when {
                ThreadDeepLink.isCodexUri(href) && binding != null -> binding.repository.openThreadLink(href, binding.origin)
                ThreadDeepLink.isCodexUri(href) -> Toast.makeText(context,
                    if (ThreadDeepLink.parse(href) == null) "会话链接格式无效" else "当前页面尚未接入会话导航，请从会话页打开", Toast.LENGTH_LONG).show()
                fallback != null -> fallback(href)
                href.startsWith("https://", true) || href.startsWith("http://", true) ->
                    runCatching { io.noties.markwon.LinkResolverDef().resolve(android.widget.TextView(context), href) }
                else -> Unit
            }
            Unit
        }
    }
}
