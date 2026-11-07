package dev.codexpocket.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val VISIBLE_FILES = 6

@Composable
internal fun NativeActivityTrace(row: TranscriptRow, scope: String, expanded: Boolean, onExpand: () -> Unit, onDetail: (TimelineItem) -> Unit,
    expansionKeys: Set<String>? = null, onExpansionKey: ((String) -> Unit)? = null) {
    // Store item choices above the conditional subtree so folding and completion retain them.
    var expandedItems by rememberSaveable(scope, row.key) { mutableStateOf(emptyList<String>()) }
    var manualGroupExpanded by rememberSaveable(scope, row.key) { mutableStateOf<Boolean?>(null) }
    // Draft-backed native item keys survive regrouping when a tool fails or asks for approval.
    val openItems = expansionKeys?.filter { it.startsWith("activity-item:") }?.map { it.removePrefix("activity-item:") } ?: expandedItems
    val collapsedKey = "activity-collapsed:${row.key}"
    val manual = if (expansionKeys == null) manualGroupExpanded else when {
        collapsedKey in expansionKeys -> false
        expanded -> true
        else -> null
    }
    // Codex folds only runs of several items; a lone read or command shows as its own sentence.
    val single = row.items.size == 1
    val folded = !single && NativeActivityPresentation.foldedByDefault(row)
    val showItems = single || NativeActivityPresentation.groupExpanded(row, expanded, manual, openItems)
    Column(Modifier.fillMaxWidth().testTag("activity-group")) {
        if (folded) {
            val summary by produceState<ActivitySummary?>(null, row.items) {
                value = withContext(Dispatchers.Default) { NativeActivityPresentation.summary(row.items) }
            }
            val label = summary?.let { traceLabel(it) } ?: stringResource(R.string.v03_trace)
            val expansionState = stringResource(if (showItems) R.string.v03_expanded else R.string.v03_collapsed)
            TraceHeader(label, summary?.let(::summaryIcon), (summary?.failed ?: 0) > 0, showItems,
                Modifier.testTag("activity-group-toggle:${row.key}").semantics { stateDescription = expansionState }) {
                val next = !showItems
                manualGroupExpanded = next
                if (expanded != next) onExpand()
                if (expansionKeys != null && (collapsedKey in expansionKeys) == next) onExpansionKey?.invoke(collapsedKey)
            }
        }
        // One structure for folded and live groups: completion never remounts rows or restarts their parsing.
        // Live content is already visible, so only a manual fold/unfold animates.
        AnimatedVisibility(showItems,
            enter = fadeIn(tween(pocketMotionMillis(180))) + expandVertically(tween(pocketMotionMillis(240), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(pocketMotionMillis(120))) + shrinkVertically(tween(pocketMotionMillis(200), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
            Column(Modifier.fillMaxWidth().padding(start = if (folded) 4.dp else 0.dp)) {
                row.items.forEach { item -> key(item.key) {
                    NativeToolRow(item, scope, row.completedTurn, item.key in openItems, {
                        if (onExpansionKey != null) onExpansionKey("activity-item:${item.key}")
                        else expandedItems = if (item.key in expandedItems) expandedItems - item.key else expandedItems + item.key
                    }, onDetail)
                } }
            }
        }
    }
}

/**
 * Codex never draws a container behind a trace row: pressing or opening it only darkens the gray prose
 * to the body color. [LocalTraceTone] carries that animated color to every glyph and word in the row.
 */
private data class TraceTone(val text: Color, val subject: Color)
private val LocalTraceTone = compositionLocalOf<TraceTone?> { null }

@Composable
private fun traceTone(active: Boolean): TraceTone {
    val c = MaterialTheme.colorScheme
    val spec = tween<Color>(pocketMotionMillis(140), easing = FastOutSlowInEasing)
    val text by androidx.compose.animation.animateColorAsState(if (active) c.onSurface else c.onSurfaceVariant, spec, label = "trace-tone")
    val subject by androidx.compose.animation.animateColorAsState(if (active) c.onSurface.copy(alpha = .78f) else c.onSurfaceVariant.copy(alpha = .62f), spec, label = "trace-subject")
    return TraceTone(text, subject)
}

@Composable
private fun toneText() = LocalTraceTone.current?.text ?: MaterialTheme.colorScheme.onSurfaceVariant
@Composable
private fun toneSubject() = LocalTraceTone.current?.subject ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .62f)

@Composable
private fun TraceHeader(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, failed: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tone = traceTone(pressed || open)
    CompositionLocalProvider(LocalTraceTone provides tone) {
        Row(modifier.heightIn(min = 40.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (failed) Icon(PocketIcons.ErrorOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
            else if (icon != null) TraceGlyph(icon)
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = tone.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ActivityExpansionIcon(open)
        }
    }
}

/** Group glyph: the leading kind of work, the way Codex prefixes a folded group. */
internal fun summaryIcon(s: ActivitySummary) = when {
    s.skills > 0 -> PocketIcons.Wrench
    s.reads > 0 -> PocketIcons.BookOpen
    s.searches > 0 -> PocketIcons.Search
    s.lists > 0 -> PocketIcons.Folder
    s.commands > 0 -> PocketIcons.Terminal
    s.files > 0 -> PocketIcons.FilePen
    s.web > 0 -> PocketIcons.Globe
    s.images > 0 -> PocketIcons.Image
    s.agents > 0 -> PocketIcons.Users
    s.tools > 0 -> PocketIcons.Wrench
    else -> PocketIcons.Sparkle
}

@Composable
private fun traceLabel(summary: ActivitySummary): String = listOfNotNull(
    summary.reasoning.takeIf { it > 0 && summary.commands + summary.files + summary.tools + summary.agents + summary.web + summary.images +
        summary.reads + summary.searches + summary.lists + summary.skills == 0 }
        ?.let { stringResource(R.string.v04_trace_thought) },
    summary.skills.takeIf { it > 0 }?.let { stringResource(R.string.v05_trace_sum_skills, it) },
    summary.reads.takeIf { it > 0 }?.let { stringResource(R.string.v05_trace_sum_reads, it) },
    summary.searches.takeIf { it > 0 }?.let { stringResource(R.string.v05_trace_sum_searches, it) },
    summary.lists.takeIf { it > 0 }?.let { stringResource(R.string.v05_trace_sum_lists, it) },
    summary.commands.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_commands, it) },
    summary.files.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_files, it) },
    summary.web.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_web) },
    summary.images.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_images, it) },
    summary.tools.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_tools, it) },
    summary.agents.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_agents, it) },
    summary.failed.takeIf { it > 0 }?.let { stringResource(R.string.v04_trace_sum_failed, it) },
).joinToString("，").ifBlank { stringResource(R.string.v03_trace) }

@Composable
internal fun pocketDiffColors(): Pair<Color, Color> = if (MaterialTheme.colorScheme.background.luminance() < .5f)
    Color(0xFF3FB950) to Color(0xFFF85149) else Color(0xFF1A7F37) to Color(0xFFCF222E)

@Composable
private fun NativeToolRow(item: TimelineItem, scope: String, completedTurn: Boolean, expanded: Boolean, onExpand: () -> Unit, onDetail: (TimelineItem) -> Unit) {
    // A superseded revision cannot publish its result; diff/result parsing stays off the UI thread.
    val descriptor by produceState<PresentedActivity?>(null, item, completedTurn) {
        value = withContext(Dispatchers.Default) { NativeActivityPresentation.describe(item, completedTurn) }
    }
    val value = descriptor
    val state = NativeActivityPresentation.state(item, completedTurn)
    val status = activityStatusLabel(state)
    val expansionState = stringResource(if (expanded) R.string.v03_expanded else R.string.v03_collapsed)
    val running = state == ActivityStatus.Running || state == ActivityStatus.Waiting
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("native-activity:${item.key}")) {
        val files = value?.files.orEmpty()
        if (value?.kind == ActivityKind.Files && files.isNotEmpty()) {
            // Codex shows every edited file as its own sentence; the name opens the diff.
            files.take(if (expanded) files.size else VISIBLE_FILES).forEach { file -> key(file.id) { FileLine(file, running) { onDetail(item) } } }
            if (!expanded && files.size > VISIBLE_FILES) TraceLine(onClick = onExpand) {
                Text(stringResource(R.string.v04_trace_more, files.size - VISIBLE_FILES), style = MaterialTheme.typography.bodyMedium, color = toneText())
            }
            return@Column
        }
        if (value?.kind == ActivityKind.Commentary && value.output.text.isNotBlank()) {
            Box(Modifier.padding(vertical = 4.dp)) { MarkdownText(value.output.text, "$scope/${item.key}/commentary") }
            return@Column
        }
        val command = value?.command
        if (command != null && command.explored) {
            // Codex shows a parsed read/search/list as what it did, one line per action, never as the shell call.
            command.steps.forEachIndexed { index, step -> key(index) {
                val (verb, subject) = stepSentence(step, running)
                TraceLine(onClick = onExpand, active = expanded, modifier = Modifier.testTag(if (index == 0) "activity-toggle:${item.key}" else "activity-step:${item.key}:$index")
                    .semantics { stateDescription = "$status · $expansionState" }) {
                    StatusGlyph(running, stepIcon(step.kind))
                    Text(verb, style = MaterialTheme.typography.bodyMedium, color = toneText(), maxLines = 1)
                    if (subject.isNotBlank()) Text(subject, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = toneSubject())
                    if (state == ActivityStatus.Failed) Text(command.exitCode?.let { stringResource(R.string.v03_exit_code, it) } ?: status,
                        maxLines = 1, style = MaterialTheme.typography.labelSmall, color = c.error)
                }
            } }
            AnimatedVisibility(expanded,
                enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
                ActivityDetail(item, value, scope, state) { onDetail(item) }
            }
            return@Column
        }
        val (verb, subject) = traceSentence(item, value, running)
        // A finished thought with no recorded text has nothing to open; Codex shows it as a plain label.
        val empty = value?.kind == ActivityKind.Reasoning && value.output.text.isBlank() && !running
        val image = value?.kind == ActivityKind.Image
        TraceLine(onClick = onExpand, active = expanded, enabled = !empty,
            modifier = Modifier.testTag("activity-toggle:${item.key}").semantics { stateDescription = "$status · $expansionState" }) {
            StatusGlyph(running, kindIcon(value?.kind, value?.webAction))
            Text(verb, style = MaterialTheme.typography.bodyMedium, color = toneText(), maxLines = 1)
            if (subject.isNotBlank()) Text(subject, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (value?.kind == ActivityKind.Command) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                color = toneSubject())
            if (state == ActivityStatus.Failed) Text(value?.command?.exitCode?.let { stringResource(R.string.v03_exit_code, it) } ?: status,
                maxLines = 1, style = MaterialTheme.typography.labelSmall, color = c.error)
            else if (state == ActivityStatus.Interrupted) Text(status, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = toneText())
            if (!empty) ActivityExpansionIcon(expanded)
        }
        if (image && value != null) AnimatedVisibility(expanded,
            enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
            // Viewed and generated images open as the picture itself, never as an empty text result.
            val path = item.raw.string("path", item.raw.string("savedPath"))
            if (path.isNotBlank()) ImageGrid(listOf(MentionedFile(name = compactFileName(path), path = path, image = true)), alignEnd = false,
                modifier = Modifier.padding(start = 26.dp, top = 2.dp, bottom = 8.dp))
            else ActivityDetail(item, value, scope, state) { onDetail(item) }
        }
        else AnimatedVisibility(expanded && value != null,
            enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
            value?.let { ActivityDetail(item, it, scope, state) { onDetail(item) } }
        }
    }
}

@Composable
private fun TraceGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, null, Modifier.size(15.dp), tint = toneText().copy(alpha = .85f))
}

/** The breathing dot of a running step morphs into its kind glyph when it finishes (scale + fade on a spring). */
@Composable
private fun StatusGlyph(running: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.animation.AnimatedContent(running, Modifier.size(15.dp), contentAlignment = Alignment.Center, transitionSpec = {
        (fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = .55f, stiffness = 700f), initialScale = .4f)) togetherWith
            (fadeOut(tween(pocketMotionMillis(100))) + androidx.compose.animation.scaleOut(tween(pocketMotionMillis(120)), targetScale = .4f))
    }, label = "trace-status") { busy -> if (busy) Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) { RunningDot() } else TraceGlyph(icon) }
}

internal fun stepIcon(kind: StepKind) = when (kind) {
    StepKind.Read -> PocketIcons.BookOpen
    StepKind.Skill -> PocketIcons.Wrench
    StepKind.Search -> PocketIcons.Search
    StepKind.ListFiles -> PocketIcons.Folder
    StepKind.Unknown -> PocketIcons.Terminal
}

internal fun kindIcon(kind: ActivityKind?, web: WebAction? = null) = when (kind) {
    ActivityKind.Command -> PocketIcons.Terminal
    ActivityKind.Files -> PocketIcons.FilePen
    ActivityKind.Tool -> PocketIcons.Wrench
    ActivityKind.Web -> if (web == WebAction.Search) PocketIcons.Globe else PocketIcons.Link
    ActivityKind.Agents -> PocketIcons.Users
    ActivityKind.Reasoning -> PocketIcons.Sparkle
    ActivityKind.Image -> PocketIcons.Image
    else -> PocketIcons.Wrench
}

/** Codex names a skill by its folder; short ones read as acronyms ("pdf" → "PDF"). */
internal fun skillLabel(name: String) = if (name.length <= 4 && name.all { it.isLetter() }) name.uppercase() else name

@Composable
private fun stepSentence(step: CommandStep, running: Boolean): Pair<String, String> = when (step.kind) {
    StepKind.Skill -> stringResource(if (running) R.string.v05_trace_skill_loading else R.string.v05_trace_skill, skillLabel(step.name)) to ""
    StepKind.Read -> stringResource(if (running) R.string.v05_trace_reading else R.string.v05_trace_read) to step.name.ifBlank { compactFileName(step.path) }
    StepKind.Search -> stringResource(if (running) R.string.v05_trace_searching else R.string.v05_trace_searched) to
        listOf(step.query, compactFileName(step.path)).filter(String::isNotBlank).joinToString(" · ").ifBlank { step.command }
    StepKind.ListFiles -> stringResource(if (running) R.string.v05_trace_listing else R.string.v05_trace_listed) to
        compactFileName(step.path).ifBlank { step.path.ifBlank { step.command } }
    StepKind.Unknown -> stringResource(if (running) R.string.v04_trace_running else R.string.v04_trace_ran) to step.command
}

/** One gray sentence: verb + subject. Running items use present tense. */
@Composable
private fun traceSentence(item: TimelineItem, value: PresentedActivity?, running: Boolean): Pair<String, String> {
    if (value == null) return itemLabel(item.type) to ""
    return when (value.kind) {
        ActivityKind.Command -> {
            val command = value.command
            val verb = when {
                running -> stringResource(R.string.v04_trace_running)
                command?.durationMs != null && command.durationMs >= 1_000 -> stringResource(R.string.v04_trace_ran_in, NativeActivityPresentation.duration(command.durationMs))
                else -> stringResource(R.string.v04_trace_ran)
            }
            verb to NativeActivityPresentation.headline(command?.display ?: value.title)
        }
        ActivityKind.Files -> stringResource(if (running) R.string.v04_trace_editing else R.string.v04_trace_edited) to ""
        ActivityKind.Web -> stringResource(when (value.webAction) {
            WebAction.Search -> R.string.v04_trace_searched
            WebAction.Open -> R.string.v04_trace_opened
            WebAction.Find -> R.string.v04_trace_found
            WebAction.Other -> R.string.v03_activity_web
        }) to value.subtitle
        ActivityKind.Image -> stringResource(if (item.type == "imageGeneration") R.string.v04_trace_generated_image else R.string.v04_trace_viewed_image) to
            compactFileName(value.subtitle)
        ActivityKind.Agents -> agentActionLabel(value.title) to value.agents.joinToString("、") { it.name.ifBlank { it.threadId.take(8) } }
        ActivityKind.Reasoning -> stringResource(if (running) R.string.v04_trace_thinking else R.string.v04_trace_thought) to ""
        ActivityKind.Tool -> stringResource(if (running) R.string.v04_trace_calling else R.string.v04_trace_called) to
            listOf(value.subtitle, value.title).filter(String::isNotBlank).joinToString(" · ").ifBlank { itemLabel(item.type) }
        else -> itemLabel(item.type) to value.title
    }
}

/** A trace sentence. No background or scale on press: the words darken, as on Codex desktop. */
@Composable
private fun TraceLine(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tone = traceTone(enabled && (pressed || active))
    CompositionLocalProvider(LocalTraceTone provides tone) {
        Row(modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun FileLine(file: ActivityFile, running: Boolean, onOpen: () -> Unit) {
    val (add, remove) = pocketDiffColors()
    val c = MaterialTheme.colorScheme
    val name = compactFileName(file.path).ifBlank { stringResource(R.string.v03_file_unknown) }
    TraceLine(onClick = onOpen, modifier = Modifier.semantics { contentDescription = file.path }) {
        StatusGlyph(running, PocketIcons.FilePen)
        Text(stringResource(if (running) R.string.v04_trace_editing else R.string.v04_trace_edited), style = MaterialTheme.typography.bodyMedium, color = toneText(), maxLines = 1)
        Text(name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline), color = c.onSurface)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = add)) { append("+${file.additions}") }
            append("  ")
            withStyle(SpanStyle(color = remove)) { append("−${file.deletions}") }
        }, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Soft breathing dot instead of a spinner; stays still when animations are off. */
@Composable
private fun RunningDot() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val alpha = if (pocketMotionMillis(1) == 0) .7f else {
        val transition = rememberInfiniteTransition(label = "trace-running")
        transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "trace-running-alpha").value
    }
    Box(Modifier.size(6.dp).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
}

@Composable
private fun ActivityDetail(item: TimelineItem, value: PresentedActivity, scope: String, state: ActivityStatus, onDetail: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (value.kind == ActivityKind.Reasoning) {
            if (value.output.text.isNotBlank()) Box(Modifier.padding(start = 8.dp)) { MarkdownText(value.output.text, "$scope/${item.key}/reasoning") }
            else Text(stringResource(R.string.v03_output_waiting), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            return@Column
        }
        value.agents.forEach { agent -> key(agent.threadId) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onDetail)
                .testTag("agent-summary:${agent.threadId}").padding(horizontal = 8.dp, vertical = 6.dp)) {
                // A native thread ID is an honest fallback when no name was recorded.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(PocketIcons.Users, null, Modifier.size(17.dp), tint = c.onSurfaceVariant)
                    Text(agent.name.ifBlank { agent.threadId.take(12) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activityStatusLabel(agent.status), style = MaterialTheme.typography.labelSmall, color = if (agent.status == ActivityStatus.Failed) c.error else c.onSurfaceVariant)
                    Icon(PocketIcons.ChevronRight, "查看子任务详情", Modifier.size(16.dp), tint = c.onSurfaceVariant)
                }
                if (agent.message.text.isNotBlank()) Text(agent.message.text, Modifier.padding(start = 25.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } }
        val command = value.command
        // The header line is one truncated line; the detail shows the whole readable script, never the shell launcher.
        val shownCommand = command?.display?.trim().orEmpty().ifBlank { command?.command?.trim().orEmpty() }
        val showCommand = command != null && shownCommand.isNotBlank()
        val hasOutput = value.output.text.isNotBlank()
        if (showCommand || (command?.cwd?.isNotBlank() == true) || hasOutput) {
            Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showCommand) SelectionContainer {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = c.onSurfaceVariant)) { append("$ ") }
                            append(shownCommand)
                        }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                    }
                    if (command != null && command.cwd.isNotBlank()) Text(stringResource(R.string.v04_transcript_cwd, command.cwd),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    if (value.command == null && value.subtitle.isNotBlank() && value.kind != ActivityKind.Web) Text(value.subtitle,
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (hasOutput) {
                        if (showCommand || command?.cwd?.isNotBlank() == true) HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
                        Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(value.output.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.onSurface,
                                    fontFamily = if (value.kind == ActivityKind.Command) FontFamily.Monospace else FontFamily.Default)
                            }
                        }
                    }
                }
            }
        } else if (value.files.isEmpty() && value.agents.isEmpty()) Text(
            stringResource(if (state == ActivityStatus.Running) R.string.v03_output_waiting else R.string.v03_output_unavailable),
            Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        if (value.output.truncated) Text(stringResource(R.string.v03_output_truncated), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        PocketTextButton(onClick = onDetail, modifier = Modifier.align(Alignment.End), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.full_details), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ActivityExpansionIcon(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(pocketMotionMillis(200), easing = FastOutSlowInEasing), label = "activity-expansion")
    Icon(PocketIcons.ExpandMore, stringResource(if (expanded) R.string.collapse_details else R.string.expand_details), Modifier.size(16.dp).rotate(rotation),
        tint = toneText().copy(alpha = .8f))
}

@Composable
internal fun activityStatusLabel(status: ActivityStatus): String = stringResource(when (status) {
    ActivityStatus.Running -> R.string.state_in_progress
    ActivityStatus.Completed -> R.string.state_completed
    ActivityStatus.Failed -> R.string.state_failed
    ActivityStatus.Waiting -> R.string.state_waiting
    ActivityStatus.Interrupted -> R.string.state_interrupted
    ActivityStatus.Unknown -> R.string.state_unknown
})

@Composable
private fun agentActionLabel(action: String): String = when (action) {
    "spawnAgent", "started" -> stringResource(R.string.v03_agent_spawn)
    "sendInput", "sendMessage", "interacted" -> stringResource(R.string.v03_agent_message)
    "resumeAgent" -> stringResource(R.string.v03_agent_resume)
    "wait" -> stringResource(R.string.v03_agent_wait)
    "closeAgent" -> stringResource(R.string.v03_agent_close)
    "interruptAgent", "interrupted" -> stringResource(R.string.v03_agent_interrupt)
    "followupTask" -> stringResource(R.string.v03_agent_followup)
    "listAgents" -> stringResource(R.string.v03_agent_list)
    "completed" -> stringResource(R.string.v03_agent_complete)
    else -> action.ifBlank { stringResource(R.string.v03_activity_agents) }
}
