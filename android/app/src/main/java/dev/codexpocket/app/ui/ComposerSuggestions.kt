package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

/** Inline glass surface: no popup/window, focus requester, editable search field or keyboard dismissal. */
@Composable
internal fun ComposerSuggestions(
    trigger: ComposerTrigger,
    state: ComposerSuggestionsState,
    maxHeight: Dp,
    onSelect: (ComposerSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (maxHeight < 96.dp) return
    val sections = remember(trigger, state) { composerSuggestionGroups(trigger, state) }
    val current = state.trigger == trigger
    val searching = state.online && (!current || state.loading)
    val status = when {
        !state.online -> "连接 Codex 后可用"
        searching -> "正在查找引用…"
        state.error != null -> state.error.ifBlank { "读取引用失败" }
        sections.isEmpty() -> if (trigger.kind == ComposerTriggerKind.Command) "没有匹配的命令或技能" else "没有匹配的引用"
        else -> null
    }
    val unsupported = if (state.online && current && !state.loading && state.error == null) {
        listOfNotNull("技能".takeIf { "skills/list" in state.unsupported },
            "插件引用".takeIf { "plugin/list" in state.unsupported && trigger.kind == ComposerTriggerKind.Reference })
    } else emptyList()
    PocketGlassSurface(modifier.fillMaxWidth().heightIn(max = minOf(320.dp, maxHeight))
        .testTag("composer-suggestions"), shape = RoundedCornerShape(20.dp), dense = true) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (trigger.kind == ComposerTriggerKind.Command) PocketIcons.Command else PocketIcons.AlternateEmail,
                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(if (trigger.kind == ComposerTriggerKind.Command) "命令与技能" else "引用",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.size(48.dp).focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = "关闭建议" },
                    contentAlignment = Alignment.Center) {
                    Icon(PocketIcons.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("composer-suggestion-list"),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                sections.forEach { section ->
                    item(key = "heading:${section.group.name}", contentType = "heading") {
                        Text(if (section.group == ComposerSuggestionGroup.Plugins) "应用与插件" else section.group.label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(section.candidates, key = { it.key }, contentType = { it.group }) { candidate ->
                        ComposerSuggestionRow(candidate) { onSelect(candidate) }
                    }
                }
                if (status != null) item(key = "status", contentType = "status") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (unsupported.isNotEmpty()) item(key = "unsupported", contentType = "status") {
                    Text("当前 Codex 未提供${unsupported.joinToString("、")}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionRow(candidate: ComposerSuggestion, onClick: () -> Unit) {
    val icon = when (candidate.group) {
        ComposerSuggestionGroup.Commands -> PocketIcons.Command
        ComposerSuggestionGroup.Skills -> PocketIcons.Cube
        ComposerSuggestionGroup.Files -> if (candidate.reference?.string("type") == "directory") PocketIcons.Folder else PocketIcons.Description
        ComposerSuggestionGroup.Plugins -> PocketIcons.Link
    }
    val title = candidate.reference?.let(::referenceDisplayName) ?: candidate.title
    val source = candidate.reference?.let { value ->
        listOf("source", "pluginName", "scope").firstNotNullOfOrNull { value.string(it).takeIf(String::isNotBlank) }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).focusProperties { canFocus = false }
        .clickable(role = Role.Button, onClick = onClick).testTag("composer-suggestion-${candidate.key}")
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (source != null) Text(source, Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(candidate.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Preserve unaffected IME composition; accepting a suggestion commits the replaced composing token. */
internal fun replaceComposerEditor(editor: TextFieldValue, trigger: ComposerTrigger, token: String): TextFieldValue? {
    if (!editor.selection.collapsed || editor.selection.start != trigger.cursor) return null
    // Commands only remove their trigger; references use exactly the same spacing/caret rule as the parent.
    if (composerTrigger(editor.text, editor.selection.start, editor.selection.end) != trigger) return null
    val replacement = if (token.isEmpty()) replaceComposerTrigger(editor.text, trigger, token) else {
        replaceReferenceAt(editor.text, trigger.draft, trigger.range.start, trigger.range.end, token)
            ?.let { ComposerReplacement(it.text, it.caret) }
    } ?: return null
    val delta = replacement.text.length - editor.text.length
    val composition = editor.composition?.let { range ->
        when {
            range.max <= trigger.range.start -> range
            range.min >= trigger.range.end -> TextRange(range.start + delta, range.end + delta)
            else -> null
        }
    }
    return editor.copy(text = replacement.text, selection = TextRange(replacement.cursor), composition = composition)
}
