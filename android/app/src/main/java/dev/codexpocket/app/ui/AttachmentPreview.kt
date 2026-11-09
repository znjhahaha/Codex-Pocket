package dev.codexpocket.app.ui

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexpocket.app.R
import dev.codexpocket.app.data.LocalAttachment
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** Ordered tool output: only adjacent image blocks form a gallery; prose retains its place. */
@Composable
internal fun ToolOutputContent(blocks: List<ToolOutputBlock>) {
    Column(Modifier.fillMaxWidth().testTag("tool-output-blocks"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var index = 0
        while (index < blocks.size) {
            when (val block = blocks[index]) {
                is ToolOutputBlock.Text -> { SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }; index++ }
                is ToolOutputBlock.Image -> {
                    val media = mutableListOf<ToolMediaRef>()
                    while (index < blocks.size && blocks[index] is ToolOutputBlock.Image) { media.add((blocks[index] as ToolOutputBlock.Image).media); index++ }
                    ToolMediaGrid(media)
                }
            }
        }
    }
}

/** Root binds this to the existing SourceScreen read-only preview action, respecting its read capability. */
internal val LocalAttachmentFilePreview = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun attachmentTypeIcon(file: MentionedFile) = when {
    looksLikeImage(file) -> PocketIcons.Image
    file.name.ifBlank { file.path }.substringAfterLast('.', "").lowercase() in setOf("kt", "kts", "rs", "py", "js", "ts", "tsx", "java", "c", "cpp", "h", "json", "yaml", "toml", "xml", "sh", "ps1") -> PocketIcons.Code
    else -> PocketIcons.Description
}

@Composable
internal fun AttachmentStrip(attachments: List<LocalAttachment>, fileFor: (LocalAttachment) -> File, onRemove: ((LocalAttachment) -> Unit)? = null) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<LocalAttachment?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            key(attachment.id) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.width(128.dp)) {
                        Box(Modifier.fillMaxWidth().height(72.dp).clickable(enabled = attachment.mime.startsWith("image/"), onClickLabel = context.getString(R.string.delivery_preview_image, attachment.name)) { preview = attachment }, contentAlignment = Alignment.Center) {
                            if (attachment.mime.startsWith("image/")) LocalImage(fileFor(attachment), attachment.name, 320, Modifier.fillMaxSize())
                            else Icon(PocketIcons.Description, attachment.name, Modifier.size(28.dp))
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(attachment.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (onRemove != null) ActionIcon(PocketIcons.Close, context.getString(R.string.remove_image, attachment.name)) { onRemove(attachment) }
                        }
                    }
                }
            }
        }
    }
    preview?.takeIf { shown -> attachments.any { it.id == shown.id } }?.let { shown ->
        ImagePreview(fileFor(shown), shown.name) { preview = null }
    }
}

@Composable
private fun LocalImage(file: File, name: String, maxSide: Int, modifier: Modifier = Modifier) {
    var failed by remember(file.path, maxSide) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, file.path, maxSide) {
        failed = false
        value = try {
            withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val ratio = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; null }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(PocketIcons.Image, null)
            Text(LocalContext.current.getString(R.string.delivery_image_unavailable), style = MaterialTheme.typography.labelSmall)
        } else PocketLoader()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreview(file: File, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { ActionIcon(PocketIcons.Close, context.getString(R.string.delivery_close_preview), onClick = onDismiss) })
        }) { padding -> LocalImage(file, name, 1600, Modifier.fillMaxSize().padding(padding).padding(16.dp)) }
    }
}

private val transcriptImageClient by lazy { OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).followRedirects(false).dns(object : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> = okhttp3.Dns.SYSTEM.lookup(hostname).also { addresses -> require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it is java.net.Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc) }) }
}).build() }
internal fun transcriptImageSources(item: TimelineItem): List<String> = if (!ConversationPresentation.visible(item)) emptyList() else {
    val content = item.raw.array("content").map(JsonElement::asObject)
    content.filter { it.string("type") in setOf("image", "input_image", "image_url") && !it.containsKey("mediaId") && !it.containsKey("status") }
        .map { it.string("url", it.string("imageUrl", (it["image_url"] as? JsonPrimitive)?.contentOrNull ?: it.obj("image_url").string("url"))) }.filter(String::isNotBlank)
}

private fun localImagePaths(item: TimelineItem): List<String> = if (!ConversationPresentation.visible(item)) emptyList() else
    item.raw.array("content").map(JsonElement::asObject).filter { it.string("type") == "localImage" }.map { it.string("path") }

private fun baseName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

/**
 * One list for everything the user attached: parsed "Files mentioned" entries plus desktop-only
 * `localImage` parts. The two sources spell the same path differently, so match by file name.
 */
internal fun userAttachments(item: TimelineItem, files: List<MentionedFile>): List<MentionedFile> {
    if (userMessageMedia(item).isNotEmpty()) return files.filterNot { it.image || looksLikeImage(it) }
    val seen = files.map { it.name.ifBlank { baseName(it.path) }.lowercase() }.toMutableSet()
    val extra = localImagePaths(item).filter { seen.add(baseName(it).lowercase()) }.map { MentionedFile(baseName(it), it, image = true) }
    return files + extra
}

/** Inline (remote/data URL) images. Local desktop paths render as attachment chips instead. */
@Composable
internal fun TranscriptImages(item: TimelineItem, includeLocal: Boolean = true) {
    val sources = remember(item.raw) { transcriptImageSources(item) }
    sources.forEach { source -> key(source) { RemoteTranscriptImage(source) } }
    if (!includeLocal) return
    val locals = remember(item.raw) { userAttachments(item, emptyList()) }
    if (locals.isNotEmpty()) ImageGrid(locals, alignEnd = false, modifier = Modifier.padding(top = 6.dp))
}

/**
 * Middle ellipsis that keeps the extension and the end of the stem, so report_v2.docx and
 * report_v2.pdf stay distinguishable. Wide (CJK) characters count double against a chip-sized budget.
 */
internal fun chipName(name: String, budget: Int = 30): String {
    fun width(ch: Char) = if (ch.code >= 0x2E80) 2 else 1
    if (name.sumOf { width(it) } <= budget) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it in 2..8 } ?: name.length
    val ext = name.substring(dot)
    val stem = name.substring(0, dot)
    var room = budget - ext.sumOf { width(it) } - 1
    val tailRoom = room / 3
    val tail = StringBuilder()
    for (ch in stem.reversed()) { if (tail.sumOf { width(it) } + width(ch) > tailRoom) break; tail.insert(0, ch) }
    room -= tail.sumOf { width(it) }
    val head = StringBuilder()
    for (ch in stem) { if (head.sumOf { width(it) } + width(ch) > room) break; head.append(ch) }
    return "$head…$tail$ext"
}

/** Up to two chips inline; more fold into one expandable summary so the request stays readable. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentChips(key: String, files: List<MentionedFile>, alignEnd: Boolean) {
    val c = MaterialTheme.colorScheme
    val fallback = LocalContext.current.getString(R.string.v02_image)
    val grouped = files.size > 2
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var detailsPath by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val onPreview = LocalAttachmentFilePreview.current
    val clipboard = LocalClipboardManager.current
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(pocketMotionMillis(160)), label = "attachments-chevron")
    val arrangement = if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp)
    Column(Modifier.animateContentSize(spring(dampingRatio = .86f, stiffness = 520f)),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (grouped) {
            val images = files.all { it.image }
            Row(Modifier.clip(PocketOval).background(c.surfaceContainer).clickable(role = Role.Button) { open = !open }
                .testTag("desktop-images-toggle").semantics { stateDescription = if (open) "已展开" else "已收起" }
                .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (images) PocketIcons.Image else PocketIcons.Description, null, Modifier.size(15.dp), tint = c.onSurfaceVariant)
                Text(stringResource(if (images) R.string.v04_desktop_images else R.string.v04_attachments, files.size), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Icon(PocketIcons.KeyboardArrowDown, null, Modifier.size(15.dp).graphicsLayer { rotationZ = chevron }, tint = c.onSurfaceVariant)
            }
        }
        if (!grouped || open) FlowRow(horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { file -> key(file.path.ifBlank { file.name }) {
                Row(Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer.copy(alpha = .7f))
                    .combinedClickable(role = Role.Button, onClickLabel = "只读预览", onLongClickLabel = "文件路径详情",
                        onClick = { if (onPreview != null && file.path.isNotBlank()) onPreview(file.path) else detailsPath = file.path.ifBlank { file.name } },
                        onLongClick = { detailsPath = file.path.ifBlank { file.name } })
                    .testTag("attachment-chip:${file.path.ifBlank { file.name }}")
                    .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(attachmentTypeIcon(file), null, Modifier.size(15.dp), tint = c.onSurfaceVariant)
                    // Long names keep their extension visible so report.docx and report.pdf stay distinguishable.
                    val name = file.name.ifBlank { baseName(file.path) }.ifBlank { fallback }
                    Text(remember(name) { chipName(name) }, Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } }
        }
    }
    detailsPath?.let { path -> AlertDialog(onDismissRequest = { detailsPath = null }, title = { Text(baseName(path)) },
        text = { SelectionContainer { Text(path, Modifier.testTag("attachment-full-path"), style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(path)); detailsPath = null }) { Text("复制完整路径") } },
        dismissButton = { TextButton(enabled = onPreview != null && files.any { it.path == path }, onClick = { detailsPath = null; onPreview?.invoke(path) }) { Text("只读预览") } }) }
}

/** Bounded native image decoding. No arbitrary desktop file paths or authenticated fetches. */
@Composable
internal fun RemoteTranscriptImage(source: String) {
    var preview by remember { mutableStateOf(false) }
    var requested by remember(source) { mutableStateOf(source.startsWith("data:image/")) }
    var failed by remember(source) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, source, requested) {
        if (!requested) return@produceState
        failed = false
        value = try { withContext(Dispatchers.IO) {
            val bytes = when {
                source.startsWith("data:image/") && source.substringBefore(',').endsWith(";base64") -> {
                    require(source.length <= 12_000_000); Base64.decode(source.substringAfter(','), Base64.DEFAULT)
                }
                source.startsWith("https://") -> transcriptImageClient.newCall(Request.Builder().url(source).build()).execute().use { response ->
                    require(response.isSuccessful)
                    val body = response.body ?: error("image unavailable")
                    require(body.contentLength() <= 8_000_000)
                    require(body.contentType()?.type == "image")
                    body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 8_000_000); output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                }
                else -> error("image unavailable")
            }
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val ratio = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true; null }
    }
    val label = LocalContext.current.getString(R.string.v02_image)
    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp).clickable { if (bitmap != null) preview = true else requested = true }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, label, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
        if (bitmap == null) Text(if (!requested) LocalContext.current.getString(R.string.v02_load_external_image, runCatching { java.net.URI(source).host }.getOrNull().orEmpty()) else LocalContext.current.getString(if (failed) R.string.delivery_image_unavailable else R.string.v02_image_loading), style = MaterialTheme.typography.bodySmall)
    }
    if (preview && bitmap != null) Dialog({ preview = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
            Image(bitmap!!, label, Modifier.fillMaxSize().padding(20.dp), contentScale = ContentScale.Fit)
            IconButton(onClick = { preview = false }, Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Icon(PocketIcons.Close, LocalContext.current.getString(R.string.delivery_close_preview)) }
        } }
    }
}
