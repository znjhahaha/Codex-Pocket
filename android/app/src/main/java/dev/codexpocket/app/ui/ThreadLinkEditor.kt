package dev.codexpocket.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import dev.codexpocket.app.data.*

internal data class EditorThreadLink(val link: ThreadTextLink, val title: String)

@Composable
internal fun rememberThreadTitles(): (ThreadTextLink) -> String {
    val binding = LocalThreadLinkBinding.current
    val state = binding?.repository?.state?.collectAsState()?.value
    val chats = state?.let { it.chats + it.agentChats }.orEmpty()
    return remember(binding?.origin, chats) { { threadTextTitle(it, binding?.origin, chats) } }
}

/** Only the displayed glyphs change. Drafts, clipboard selections and wire text keep the URI. */
internal class ThreadLinkVisualTransformation(
    private val base: ReferenceVisualTransformation,
    private val links: List<EditorThreadLink>,
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = base.filter(text)
        data class Replacement(val start: Int, val end: Int, val title: String)
        val changes = links.map { Replacement(original.offsetMapping.originalToTransformed(it.link.start),
            // End before an adjacent reference's icon, whose start offset maps after its icon.
            original.offsetMapping.originalToTransformed(it.link.end - 1) + 1, it.title) }
        val output = buildAnnotatedString {
            var cursor = 0
            changes.forEach { change ->
                append(original.text.subSequence(cursor, change.start))
                withStyle(SpanStyle(color = color)) { append('\u2003'); append(change.title) }
                cursor = change.end
            }
            append(original.text.subSequence(cursor, original.text.length))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val position = original.offsetMapping.originalToTransformed(offset)
                var delta = 0
                for (change in changes) {
                    if (position < change.start) break
                    if (position < change.end) return change.start + delta + 1 +
                        ((position - change.start).toLong() * change.title.length / (change.end - change.start)).toInt()
                    delta += 1 + change.title.length - (change.end - change.start)
                }
                return position + delta
            }
            override fun transformedToOriginal(offset: Int): Int {
                var delta = 0
                for (change in changes) {
                    val start = change.start + delta
                    val length = 1 + change.title.length
                    if (offset < start) break
                    if (offset < start + length) return original.offsetMapping.transformedToOriginal(
                        if ((offset - start - 1).coerceAtLeast(0) * 2 < change.title.length) change.start else change.end)
                    delta += length - (change.end - change.start)
                }
                return original.offsetMapping.transformedToOriginal(offset - delta)
            }
        }
        return TransformedText(output, mapping)
    }
}

/** A title is one editable token: backspace/delete removes its complete address. */
internal fun editThreadLinkToken(before: TextFieldValue, after: TextFieldValue, links: List<EditorThreadLink>): TextFieldValue {
    if (before.composition != null || after.composition != null || !before.selection.collapsed || !after.selection.collapsed ||
        before.text.length - after.text.length != 1) return after
    val edit = composerReferenceEdit(before, after) ?: return after
    val token = links.firstOrNull { edit.start >= it.link.start && edit.endExclusive <= it.link.end }?.link ?: return after
    return TextFieldValue(before.text.removeRange(token.start, token.end), TextRange(token.start))
}
