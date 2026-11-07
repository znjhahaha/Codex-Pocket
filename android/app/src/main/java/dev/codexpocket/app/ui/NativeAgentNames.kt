package dev.codexpocket.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.codexpocket.app.data.ActivityAgent

/** Real names learned by the parent/reader; never derive nicknames from native identities. */
internal val LocalNativeAgentNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
internal val LocalNativeAgentReader = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun nativeAgentDisplayName(agent: ActivityAgent, names: Map<String, String>): String =
    nativeAgentDisplayName(agent.threadId, agent.name, names)

internal fun nativeAgentDisplayName(id: String, recordedName: String, names: Map<String, String>): String {
    val value = names[id]?.trim()?.takeIf(String::isNotBlank) ?: recordedName.trim()
    return value.takeUnless { it == id || it.contains('/') || it.contains('\\') || nativeIdentity.matches(it) }.orEmpty()
}

private val nativeIdentity = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
