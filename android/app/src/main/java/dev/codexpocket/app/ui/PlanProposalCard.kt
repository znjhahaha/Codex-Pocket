package dev.codexpocket.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexpocket.app.data.ProposedPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ripple-free controls shared by the proposal reader and confirmation dock. */
@Composable
internal fun planPress(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, pocketPressSpring(), label = "plan-press")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .5f }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

@Composable
internal fun PlanIconAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).then(planPress(onClick, enabled)), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Native text and identity remain intact; opening this reader never submits a command. */
@Composable
internal fun PlanProposalCard(plan: ProposedPlan, messageKey: String, onReopenPlan: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var readerOpen by rememberSaveable(messageKey, plan.revision) { mutableStateOf(false) }
    var readerPosition by rememberSaveable(messageKey, plan.revision) { mutableIntStateOf(0) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by remember(plan.revision) { mutableStateOf(false) }
    val renderer = checkNotNull(LocalMarkdownCache.current)
    // Hoisted so reopening never clamps a restored position to a short loading placeholder.
    val rendered by produceState<RenderedMarkdown?>(null, renderer, messageKey, plan.revision) {
        value = null
        value = renderer.render("$messageKey/proposal/${plan.revision}", plan.content)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val snapshot = pendingExport
        pendingExport = null
        if (uri != null && snapshot != null) scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                    ?: error("Document unavailable") }.isSuccess
            }
            Toast.makeText(context, if (success) "已保存 Markdown" else "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }
    val save: () -> Unit = { pendingExport = plan.markdownText; export.launch(plan.markdownFilename) }
    val copy: () -> Unit = { clipboard.setText(AnnotatedString(plan.content)); copied = true }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Surface(Modifier.fillMaxWidth().testTag("proposed-plan"), shape = RoundedCornerShape(18.dp), color = c.surface,
        border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = .65f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PocketIcons.Lightbulb, null, Modifier.size(19.dp), tint = c.onSurface)
                Text("计划", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                PlanIconAction(PocketIcons.ArrowDownToLine, "保存计划 Markdown", pendingExport == null, save)
                PlanIconAction(if (copied) PocketIcons.Check else PocketIcons.ContentCopy, "复制完整计划", onClick = copy)
                PlanIconAction(PocketIcons.OpenInFull, "展开完整计划") { readerOpen = true }
            }
            val limit = with(LocalDensity.current) { 240.dp.roundToPx() }
            var overflowing by remember(plan.revision) { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clipToBounds().testTag("plan-preview")) {
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { overflowing = it.height > limit }.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    PlanDocumentText(plan.content, rendered)
                }
                if (overflowing) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.surface))))
                Box(Modifier.matchParentSize().semantics { contentDescription = "阅读完整计划" }.then(planPress({ readerOpen = true })))
            }
            if (onReopenPlan != null) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-reopen")
                .then(planPress(onReopenPlan)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("实施此计划？", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(PocketIcons.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
    if (readerOpen) Dialog({ readerOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val scroll = rememberScrollState(readerPosition)
        // Restored dialogs wait for the complete document before tracking scroll changes.
        LaunchedEffect(scroll, rendered != null) {
            if (rendered != null) {
                withFrameNanos { }
                scroll.scrollTo(readerPosition)
                snapshotFlow { scroll.value }.collect { readerPosition = it }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("plan-reader"), color = c.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PocketIcons.Lightbulb, null, Modifier.size(19.dp))
                    Text("计划", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    PlanIconAction(PocketIcons.ArrowDownToLine, "保存计划 Markdown", pendingExport == null, save)
                    PlanIconAction(if (copied) PocketIcons.Check else PocketIcons.ContentCopy, "复制完整计划", onClick = copy)
                    PlanIconAction(PocketIcons.Close, "关闭计划阅读") { readerOpen = false }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp)) { PlanDocumentText(plan.content, rendered) }
            }
        }
    }
}

@Composable
private fun PlanDocumentText(source: String, rendered: RenderedMarkdown?) {
    val renderer = checkNotNull(LocalMarkdownCache.current)
    val c = MaterialTheme.colorScheme
    if (rendered == null) Text(markdownPreview(source), style = MaterialTheme.typography.bodyLarge)
    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rendered.parts.forEach { part ->
            if (part.table != null) MarkdownTable(part.table)
            else if (part.code != null) CodeBlock(part.code, part.language)
            else part.spans?.let { MarkdownSpans(it, renderer, c.onSurface.toArgb(), c.primary.toArgb(), tableColumns = part.tableColumns) }
        }
        rendered.images.forEach { RemoteTranscriptImage(it) }
    }
}
