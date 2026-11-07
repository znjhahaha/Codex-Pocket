package dev.codexpocket.app.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

@Composable
internal fun SourceScreen(
    source: SourcePreview, onBack: () -> Unit,
    onComment: (String, Int, String) -> Unit = { _, _, _ -> },
    onOpen: (String) -> Unit = {}, onDraftComment: ((FileCommentDraft) -> Unit)? = null,
    canRead: Boolean = true, initialLine: Int? = null,
) {
    // Standalone use does not require a transcript provider; state keys contain only file identity.
    ProvideMarkdownRendering {
        val clipboard = LocalClipboardManager.current
        var scrolled by remember(source.path) { mutableStateOf(false) }
        val markdown = sourceLanguage(source.path) == "markdown"
        var preview by rememberSaveable(source.path) { mutableStateOf(markdown) }
        var progress by remember(source.path) { mutableStateOf(0f) }
        val (name, folder) = remember(source.path) { splitPath(source.path) }
        Scaffold(topBar = { Column {
            ReviewHeader(name, "返回", onBack, scrolled, subtitle = folder, badgePath = source.path) {
                ActionIcon(PocketIcons.ContentCopy, if (source.truncated) "复制已加载内容" else "复制源码") { clipboard.setText(AnnotatedString(source.text)) }
            }
            ReadingProgress(progress)
        } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (source.truncated) Notice("文件超过后端 256 KiB 读取上限，当前仅为开头片段。搜索、复制和预览仅包含已加载内容。")
                if (markdown) PocketSegmented(listOf("预览", "源码"), if (preview) 0 else 1, { preview = it == 0 },
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                // Switching mode cross-fades with a slight lift instead of snapping.
                androidx.compose.animation.AnimatedContent(markdown && preview, Modifier.weight(1f), label = "source-mode", transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(pocketMotionMillis(200))) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(pocketMotionMillis(240))) { it / 24 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(pocketMotionMillis(120)))
                }) { showPreview ->
                    if (showPreview) MarkdownFilePreview(source, Modifier.fillMaxSize(), onOpen, canRead, onScrolled = { scrolled = it }, onProgress = { progress = it })
                    else SourceCodeLines(source, Modifier.fillMaxSize(), initialLine, { deliverReadingDraft(it, onDraftComment, onComment) }, { scrolled = it }, onProgress = { progress = it })
                }
            }
        }
    }
}

@Composable
private fun SourceCodeLines(source: SourcePreview, modifier: Modifier, initialLine: Int?, onDraft: (FileCommentDraft) -> Unit, onScrolled: (Boolean) -> Unit,
    onProgress: (Float) -> Unit = {}) {
    val renderer = checkNotNull(LocalMarkdownCache.current)
    val index by produceState<SourceLineIndex?>(null, source.path, source.text) { value = withContext(Dispatchers.Default) { SourceLineIndex(source.text) } }
    val highlighted by produceState(AnnotatedString(""), renderer, source.path, source.text) { value = renderer.highlight(source.text, sourceLanguage(source.path)) }
    val data = index?.takeIf { it.source == source.text }
    val highlightReady = remember(highlighted, source.text) { highlighted.text == source.text }
    var query by rememberSaveable(source.path) { mutableStateOf("") }
    var search by rememberSaveable(source.path) { mutableStateOf(false) }
    var jump by rememberSaveable(source.path) { mutableStateOf("") }
    var wrap by rememberSaveable(source.path) { mutableStateOf(false) }
    var commentLine by remember(source.path, source.text) { mutableStateOf<Int?>(null) }
    val matches by produceState(emptyList<Int>(), data, query) { value = withContext(Dispatchers.Default) { data?.matchingLines(query).orEmpty() } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val horizontal = rememberScrollState()
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 } }
    val report by rememberUpdatedState(onScrolled)
    LaunchedEffect(scrolled) { report(scrolled) }
    LaunchedEffect(source.path, data, initialLine) { if (data != null) state.scrollToItem(((initialLine ?: 1) - 1).coerceIn(0, data.size - 1)) }
    val progress by rememberUpdatedState(onProgress)
    LaunchedEffect(state, data) {
        snapshotFlow { state.layoutInfo.let { info -> if (info.totalItemsCount <= 1) 0f else (info.visibleItemsInfo.lastOrNull()?.index ?: 0) / (info.totalItemsCount - 1f) } }
            .collect { progress(it) }
    }
    fun go(line: Int) { scope.launch { state.animateScrollToItem(line) } }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewerToggle("自动换行", PocketIcons.WrapText, wrap, { wrap = !wrap })
            ViewerToggle("搜索", PocketIcons.Search, search, { search = !search }, description = "搜索已加载源码")
            Text(data?.let { "${it.size} 行 · ${sourceLanguage(source.path) ?: "纯文本"}" } ?: "正在准备源码", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.animation.AnimatedVisibility(search) { Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PocketTextField(query, { query = it }, Modifier.weight(1f), placeholder = "搜索文字", singleLine = true)
                ActionIcon(PocketIcons.ArrowUpward, "上一个匹配行", matches.isNotEmpty()) { go(matches.lastOrNull { it < state.firstVisibleItemIndex } ?: matches.last()) }
                ActionIcon(PocketIcons.ArrowDownward, "下一个匹配行", matches.isNotEmpty()) { go(matches.firstOrNull { it > state.firstVisibleItemIndex } ?: matches.first()) }
            }
            Text("${matches.size} 个匹配行 · 仅搜索已加载内容", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PocketTextField(jump, { jump = it.filter(Char::isDigit).take(9) }, Modifier.weight(1f), placeholder = "跳转到行", singleLine = true)
                PocketTextButton(onClick = { data?.jumpIndex(jump)?.let(::go) }, enabled = data?.jumpIndex(jump) != null) { Text("跳转") }
            }
        } }
        if (data == null) ReviewLoading(Modifier.weight(1f).fillMaxWidth())
        else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = maxWidth
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(data.size, key = { it }, contentType = { "code-line" }) { number ->
                        val text = data.line(number)
                        val annotated = if (highlightReady) highlighted.subSequence(data.starts[number], data.end(number)) else AnnotatedString(text)
                        val matchColor = MaterialTheme.colorScheme.tertiaryContainer
                        val shown = remember(annotated, query, matchColor) {
                            AnnotatedString.Builder(annotated).apply {
                                if (query.isNotEmpty()) {
                                    var start = text.indexOf(query, ignoreCase = true)
                                    while (start >= 0) {
                                        addStyle(SpanStyle(background = matchColor), start, start + query.length)
                                        start = text.indexOf(query, start + query.length, ignoreCase = true)
                                    }
                                }
                            }.toAnnotatedString()
                        }
                        val hit = query.isNotEmpty() && number in matches
                        val gutter = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
                        Row(Modifier.widthIn(min = width).then(if (wrap) Modifier.fillMaxWidth() else Modifier)
                            .background(if (hit) matchColor.copy(alpha = .25f) else androidx.compose.ui.graphics.Color.Transparent).padding(end = 16.dp)) {
                            // Quiet gutter with a hairline divider, as in VS Code / GitHub blob view.
                            Text((number + 1).toString(), Modifier.width(56.dp).heightIn(min = 32.dp).clickable { commentLine = number + 1 }.semantics { contentDescription = "第 ${number + 1} 行，添加草稿评论" }
                                .drawBehind { drawLine(gutter, androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), androidx.compose.ui.geometry.Offset(size.width - 1f, size.height), 1f) }
                                .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), textAlign = TextAlign.End, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                            Text(shown, Modifier.then(if (wrap) Modifier.weight(1f) else Modifier).padding(start = 12.dp, top = 5.dp, bottom = 5.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp, softWrap = wrap)
                        }
                    }
                }
            }
        }
    }
    commentLine?.let { ReadingCommentDialog(source.path, it, ReviewSide.NEW, { commentLine = null }, onDraft) }
}

@Composable
private fun MarkdownFilePreview(source: SourcePreview, modifier: Modifier, onOpen: (String) -> Unit, canRead: Boolean, onScrolled: (Boolean) -> Unit,
    onProgress: (Float) -> Unit = {}) {
    val renderer = checkNotNull(LocalMarkdownCache.current)
    val rendered by produceState<RenderedMarkdown?>(null, source.path, source.text, renderer) { value = renderer.render("file:${source.path}", source.text) }
    val data = rendered?.takeIf { it.source == source.text }
    var toc by rememberSaveable(source.path) { mutableStateOf(false) }
    var notice by remember(source.path) { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 } }
    val report by rememberUpdatedState(onScrolled)
    LaunchedEffect(scrolled) { report(scrolled) }
    val progress by rememberUpdatedState(onProgress)
    LaunchedEffect(state, data) {
        snapshotFlow { state.layoutInfo.let { info -> if (info.totalItemsCount <= 1) 0f else (info.visibleItemsInfo.lastOrNull()?.index ?: 0) / (info.totalItemsCount - 1f) } }
            .collect { progress(it) }
    }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    // The heading the reader is in, so the outline marks "you are here".
    val currentHeading by remember(data) { derivedStateOf { data?.headings?.lastOrNull { it.partIndex <= state.firstVisibleItemIndex } } }
    Column(modifier.fillMaxWidth()) {
        if (data?.headings?.isNotEmpty() == true) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewerToggle(if (toc) "收起目录" else "目录", PocketIcons.ListTree, toc, { toc = !toc })
            currentHeading?.let { Text(it.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        androidx.compose.animation.AnimatedVisibility(toc && data != null) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(vertical = 6.dp)) {
                    itemsIndexed(data?.headings.orEmpty()) { _, heading ->
                        val here = heading == currentHeading
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { state.animateScrollToItem(heading.partIndex) }; toc = false }
                            .padding(start = (14 + (heading.level - 1) * 14).dp, end = 14.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (here) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, PocketCapsule))
                            Spacer(Modifier.width(8.dp))
                            Text(heading.title, style = if (heading.level <= 1) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                color = if (here) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        notice?.let { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.bodySmall)
            PocketTextButton(onClick = { notice = null }) { Text("关闭") }
        } }
        if (data == null) ReviewLoading(Modifier.weight(1f).fillMaxWidth())
        // Reading layout: wider margins and paragraph rhythm, like GitHub's rendered README.
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(data.parts, key = { i, _ -> i }) { _, part ->
                if (part.code != null) CodeBlock(part.code, part.language)
                else MarkdownSpans(part.spans ?: android.text.SpannedString(""), renderer, foreground, linkColor) { href ->
                    if (href.startsWith('#')) {
                        val anchor = runCatching { URLDecoder.decode(href.drop(1).replace("+", "%2B"), "UTF-8") }.getOrNull()
                        val target = data.headings.firstOrNull { it.anchor == anchor }
                        if (target != null) scope.launch { state.scrollToItem(target.partIndex) } else notice = "当前片段中未找到此标题"
                    } else {
                        val target = resolveWorkspaceLink(source.path, href, source.workspaceRoot)
                        when {
                            !canRead -> notice = "当前连接不支持读取文件"
                            target == null -> notice = "仅支持已确认工作区内的相对文件链接"
                            else -> { notice = null; onOpen(target) }
                        }
                    }
                }
            }
            if (data.images.isNotEmpty()) item { Text("文件预览不加载外部图片。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
