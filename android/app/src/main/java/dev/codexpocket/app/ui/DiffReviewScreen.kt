package dev.codexpocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.R
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private data class ReviewContent(val files: List<DiffFile>, val fallback: String, val copyAll: String)

@Composable
internal fun DetailScreen(item: TimelineItem, onBack: () -> Unit, onSource: (String) -> Unit = {}, onComment: (String, Int, String) -> Unit = { _, _, _ -> }, onDraftComment: ((FileCommentDraft) -> Unit)? = null, canRead: Boolean = true,
    availableAgentIds: Set<String> = emptySet(), onAgent: (String) -> Unit = {}) {
    if (item.type == "collabAgentToolCall" || item.type == "subAgentActivity") {
        AgentDetailsScreen(item, onBack, availableAgentIds, onAgent)
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val review by produceState<ReviewContent?>(null, item) {
        value = withContext(Dispatchers.Default) {
            val files = if (ConversationPresentation.visible(item)) reviewActivityFiles(item) else emptyList()
            val fallback = ConversationPresentation.detail(item)
            ReviewContent(files, fallback, if (files.isEmpty()) fallback else files.joinToString("\n\n") { it.path + "\n" + it.diff })
        }
    }
    var selectedId by rememberSaveable(item.key) { mutableStateOf<String?>(null) }
    var contentScrolled by remember(item.key, selectedId) { mutableStateOf(false) }
    val readingPages = rememberSaveableStateHolder()
    val fileListState = rememberLazyListState()
    val fileListScrolled by remember { derivedStateOf { fileListState.firstVisibleItemIndex > 0 || fileListState.firstVisibleItemScrollOffset > 0 } }
    val data = review
    val files = data?.files.orEmpty()
    val countedFiles = remember(files) { files.filter { inlineDiffFallback(it.diff) == null } }
    val selected = if (files.size == 1) files.first() else files.firstOrNull { it.id == selectedId }
    val listing = files.size > 1 && selected == null
    fun back() { if (files.size > 1 && selected != null) selectedId = null else onBack() }
    BackHandler(files.size > 1 && selected != null) { selectedId = null }
    val (selectedName, selectedFolder) = remember(selected?.path) { selected?.path?.let(::splitPath) ?: ("" to "") }
    Scaffold(topBar = {
        ReviewHeader(
            title = when { listing -> context.getString(R.string.delivery_changed_files, files.size); selected != null -> selectedName.ifBlank { context.getString(R.string.unified_diff) }; else -> itemLabel(item.type) },
            backLabel = context.getString(if (files.size > 1 && selected != null) R.string.delivery_back_files else R.string.back_conversation),
            onBack = ::back,
            scrolled = if (listing) fileListScrolled else contentScrolled,
            subtitle = when {
                listing -> "+${countedFiles.sumOf { it.additions }}  −${countedFiles.sumOf { it.deletions }}"
                selected != null -> selectedFolder
                else -> null
            },
            badgePath = selected?.path?.takeIf(String::isNotBlank),
        ) {
            if (files.size > 1 && selected != null) ActionIcon(PocketIcons.FolderOpen, context.getString(R.string.delivery_back_files)) { selectedId = null }
            ActionIcon(PocketIcons.ContentCopy, context.getString(R.string.copy_full), enabled = data != null) { clipboard.setText(AnnotatedString(selected?.diff ?: data?.copyAll.orEmpty())) }
        }
    }) { padding ->
        when {
            data == null -> ReviewLoading(Modifier.fillMaxSize().padding(padding))
            listing -> LazyColumn(Modifier.fillMaxSize().padding(padding), state = fileListState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(files, key = { it.id }, contentType = { "file" }) { file ->
                    ChangedFileRow(file, file.kind.takeIf(String::isNotBlank)?.let(context::changeLabel),
                        file.path.ifBlank { context.getString(R.string.delivery_unnamed_file) }) { selectedId = file.id }
                }
            }
            else -> readingPages.SaveableStateProvider(selected?.id ?: item.key) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    DiffLines(selected?.diff ?: data.fallback, selected != null, Modifier.weight(1f).fillMaxWidth(), selected?.path, onComment, onScrolled = { contentScrolled = it }, onDraftComment = onDraftComment,
                        leading = selected?.let { file -> {
                            val (add, remove) = pocketDiffColors()
                            if (inlineDiffFallback(file.diff) == null) Text(androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = add)) { append("+${file.additions}") }
                                append("  ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = remove)) { append("−${file.deletions}") }
                            }, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.delivery_diff_counts, file.additions, file.deletions) })
                            ViewerToggle(stringResource(R.string.v02_open_source), PocketIcons.Code, false, { if (canRead && file.path.isNotBlank()) onSource(file.path) })
                        } })
                }
            }
        }
    }
}

@Composable
internal fun DiffLines(
    content: String,
    isDiff: Boolean,
    modifier: Modifier,
    path: String? = null,
    onComment: (String, Int, String) -> Unit = { _, _, _ -> },
    onScrolled: (Boolean) -> Unit = {},
    onDraftComment: ((FileCommentDraft) -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    readingState: DiffReadingState = rememberDiffReadingState(path ?: "review"),
    showControls: Boolean = true,
    commentsEnabled: Boolean = true,
) {
    val cached = readingState.document?.takeIf { it.content == content && it.diff == isDiff }
    val document by produceState(cached, content, isDiff, readingState) {
        value = cached
        if (cached == null) {
            value = withContext(Dispatchers.Default) {
                val lines = parseReviewLines(content, isDiff)
                val columns = lines.maxOfOrNull { row ->
                    reviewCode(row, isDiff).fold(0) { count, char -> count + when { char == '\t' -> 4; char.code >= 0x2e80 -> 2; else -> 1 } }
                } ?: 1
                val digits = (lines.maxOfOrNull { maxOf(it.oldLine ?: 0, it.newLine ?: 0) } ?: 0).toString().length
                DiffReadingDocument(content, isDiff, lines, columns, digits)
            }
            readingState.document = value
        }
    }
    val parsed = document?.lines
    var expanded by remember(readingState) { readingState::contexts }
    val rows = remember(parsed, expanded) { foldReviewLines(parsed.orEmpty(), expanded) }
    val hunks = remember(rows) { rows.indices.filter { rows[it].row.hunk } }
    // Highlight all code lines in one pass (stable token context), then slice per line.
    val renderer = LocalMarkdownCache.current
    val language = remember(path) { path?.let(::sourceLanguage) }
    val highlightKey = Triple(content, language, renderer)
    val cachedHighlight = readingState.highlighted.takeIf { readingState.highlightKey == highlightKey }
    val highlighted by produceState(cachedHighlight, parsed, language, renderer, readingState) {
        value = cachedHighlight
        if (cachedHighlight != null) return@produceState
        val lines = parsed ?: return@produceState
        if (renderer == null || language == null) return@produceState
        val joined = lines.joinToString("\n") { if (it.hunk) "" else reviewCode(it, isDiff) }
        val annotated = renderer.highlight(joined, language)
        if (annotated.text != joined) return@produceState
        var start = 0
        value = lines.map { line ->
            val length = if (line.hunk) 0 else reviewCode(line, isDiff).length
            annotated.subSequence(start, start + length).also { start += length + 1 }
        }
        readingState.highlightKey = highlightKey
        readingState.highlighted = value
    }
    val (addColor, removeColor) = pocketDiffColors()
    var target by remember(path, content) { mutableStateOf<ReviewLine?>(null) }
    var wrap by remember(readingState) { readingState::wrap }
    val state = readingState.list
    val horizontal = readingState.horizontal
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitWidth = remember(measurer, density) { with(density) { measurer.measure("0", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)).size.width.toDp() } }
    val numberWidth = remember(measurer, density) { with(density) { measurer.measure("0", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)).size.width.toDp() } }
    val columns = document?.columns ?: 1
    val binary = remember(content, isDiff) { isDiff && inlineDiffFallback(content) == InlineDiffFallback.Binary }
    val scope = rememberCoroutineScope()
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 } }
    val report by rememberUpdatedState(onScrolled)
    LaunchedEffect(scrolled) { report(scrolled) }
    val currentHunk by remember(hunks) { derivedStateOf { hunks.indexOfLast { it <= state.firstVisibleItemIndex } } }
    Column(modifier) {
        if (showControls) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading?.invoke(this)
            ViewerToggle("自动换行", PocketIcons.WrapText, wrap, { wrap = !wrap })
            if (expanded.isNotEmpty()) ViewerToggle("折叠上下文", PocketIcons.FoldVertical, false, { expanded = emptySet() })
        }
        if (showControls && commentsEnabled && path != null && !binary) Text("点按行号添加草稿评论 · 旧侧 / 新侧", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            binary -> DiffFallbackNotice(InlineDiffFallback.Binary, Modifier.fillMaxSize())
            parsed == null -> ReviewLoading(Modifier.fillMaxSize())
            content.isBlank() -> Text(stringResource(R.string.delivery_diff_empty), Modifier.padding(24.dp))
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxWidth
                val gutterWidth = (numberWidth * ((document?.numberDigits ?: 1) * if (isDiff) 2 else 1) + 28.dp)
                    .coerceAtLeast(if (isDiff) 92.dp else 56.dp)
                val codeViewport = (viewport - gutterWidth - if (isDiff) 14.dp else 0.dp).coerceAtLeast(1.dp)
                val codeWidth = (digitWidth * columns + 16.dp).coerceAtLeast(codeViewport)
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().testTag("diff-lines"), state = state, contentPadding = PaddingValues(bottom = if (showControls) 24.dp else 4.dp)) {
                        items(rows, key = { it.row.index }, contentType = { if (it.hiddenCount > 0) "fold" else "line" }) { visible ->
                            val row = visible.row
                            if (visible.hiddenCount > 0) {
                                PocketTextButton(onClick = { expanded = expanded + row.index }, modifier = Modifier.width(viewport)) { Text("展开 ${visible.hiddenCount} 行未改动上下文") }
                            } else {
                                val added = isDiff && row.newLine != null && row.oldLine == null
                                val removed = isDiff && row.oldLine != null && row.newLine == null
                                val background = when {
                                    added -> addColor.copy(alpha = .12f)
                                    removed -> removeColor.copy(alpha = .12f)
                                    else -> Color.Transparent
                                }
                                val number = row.newLine ?: row.oldLine
                                if (row.hunk) {
                                    // "@@ -12,6 +12,9 @@ fun load()" → range chip plus the enclosing function, as GitHub shows it.
                                    val context = row.text.substringAfter("@@", "").substringAfter("@@", "").trim()
                                    val range = row.text.removeSuffix(context).trim()
                                    Row(Modifier.width(viewport).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(range, Modifier.clip(PocketCapsule).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f))
                                            .padding(horizontal = 10.dp, vertical = 3.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                                        if (context.isNotBlank()) Text(context, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else Row(Modifier.fillMaxWidth().background(background)
                                    .drawBehind { if (added || removed) drawRect(if (added) addColor else removeColor, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                                    ) {
                                    Box(Modifier.width(gutterWidth).testTag("diff-gutter:${row.index}").heightIn(min = if (number != null) 28.dp else 24.dp)
                                        .clickable(enabled = commentsEnabled && !path.isNullOrBlank() && number != null && number > 0) { target = row }
                                        .padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.TopEnd) {
                                        Text(if (isDiff) "${row.oldLine ?: "·"}  ${row.newLine ?: "·"}" else number?.toString().orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.End,
                                            maxLines = 1, softWrap = false,
                                            color = when { added -> addColor; removed -> removeColor; else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f) })
                                    }
                                    if (isDiff) Text(if (added) "+" else if (removed) "−" else " ", Modifier.width(14.dp).padding(vertical = 3.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                        color = if (added) addColor else removeColor)
                                    val plain = reviewCode(row, isDiff)
                                    val code = highlighted?.getOrNull(row.index)?.takeIf { it.text == plain && plain.isNotEmpty() }
                                    // Only the code pane scrolls. Every visible row shares the same width/range;
                                    // the old/new coordinates and +/- column stay fixed at the viewport edge.
                                    Box(Modifier.weight(1f).clipToBounds().testTag("diff-code:${row.index}")
                                        .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))) {
                                        Text(code ?: AnnotatedString(plain.ifEmpty { " " }),
                                            Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier.width(codeWidth)).padding(top = 3.dp, bottom = 3.dp, end = 12.dp),
                                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = wrap)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Floating change navigator, like the next/previous-change control in a code review tool.
        androidx.compose.animation.AnimatedVisibility(showControls && hunks.size > 1, Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = .8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = .8f)) {
            ChangeNavigator(currentHunk.coerceAtLeast(0), hunks.size,
                { if (currentHunk > 0) scope.launch { state.animateScrollToItem(hunks[currentHunk - 1]) } },
                { if (currentHunk < hunks.lastIndex) scope.launch { state.animateScrollToItem(hunks[currentHunk + 1]) } })
        }
        }
    }
    target?.let { row ->
        val number = row.newLine ?: row.oldLine
        if (commentsEnabled && path != null && number != null) ReadingCommentDialog(path, number, if (row.newLine == null) ReviewSide.OLD else ReviewSide.NEW, { target = null }) { deliverReadingDraft(it, onDraftComment, onComment) }
    }
}

/** GitHub "Files changed" row: type badge, bold name over its folder, +/− and the five-square bar. */
@Composable
private fun ChangedFileRow(file: DiffFile, kind: String?, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (add, remove) = pocketDiffColors()
    val (name, folder) = remember(label) { splitPath(label) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .98f else 1f, pocketPressSpring(), label = "file-row")
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(16.dp))
        .background(if (pressed) c.surfaceContainerHigh else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FileBadge(file.path)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(kind, folder.takeIf(String::isNotBlank)).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (inlineDiffFallback(file.diff) == InlineDiffFallback.Binary) Text("二进制", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            else {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = add)) { append("+${file.additions}") }
                append(" ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = remove)) { append("−${file.deletions}") }
            }, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            DiffStatBar(file.additions, file.deletions)
            }
        }
        Icon(PocketIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
    }
}

/** Code without the unified-diff marker column; metadata lines stay verbatim. */
internal fun reviewCode(row: ReviewLine, isDiff: Boolean): String =
    if (isDiff && (row.oldLine != null || row.newLine != null) && row.text.firstOrNull() in setOf('+', '-', ' ')) row.text.substring(1) else row.text

/** Compatibility helper used by existing callers/tests; deletions have no new-side line. */
internal fun diffNewLineNumbers(diff: String): List<Int?> = parseReviewLines(diff, true).map { it.newLine }

@Composable
internal fun ReviewHeader(title: String, backLabel: String, onBack: () -> Unit, scrolled: Boolean, subtitle: String? = null, badgePath: String? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    val edge by animateColorAsState(if (scrolled) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, tween(pocketMotionMillis(180)), label = "review-scroll-edge")
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .drawBehind { drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(PocketIcons.ArrowBack, backLabel, onClick = onBack)
            if (badgePath != null) Box(Modifier.padding(start = 2.dp, end = 8.dp)) { FileBadge(badgePath, 32.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, maxLines = if (subtitle.isNullOrBlank()) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

@Composable
internal fun ReviewLoading(modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) { index ->
            Box(Modifier.fillMaxWidth(if (index % 2 == 0) .84f else .62f).height(12.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp)))
        }
    }
}
