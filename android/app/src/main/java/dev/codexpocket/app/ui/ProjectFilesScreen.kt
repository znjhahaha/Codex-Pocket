package dev.codexpocket.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.*

/** All reads are owned by the caller, which must supply current pairing/capability gates. */
@Composable
internal fun ProjectFilesScreen(
    listing: ProjectFileListing?, loading: Boolean, error: String?,
    onNavigate: (String) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit,
    canList: Boolean = true, canRead: Boolean = true,
) {
    var query by rememberSaveable(listing?.path) { mutableStateOf("") }
    val entries = remember(listing, query) { listing?.entries.orEmpty().filter { it.name.contains(query, ignoreCase = true) } }
    val state = rememberLazyListState()
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 } }
    LaunchedEffect(listing?.path) { state.scrollToItem(0) }
    Scaffold(topBar = { ReviewHeader("项目文件", "返回", onBack, scrolled) {
        ActionIcon(PocketIcons.Refresh, "刷新目录", canList && !loading) { onNavigate(listing?.path.orEmpty()) }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listing?.path?.ifBlank { "工作区" } ?: "工作区", style = MaterialTheme.typography.bodySmall)
                PocketTextField(query, { query = it }, placeholder = "筛选当前目录", singleLine = true)
            }
            if (!canList) Notice("当前连接不支持浏览项目文件")
            if (loading) PocketProgressStatus("正在读取目录", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (error != null) {
                Notice(error)
                PocketTextButton(onClick = { onNavigate(listing?.path.orEmpty()) }, enabled = canList && !loading) { Text("重试") }
            }
            if (listing?.truncated == true) Notice("目录结果不完整；仅显示后端返回的文件，筛选范围也仅限这些结果。")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp)) {
                val parent = listing?.let(::projectParentPath)
                if (parent != null) item(key = "parent") { PocketActionRow("上一级", PocketIcons.ArrowUpward, { onNavigate(parent) }, enabled = canList && !loading) }
                if (listing != null && listing.roots.size > 1) items(listing.roots, key = { "root:$it" }) { root ->
                    PocketActionRow(root, PocketIcons.FolderOpen, { onNavigate(root) }, subtitle = "工作区根目录", enabled = canList && !loading)
                }
                if (!loading && error == null && listing != null && entries.isEmpty()) item { Text(if (query.isEmpty()) "此目录没有可显示的文件" else "当前目录中无匹配文件", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(entries, key = { "entry:${it.path}" }, contentType = { "file" }) { entry ->
                    PocketActionRow(
                        entry.name, if (entry.directory) PocketIcons.FolderOpen else PocketIcons.Description,
                        { if (entry.directory) onNavigate(entry.path) else onOpen(entry.path) },
                        subtitle = if (entry.directory) "目录" else entry.size?.let { "$it B" },
                        enabled = !loading && if (entry.directory) canList else canRead,
                        trailing = { Icon(PocketIcons.ChevronRight, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}
