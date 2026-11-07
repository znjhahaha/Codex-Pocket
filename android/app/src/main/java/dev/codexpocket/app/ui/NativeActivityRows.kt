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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.unit.sp
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
    val itemStates = rememberSaveableStateHolder()
    val groupScroll = rememberScrollState()
    val largeGroupScroll = rememberLazyListState()
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
            val confirmed = row.items.all { NativeActivityPresentation.state(it) == ActivityStatus.Completed }
            val label = summary?.let { traceLabel(it, confirmed) } ?: stringResource(R.string.v03_trace)
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
            if (row.items.size > 20) LazyColumn(Modifier.fillMaxWidth().height(360.dp)
                .testTag("activity-group-scroll:${row.key}").padding(start = if (folded) 4.dp else 0.dp), state = largeGroupScroll) {
                items(row.items, key = { it.key }, contentType = { it.type }) { item ->
                    NativeToolRow(item, scope, row.completedTurn, item.key in openItems, {
                        if (onExpansionKey != null) onExpansionKey("activity-item:${item.key}")
                        else expandedItems = if (item.key in expandedItems) expandedItems - item.key else expandedItems + item.key
                    }, onDetail)
                }
            } else Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(groupScroll)
                .testTag("activity-group-scroll:${row.key}").padding(start = if (folded) 4.dp else 0.dp)) {
                row.items.forEach { item -> key(item.key) { itemStates.SaveableStateProvider(item.key) {
                    NativeToolRow(item, scope, row.completedTurn, item.key in openItems, {
                        if (onExpansionKey != null) onExpansionKey("activity-item:${item.key}")
                        else expandedItems = if (item.key in expandedItems) expandedItems - item.key else expandedItems + item.key
                    }, onDetail)
                } } }
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
        Row(modifier.fillMaxWidth().heightIn(min = 32.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (failed) Icon(PocketIcons.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            else if (icon != null) TraceGlyph(icon)
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = tone.text,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            ActivityExpansionIcon(open)
        }
    }
}

/** Group glyph: the leading kind of work, the way Codex prefixes a folded group. */
internal fun summaryIcon(s: ActivitySummary) = when {
    s.files > 0 -> PocketIcons.Edit
    s.skills > 0 -> PocketIcons.Wrench
    s.reads > 0 -> PocketIcons.BookOpen
    s.searches > 0 -> PocketIcons.Search
    s.lists > 0 -> PocketIcons.Folder
    s.commands > 0 -> PocketIcons.Terminal
    s.web > 0 -> PocketIcons.Globe
    s.images > 0 -> PocketIcons.Image
    s.agents > 0 -> PocketIcons.RobotOutline
    s.tools > 0 -> PocketIcons.Wrench
    else -> PocketIcons.Sparkle
}

@Composable
private fun traceLabel(summary: ActivitySummary, confirmed: Boolean): String {
    val onlyEditCommands = summary.commands > 0 && summary.files > 0 && summary.failed == 0 &&
        summary.tools + summary.agents + summary.web + summary.images + summary.reads + summary.searches + summary.lists + summary.skills == 0
    if (onlyEditCommands) return if (confirmed) "编辑了文件运行了命令" else "文件修改与命令执行"
    return listOfNotNull(
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
}

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
    var selectedFile by rememberSaveable(scope, item.key) { mutableStateOf<String?>(null) }
    val fileStates = rememberSaveableStateHolder()
    Column(Modifier.fillMaxWidth().testTag("native-activity:${item.key}")) {
        val files = remember(item, value?.files) { reviewActivityFiles(item, value?.files.orEmpty()) }
        if (value?.kind == ActivityKind.Files && files.isNotEmpty()) {
            // A file opens its own patch, never the entire multi-file native item.
            files.take(if (expanded) files.size else VISIBLE_FILES).forEach { file -> key(file.id) {
                val fileKey = file.id + ":" + file.path
                val truncated = item.raw.flag("pocketOutputTruncated") || item.raw.flag("diffTruncated")
                FileLine(file, state, selectedFile == fileKey, truncated) {
                    selectedFile = if (selectedFile == fileKey) null else fileKey
                    // Completion can regroup this run; retain the explicit open choice above it.
                    if (selectedFile != null && !expanded) onExpand()
                }
                if (selectedFile == fileKey) fileStates.SaveableStateProvider(fileKey) {
                    InlineDiffPreview(file, Modifier.padding(start = 4.dp, bottom = 4.dp),
                        stateKey = "$scope/${item.key}/$fileKey", truncated = truncated)
                }
            } }
            if (!expanded && files.size > VISIBLE_FILES) TraceLine(onClick = onExpand) {
                Text(stringResource(R.string.v04_trace_more, files.size - VISIBLE_FILES), style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = toneText())
            }
            return@Column
        }
        if (value?.kind == ActivityKind.Commentary && value.output.text.isNotBlank()) {
            Box(Modifier.padding(vertical = 4.dp)) { MarkdownText(value.output.text, "$scope/${item.key}/commentary") }
            return@Column
        }
        if (value?.kind == ActivityKind.Reasoning) {
            // A task's expanded process retains the native paragraph boundaries without a thought card.
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item.summaryParts.filter(String::isNotBlank).forEachIndexed { index, paragraph ->
                    NativeSummaryProse(paragraph, "$scope/${item.key}/reasoning:$index")
                }
            }
            return@Column
        }
        val command = value?.command
        if (command != null && command.explored) {
            // Codex shows a parsed read/search/list as what it did, one line per action, never as the shell call.
            command.steps.forEachIndexed { index, step -> key(index) {
                val (verb, subject) = stepSentence(step, state)
                TraceLine(onClick = onExpand, active = expanded, modifier = Modifier.testTag(if (index == 0) "activity-toggle:${item.key}" else "activity-step:${item.key}:$index")
                    .semantics { stateDescription = "$status · $expansionState" }) {
                    StatusGlyph(running, stepIcon(step.kind))
                    Text(verb, Modifier.then(if (subject.isBlank()) Modifier.weight(1f, fill = false) else Modifier),
                        style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = toneText(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (subject.isNotBlank()) Text(subject, Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = toneSubject())
                    if (state == ActivityStatus.Failed) Text(command.exitCode?.let { stringResource(R.string.v03_exit_code, it) } ?: status,
                        maxLines = 1, softWrap = false, fontSize = 14.sp, style = MaterialTheme.typography.labelSmall, color = c.error)
                    ActivityExpansionIcon(expanded)
                }
            } }
            AnimatedVisibility(expanded,
                enter = fadeIn(tween(pocketMotionMillis(160))) + expandVertically(tween(pocketMotionMillis(220), easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                exit = fadeOut(tween(pocketMotionMillis(100))) + shrinkVertically(tween(pocketMotionMillis(180), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
                ActivityDetail(item, value, scope, state) { onDetail(item) }
            }
            return@Column
        }
        val (verb, subject) = traceSentence(item, value, state)
        // A finished thought with no recorded text has nothing to open; Codex shows it as a plain label.
        val empty = value?.kind == ActivityKind.Reasoning && value.output.text.isBlank() && !running
        val image = value?.kind == ActivityKind.Image
        TraceLine(onClick = onExpand, active = expanded, enabled = !empty,
            modifier = Modifier.testTag("activity-toggle:${item.key}").semantics { stateDescription = "$status · $expansionState" }) {
            // The robot glyph remains recognisable even while its native call is running.
            if (value?.kind == ActivityKind.Agents) TraceGlyph(PocketIcons.RobotOutline)
            else StatusGlyph(running, kindIcon(value?.kind, value?.webAction))
            Text(verb, Modifier.then(if (subject.isBlank()) Modifier.weight(1f, fill = false) else Modifier),
                style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = toneText(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (subject.isNotBlank()) Text(subject, Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = toneSubject())
            if (state == ActivityStatus.Failed && value?.kind != ActivityKind.Agents) Text(value?.command?.exitCode?.let { stringResource(R.string.v03_exit_code, it) } ?: status,
                maxLines = 1, softWrap = false, fontSize = 14.sp, style = MaterialTheme.typography.labelSmall, color = c.error)
            else if (state == ActivityStatus.Interrupted) Text(status, maxLines = 1, softWrap = false, fontSize = 14.sp, style = MaterialTheme.typography.labelSmall, color = toneText())
            if (!empty && (value?.kind != ActivityKind.Agents || expanded)) ActivityExpansionIcon(expanded)
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
    Icon(icon, null, Modifier.size(16.dp), tint = toneText().copy(alpha = .85f))
}

/** The breathing dot of a running step morphs into its kind glyph when it finishes (scale + fade on a spring). */
@Composable
private fun StatusGlyph(running: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.animation.AnimatedContent(running, Modifier.size(16.dp), contentAlignment = Alignment.Center, transitionSpec = {
        (fadeIn(tween(pocketMotionMillis(160))) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = .55f, stiffness = 700f), initialScale = .4f)) togetherWith
            (fadeOut(tween(pocketMotionMillis(100))) + androidx.compose.animation.scaleOut(tween(pocketMotionMillis(120)), targetScale = .4f))
    }, label = "trace-status") { busy -> if (busy) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { RunningDot() } else TraceGlyph(icon) }
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
    ActivityKind.Files -> PocketIcons.Edit
    ActivityKind.Tool -> PocketIcons.Wrench
    ActivityKind.Web -> if (web == WebAction.Search) PocketIcons.Globe else PocketIcons.Link
    ActivityKind.Agents -> PocketIcons.RobotOutline
    ActivityKind.Reasoning -> PocketIcons.Sparkle
    ActivityKind.Image -> PocketIcons.Image
    else -> PocketIcons.Wrench
}

/** Codex names a skill by its folder; short ones read as acronyms ("pdf" → "PDF"). */
internal fun skillLabel(name: String) = if (name.length <= 4 && name.all { it.isLetter() }) name.uppercase() else name

@Composable
private fun stepSentence(step: CommandStep, state: ActivityStatus): Pair<String, String> {
    val running = state == ActivityStatus.Running || state == ActivityStatus.Waiting
    if (state !in setOf(ActivityStatus.Completed, ActivityStatus.Running, ActivityStatus.Waiting)) return when (step.kind) {
        StepKind.Read -> "读取" to step.name.ifBlank { compactFileName(step.path) }
        StepKind.Skill -> "技能" to skillLabel(step.name)
        StepKind.Search -> "搜索" to listOf(step.query, compactFileName(step.path)).filter(String::isNotBlank).joinToString(" · ").ifBlank { step.command }
        StepKind.ListFiles -> "列出目录" to compactFileName(step.path).ifBlank { step.command }
        StepKind.Unknown -> "命令" to compactCommandLabel(step.command)
    }
    return when (step.kind) {
    StepKind.Skill -> stringResource(if (running) R.string.v05_trace_skill_loading else R.string.v05_trace_skill, skillLabel(step.name)) to ""
    StepKind.Read -> stringResource(if (running) R.string.v05_trace_reading else R.string.v05_trace_read) to step.name.ifBlank { compactFileName(step.path) }
    StepKind.Search -> stringResource(if (running) R.string.v05_trace_searching else R.string.v05_trace_searched) to
        listOf(step.query, compactFileName(step.path)).filter(String::isNotBlank).joinToString(" · ").ifBlank { step.command }
    StepKind.ListFiles -> stringResource(if (running) R.string.v05_trace_listing else R.string.v05_trace_listed) to
        compactFileName(step.path).ifBlank { step.path.ifBlank { step.command } }
    StepKind.Unknown -> (if (running) "执行中" else "执行") to compactCommandLabel(step.command)
    }
}

/** One gray sentence: verb + subject. Running items use present tense. */
@Composable
private fun traceSentence(item: TimelineItem, value: PresentedActivity?, state: ActivityStatus): Pair<String, String> {
    if (value == null) return itemLabel(item.type) to ""
    val running = state == ActivityStatus.Running || state == ActivityStatus.Waiting
    return when (value.kind) {
        ActivityKind.Command -> {
            commandActionLabel(state) to compactCommandLabel(value.command?.command ?: value.title)
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
        ActivityKind.Agents -> NativeActivityPresentation.agentCallLabel(value) to ""
        ActivityKind.Reasoning -> "摘要" to ""
        ActivityKind.Tool -> stringResource(if (running) R.string.v04_trace_calling else R.string.v04_trace_called) to
            listOf(value.subtitle, value.title).filter(String::isNotBlank).joinToString(" · ").ifBlank { itemLabel(item.type) }
        else -> itemLabel(item.type) to value.title
    }
}

internal fun commandActionLabel(state: ActivityStatus): String = when (state) {
    ActivityStatus.Running -> "正在运行"
    ActivityStatus.Completed -> "已运行"
    ActivityStatus.Waiting -> "等待运行"
    ActivityStatus.Failed -> "运行失败"
    ActivityStatus.Interrupted -> "已中断"
    ActivityStatus.Unknown -> "命令"
}

/** One strip below currently executing content, fed by the parent's authoritative current roster. */
internal fun activeAgentWorkSummary(agents: List<AgentRosterEntry>, names: Map<String, String>): String {
    val active = agents.filter { it.isCurrent && it.status == ActivityStatus.Running }.distinctBy { it.threadId }
    if (active.isEmpty()) return ""
    val visibleNames = active.map { nativeAgentDisplayName(it.threadId, it.name, names) }.filter(String::isNotBlank).take(2)
    if (visibleNames.isEmpty()) return "${active.size} 个智能体正在工作"
    val others = active.size - visibleNames.size
    return visibleNames.joinToString("、") + (if (others > 0) "和另外${others}个子智能体" else "") + "开始工作"
}

@Composable
internal fun NativeAgentWorkingStrip(agents: List<AgentRosterEntry>, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null) {
    val names = LocalNativeAgentNames.current
    val active = agents.filter { it.isCurrent && it.status == ActivityStatus.Running }.distinctBy { it.threadId }
    val label = activeAgentWorkSummary(active, names)
    if (label.isBlank()) return
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("native-agents-working")
        .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(active.first().threadId) } else Modifier)
        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val colors = listOf(Color(0xFF6C9EE8), Color(0xFF63B99B), Color(0xFF89CBB7))
        val glyphs = listOf(PocketIcons.AgentFlower, PocketIcons.AgentOrbit, PocketIcons.AgentWave)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            active.take(3).forEachIndexed { index, agent -> key(agent.threadId) {
                Box(Modifier.size(17.dp).background(colors[index].copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(glyphs[index], null, Modifier.size(16.dp), tint = colors[index])
                }
            } }
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** A trace sentence. No background or scale on press: the words darken, as on Codex desktop. */
@Composable
private fun TraceLine(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tone = traceTone(enabled && (pressed || active))
    CompositionLocalProvider(LocalTraceTone provides tone) {
        Row(modifier.fillMaxWidth().heightIn(min = 32.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun FileLine(file: ActivityFile, state: ActivityStatus, expanded: Boolean, truncated: Boolean, onOpen: () -> Unit) {
    val (add, remove) = pocketDiffColors()
    val c = MaterialTheme.colorScheme
    val name = compactFileName(file.path).ifBlank { stringResource(R.string.v03_file_unknown) }
    TraceLine(onClick = onOpen, active = expanded, modifier = Modifier.testTag("activity-file:${file.id}")
        .semantics { contentDescription = file.path; stateDescription = if (expanded) "已展开" else "已折叠" }) {
        StatusGlyph(state == ActivityStatus.Running, PocketIcons.Edit)
        Text(fileActionLabel(file.kind, state), style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp,
            color = if (state == ActivityStatus.Failed) c.error else toneText(), maxLines = 1, softWrap = false)
        Text(name, Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline), fontSize = 14.sp, color = c.onSurface)
        val fallback = remember(file.diff, truncated) { inlineDiffFallback(file.diff, truncated) }
        if (fallback != null) Text(when (fallback) {
            InlineDiffFallback.Binary -> "二进制"
            InlineDiffFallback.Truncated -> "差异已截断"
            InlineDiffFallback.Empty -> "无差异"
        }, fontSize = 14.sp, maxLines = 1, softWrap = false, color = toneSubject())
        else Text(buildAnnotatedString {
            withStyle(SpanStyle(color = add)) { append("+${file.additions}") }
            append("  ")
            withStyle(SpanStyle(color = remove)) { append("−${file.deletions}") }
        }, style = MaterialTheme.typography.labelMedium, fontSize = 14.sp, maxLines = 1, softWrap = false)
        ActivityExpansionIcon(expanded)
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
    val openAgent = LocalNativeAgentReader.current
    val c = MaterialTheme.colorScheme
    val names = LocalNativeAgentNames.current
    Column(Modifier.fillMaxWidth().padding(start = 26.dp, top = 2.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (value.kind == ActivityKind.Reasoning) {
            item.summaryParts.filter(String::isNotBlank).forEachIndexed { index, part -> NativeSummaryProse(part, "$scope/${item.key}/reasoning:$index") }
            return@Column
        }
        value.agents.forEachIndexed { index, agent -> key(agent.threadId) {
            val name = nativeAgentDisplayName(agent, names).ifBlank { "智能体 ${index + 1}" }
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { if (openAgent != null) openAgent(agent.threadId) else onDetail() }
                .testTag("agent-summary:${agent.threadId}").padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(PocketIcons.RobotOutline, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activityStatusLabel(agent.status), style = MaterialTheme.typography.labelSmall, color = if (agent.status == ActivityStatus.Failed) c.error else c.onSurfaceVariant)
                    Icon(PocketIcons.ChevronRight, "查看智能体详情", Modifier.size(14.dp), tint = c.onSurfaceVariant)
                }
                if (agent.message.text.isNotBlank()) SelectionContainer {
                    Text(agent.message.text, Modifier.padding(start = 23.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (agent.status == ActivityStatus.Failed) c.error else c.onSurfaceVariant)
                }
            }
        } }
        val command = value.command
        // Keep the original invocation in details so launchers, quoting and arguments remain inspectable.
        val shownCommand = command?.command?.trim().orEmpty()
        val showCommand = command != null && shownCommand.isNotBlank()
        val hasOutput = value.output.text.isNotBlank()
        if (showCommand || (command?.cwd?.isNotBlank() == true) || hasOutput) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showCommand) SelectionContainer {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = c.onSurfaceVariant)) { append("$ ") }
                            append(shownCommand)
                        }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                    }
                    if (command != null && command.cwd.isNotBlank()) Text(stringResource(R.string.v04_transcript_cwd, command.cwd),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    if (command != null) {
                        val result = listOfNotNull(command.exitCode?.let { stringResource(R.string.v03_exit_code, it) },
                            command.durationMs?.let { NativeActivityPresentation.duration(it) }).joinToString(" · ")
                        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                    if (value.command == null && value.subtitle.isNotBlank() && value.kind != ActivityKind.Web) Text(value.subtitle,
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (hasOutput) {
                        if (value.kind == ActivityKind.Agents) Text(if (value.title == "spawnAgent") "任务" else "消息",
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        if (showCommand || command?.cwd?.isNotBlank() == true) HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
                        Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(value.output.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                                    fontFamily = if (value.kind == ActivityKind.Command) FontFamily.Monospace else FontFamily.Default)
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
