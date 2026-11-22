package dev.codexpocket.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.*

/** Padding and rhythm shared by the assistant's pages, so each starts and ends where the others do. */
internal val AssistantPagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp)
internal val AssistantPageSpacing = 12.dp

/** A quiet line for a page that has nothing to list yet. */
@Composable internal fun AssistantPageNote(text: String, modifier: Modifier = Modifier) {
    PText(text, modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = Pocket.type.body, color = Pocket.colors.inkMuted)
}

/** A small heading inside a page: what the lines under it are. */
@Composable internal fun AssistantPageHeading(text: String, modifier: Modifier = Modifier) {
    PText(text, modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() }, style = Pocket.type.labelSmall, color = Pocket.colors.inkMuted)
}

/** Details that open under their heading. Also used by the conversation's answered-question row. */
@Composable
internal fun AssistantExpansion(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(expanded,
        enter = fadeIn(tween(pocketMotionMillis(180))) + expandVertically(tween(pocketMotionMillis(180))),
        exit = fadeOut(tween(pocketMotionMillis(140))) + shrinkVertically(tween(pocketMotionMillis(140)))) { content() }
}

/**
 * One value out of a few. The options open in place under the field, so the choice works the same on a page,
 * inside a dialog and in a list, and a long catalogue scrolls with whatever holds it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun AssistantChoice(label: String, selected: String, tag: String, choices: List<Pair<String, String>>, enabled: Boolean = true,
    onChoose: (String) -> Unit) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    var expanded by remember { mutableStateOf(false) }
    val open = expanded && enabled
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        PText(label, Modifier.padding(horizontal = 4.dp), style = Pocket.type.labelSmall, color = c.inkMuted)
        Column(Modifier.fillMaxWidth().clip(PocketRadii.field).background(c.sunken).border(1.dp, c.line.copy(alpha = .5f), PocketRadii.field)
            .animateContentSize(PocketMotion.resize<IntSize>().orSnap(motion))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
                .pocketPressable(enabled, Role.Button, pressedScale = .99f, onClickLabel = if (open) "收起选项" else "展开选项") { expanded = !open }
                .testTag(tag).semantics { stateDescription = if (open) "已展开" else "已收起" }.padding(horizontal = 15.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PText(selected, Modifier.weight(1f), style = Pocket.type.body, color = if (enabled) c.ink else c.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                PToggleGlyph(MotionGlyphs.Chevron, open, Modifier.size(18.dp), c.inkFaint)
            }
            if (open) {
                PDivider(color = c.line.copy(alpha = .5f))
                // Opened near the bottom of a list or a dialog, the options are scrolled into view.
                val reveal = remember { BringIntoViewRequester() }
                LaunchedEffect(Unit) { reveal.bringIntoView() }
                Column(Modifier.padding(4.dp).bringIntoViewRequester(reveal)) {
                    choices.forEach { (id, text) -> key(id) { PRow(text, { expanded = false; onChoose(id) }, Modifier.testTag("$tag:$id"), selected = text == selected) } }
                }
            }
        }
    }
}

/** A switch with its label; the whole row reads as one setting. */
@Composable internal fun AssistantSettingToggle(text: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PText(text, Modifier.weight(1f), style = Pocket.type.body, color = if (enabled) Pocket.colors.ink else Pocket.colors.inkMuted)
        PSwitch(checked, onChange, Modifier.testTag(tag), enabled)
    }
}

/** A centred decision whose buttons the page composes, so each keeps its own tag and enabled state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, dismiss: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    PocketModal(title, onDismiss, sheet = false) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PText(title, Modifier.semantics { heading() }, style = Pocket.type.titleLarge)
            if (content != null) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismiss?.invoke(); confirm()
            }
        }
    }
}

@Composable internal fun AssistantDeleteDialog(title: String, dismiss: () -> Unit, enabled: Boolean, confirm: () -> Unit) {
    AssistantDialog(title, dismiss, confirm = { PButton("确认删除", confirm, Modifier.testTag("assistant-confirm-delete"), PButtonKind.Danger, enabled, haptic = PocketHaptic.Reject) },
        dismiss = { AssistantQuietButton("取消", dismiss, haptic = null) }) {
        PText("确认后交给电脑处理，收到回执后列表才会更新。", style = Pocket.type.body, color = Pocket.colors.inkMuted)
    }
}

/**
 * The second action beside a primary one. The kit's secondary fill equals a card's surface in the dark appearance,
 * so this one carries a fill that reads on both the page and a card.
 */
@Composable internal fun AssistantQuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    haptic: PocketHaptic? = PocketHaptic.Tap) {
    PButton(text, onClick, modifier.background(Pocket.colors.fill, PocketRadii.capsule), PButtonKind.Ghost, enabled, haptic = haptic)
}
