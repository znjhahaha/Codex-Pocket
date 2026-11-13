package dev.codexpocket.app.ui

import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexpocket.app.data.MentionedFile
import dev.codexpocket.app.data.*
import android.util.Base64
import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where gallery bytes come from. Root provides the repository; previews and tests provide nothing. */
internal fun interface DesktopImageSource {
    suspend fun file(path: String): File?
    val ownerKey: String get() = ""
    suspend fun retry(path: String) = Unit
}
internal val LocalDesktopImages = staticCompositionLocalOf<DesktopImageSource?> { null }

/** Parent supplies authenticated, owner-bound endpoint reads. No desktop path is accepted. */
internal fun interface ToolMediaImageSource {
    suspend fun read(media: ToolMediaRef, offset: Long, maxBytes: Int): ToolMediaChunk
    suspend fun refresh(media: ToolMediaRef, manual: Boolean = false) = Unit
}
internal val LocalToolMediaImages = staticCompositionLocalOf<ToolMediaImageSource?> { null }
internal val LocalImageConnection = staticCompositionLocalOf { 0L to false }
private val LocalGalleryToolRefs = staticCompositionLocalOf<Map<String, ToolMediaRef>> { emptyMap() }
private val imageDecodeSlots = Semaphore(2)

internal class ToolMediaLoadException(val status: ToolMediaStatus) : Exception()

/** One fixed-size allocation; every chunk is checked before it is copied into the image. */
internal suspend fun readToolImage(media: ToolMediaRef, source: ToolMediaImageSource): ByteArray {
    if (!media.readable) throw ToolMediaLoadException(if (media.status == ToolMediaStatus.Ready) ToolMediaStatus.Failed else media.status)
    val first = source.read(media, 0, TOOL_IMAGE_CHUNK_BYTES)
    if (first.status != ToolMediaStatus.Ready) throw ToolMediaLoadException(first.status)
    require(first.mediaId == media.mediaId && first.offset == 0L && first.size in 1..MAX_TOOL_IMAGE_BYTES && first.mimeType in TOOL_IMAGE_MIMES)
    if (media.byteLength > 0) require(first.size == media.byteLength)
    if (media.mimeType.isNotBlank()) require(first.mimeType == media.mimeType)
    val bytes = ByteArray(first.size.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val chunk = if (offset == 0) first else source.read(media, offset.toLong(), TOOL_IMAGE_CHUNK_BYTES)
        if (chunk.status != ToolMediaStatus.Ready) throw ToolMediaLoadException(chunk.status)
        require(chunk.mediaId == media.mediaId && chunk.offset == offset.toLong() && chunk.size == first.size && chunk.mimeType == first.mimeType)
        require(chunk.data.isNotEmpty() && chunk.data.length <= ((TOOL_IMAGE_CHUNK_BYTES + 2) / 3) * 4)
        val decoded = Base64.decode(chunk.data, Base64.NO_WRAP)
        require(decoded.isNotEmpty() && decoded.size <= TOOL_IMAGE_CHUNK_BYTES && decoded.size <= bytes.size - offset)
        require(chunk.eof == (offset + decoded.size == bytes.size))
        decoded.copyInto(bytes, offset)
        offset += decoded.size
    }
    if (toolImageMime(bytes) != first.mimeType) throw ToolMediaLoadException(ToolMediaStatus.Unsupported)
    return bytes
}

internal fun toolMediaLabel(status: ToolMediaStatus): String = when (status) {
    ToolMediaStatus.Ready, ToolMediaStatus.Recoverable -> "图片"
    ToolMediaStatus.Unsupported -> "暂不支持这张图片"
    ToolMediaStatus.Expired -> "图片引用已失效，点击重试"
    ToolMediaStatus.Failed -> "图片读取失败"
    ToolMediaStatus.OwnerChanged -> "图片归属已变化，请重新打开"
    ToolMediaStatus.Offline -> "连接电脑后可读取图片"
}

/** Uses exactly the same cells, viewer, zoom gestures and bounded bitmap cache as attachments. */
@Composable
internal fun ToolMediaGrid(media: List<ToolMediaRef>, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    if (media.isEmpty()) return
    val files = remember(media) { media.mapIndexed { index, ref -> MentionedFile("图片 ${index + 1}", "tool-media:${ref.cacheKey}:$index", image = true) } }
    val refs = remember(media, files) { files.zip(media).associate { (file, ref) -> file.path to ref } }
    CompositionLocalProvider(LocalGalleryToolRefs provides refs) { ImageGrid(files, alignEnd = alignEnd, modifier = modifier.testTag("tool-media-grid")) }
}

internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
internal fun looksLikeImage(file: MentionedFile) = file.image ||
    file.name.ifBlank { file.path }.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** Thumbnails decode at cell size; the viewer decodes larger. Both share one byte-bounded cache. */
private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private val galleryRetries = mutableStateMapOf<String, Int>()

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
    data class MediaUnavailable(val status: ToolMediaStatus) : ImageLoad
}

@Composable
private fun rememberGalleryBitmap(path: String, maxSide: Int): ImageLoad {
    val source = LocalDesktopImages.current
    val media = LocalGalleryToolRefs.current[path]
    val toolSource = LocalToolMediaImages.current
    val identity = "${source?.ownerKey}:${media?.cacheKey ?: path}"
    val key = "$maxSide:$identity"
    val retry = galleryRetries[identity] ?: 0
    val connection = LocalImageConnection.current
    val load by produceState<ImageLoad>(bitmapCache.get(key)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Loading, key, media, source, toolSource, retry, connection) {
        value = bitmapCache.get(key)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Loading
        if (value is ImageLoad.Ready) return@produceState
        suspend fun unavailable(status: ToolMediaStatus): ImageLoad {
            if (status == ToolMediaStatus.Expired && media != null && connection.second) {
                try { toolSource?.refresh(media) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep local retry state if history itself is unavailable. */ }
            }
            return ImageLoad.MediaUnavailable(status)
        }
        value = try {
            val bitmap = withContext(Dispatchers.IO) { imageDecodeSlots.withPermit {
                val input = if (media != null) {
                    if (!media.readable) throw ToolMediaLoadException(if (media.status == ToolMediaStatus.Ready) ToolMediaStatus.Failed else media.status)
                    val bytes = readToolImage(media, toolSource ?: throw ToolMediaLoadException(ToolMediaStatus.Failed))
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                } else {
                    // Never route a tool reference to the arbitrary-path desktop image callback.
                    require(!path.startsWith("tool-media:"))
                    val file = source?.file(path) ?: error("Image unavailable")
                    require(file.length() in 1..MAX_TOOL_IMAGE_BYTES)
                    ImageDecoder.createSource(file)
                }
                ImageDecoder.decodeBitmap(input) { decoder, info, _ ->
                    require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 100_000_000)
                    val ratio = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            } }
            bitmapCache.put(key, bitmap)
            ImageLoad.Ready(bitmap)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ToolMediaLoadException) { unavailable(failure.status) }
        catch (failure: PocketOperationException) { unavailable(mediaFailureStatus(failure.failure)) }
        catch (failure: dev.codexpocket.wire.WireException) { unavailable(
            if (failure.code in setOf("offline", "disconnected")) ToolMediaStatus.Offline else mediaFailureStatus(PocketFailure.fromCode(failure.code))) }
        catch (_: Exception) { ImageLoad.MediaUnavailable(ToolMediaStatus.Failed) }
    }
    return load
}

private fun mediaFailureStatus(failure: PocketFailure?) = when (failure) {
    PocketFailure.MediaOwner, PocketFailure.Ownership -> ToolMediaStatus.OwnerChanged
    PocketFailure.MediaExpired -> ToolMediaStatus.Expired
    PocketFailure.MediaUnsupported, PocketFailure.Unsupported -> ToolMediaStatus.Unsupported
    else -> ToolMediaStatus.Failed
}

@Composable
private fun GalleryRetry(file: MentionedFile) {
    val source = LocalDesktopImages.current
    val toolSource = LocalToolMediaImages.current
    val media = LocalGalleryToolRefs.current[file.path]
    val scope = rememberCoroutineScope()
    PocketTextButton({ scope.launch {
        try { if (media == null) source?.retry(file.path) else toolSource?.refresh(media, manual = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retry stays attached to this image, without a global error. */ }
        val identity = "${source?.ownerKey}:${media?.cacheKey ?: file.path}"
        if (galleryRetries.size > 512) galleryRetries.clear()
        galleryRetries[identity] = (galleryRetries[identity] ?: 0) + 1
    } }, Modifier.heightIn(min=48.dp).testTag("image-retry:${media?.mediaId ?: file.path}")) { Text("重试图片") }
}

/**
 * WeChat-style image group. One image keeps its aspect ratio; 2 and 4 use two columns; the rest use
 * three. At most nine cells; the ninth shows "+N" for the remainder. Tapping opens the viewer there.
 */
@Composable
internal fun ImageGrid(images: List<MentionedFile>, alignEnd: Boolean, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    var viewer by rememberSaveable { mutableStateOf<Int?>(null) }
    // Several images arrive folded into a deck of cards; the pill beside it fans them out into the grid.
    var open by rememberSaveable(images.size) { mutableStateOf(false) }
    val deck = images.size > 1
    BoxWithConstraints(modifier.testTag("image-grid")) {
        if (!deck) ImageCells(images, alignEnd) { viewer = it }
        else {
            // Geometry comes from count and parent constraints, never image intrinsic dimensions.
            // Reserve both states before either is drawn: expanding cannot move the scroll anchor.
            val geometry = multiImageGeometry(images.size, (maxWidth - 98.dp).coerceAtLeast(32.dp))
            val progress by animateFloatAsState(if (open) 1f else 0f,
                tween(pocketMotionMillis(200), easing = androidx.compose.animation.core.LinearEasing), label = "image-deck-reveal")
            val shownOpen = progress >= .5f
            val opacity = kotlin.math.abs(progress - .5f) * 2f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (alignEnd) DeckPill(open, images.size) { open = !open }
                Box(Modifier.size(geometry.width, geometry.height).testTag("image-transition-frame").clipToBounds()
                    .graphicsLayer { alpha = opacity; clip = true; compositingStrategy = CompositingStrategy.Offscreen },
                    contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
                    // Only one subtree is mounted. A reversal cancels its fade and starts from current alpha.
                    if (shownOpen) ImageCells(images, alignEnd, geometry.cell) { viewer = it }
                    else ImageDeck(images, geometry.card) { viewer = 0 }
                }
                if (!alignEnd) DeckPill(open, images.size) { open = !open }
            }
        }
    }
    viewer?.let { start -> ImageViewer(images, start.coerceIn(0, images.lastIndex)) { viewer = null } }
}

internal data class MultiImageGeometry(val width: Dp, val height: Dp, val cell: Dp, val card: Dp)
internal fun multiImageGeometry(count: Int, availableWidth: Dp): MultiImageGeometry {
    val shown = count.coerceIn(2, 9)
    val columns = if (shown == 2 || shown == 4) 2 else 3
    val cell = minOf(if (columns == 2) 104.dp else 84.dp, (availableWidth - ((columns - 1) * 4).dp) / columns).coerceAtLeast(1.dp)
    val card = minOf(150.dp, (availableWidth - 30.dp).coerceAtLeast(1.dp))
    val rows = (shown + columns - 1) / columns
    return MultiImageGeometry(maxOf(cell * columns + ((columns - 1) * 4).dp, card + 30.dp),
        maxOf(cell * rows + ((rows - 1) * 4).dp, card + 24.dp), cell, card)
}

@Composable
private fun ImageCells(images: List<MentionedFile>, alignEnd: Boolean, cellSize: Dp? = null, onOpen: (Int) -> Unit) {
    val shown = images.take(9)
    val columns = when (shown.size) { 1 -> 1; 2, 4 -> 2; else -> 3 }
    val gap = 4.dp
    val cell: Dp = cellSize ?: if (columns == 2) 104.dp else 84.dp
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(gap)) {
        if (shown.size == 1) SingleImage(shown[0]) { onOpen(0) }
        else shown.chunked(columns).forEachIndexed { row, items ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEachIndexed { column, file ->
                    val index = row * columns + column
                    val more = if (index == 8 && images.size > 9) images.size - 9 else 0
                    GridCell(file, cell, cellShape(index, shown.size, columns), more) { onOpen(index) }
                }
            }
        }
    }
}

/** Up to three cards: the first image on top, the next two peeking out to the right, slightly turned. */
@Composable
private fun ImageDeck(images: List<MentionedFile>, card: Dp = 150.dp, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val behind = minOf(images.size, 3) - 1
    Box(Modifier.testTag("image-deck").padding(end = (behind * 9).dp, top = 6.dp, bottom = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, role = Role.Image, onClick = onOpen)
        .semantics { contentDescription = images.joinToString("、") { it.name } }) {
        for (depth in behind downTo 1) {
            val file = images[depth]
            Box(Modifier.size(card).graphicsLayer {
                translationX = (depth * 9).dp.toPx(); rotationZ = depth * 3.5f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
                alpha = 1f - depth * .12f
            }.shadow((2 + depth).dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .16f)).clip(RoundedCornerShape(16.dp))) {
                ImageFill(rememberGalleryBitmap(file.path, 360), file)
            }
        }
        Box(Modifier.size(card).shadow(4.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .16f)).clip(RoundedCornerShape(16.dp))) {
            ImageFill(rememberGalleryBitmap(images[0].path, 360), images[0])
        }
    }
}

@Composable
private fun DeckPill(open: Boolean, count: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Text(if (open) "收起" else "展开 $count", Modifier.width(88.dp).testTag("image-deck-toggle").graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(PocketCapsule).background(c.surfaceContainerHigh)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Outer corners are round, inner ones tight, so the group reads as one tile like iOS Photos. */
private fun cellShape(index: Int, count: Int, columns: Int): RoundedCornerShape {
    val rows = (count + columns - 1) / columns
    val row = index / columns; val column = index % columns
    val lastInRow = column == columns - 1 || index == count - 1
    val big = 14.dp; val small = 4.dp
    return RoundedCornerShape(
        topStart = if (row == 0 && column == 0) big else small,
        topEnd = if (row == 0 && lastInRow) big else small,
        bottomEnd = if (row == rows - 1 && lastInRow) big else small,
        bottomStart = if (row == rows - 1 && column == 0) big else small)
}

@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) .95f else 1f, pocketPressSpring(), label = "image-press").value
}

@Composable
private fun SingleImage(file: MentionedFile, onOpen: () -> Unit) {
    val load = rememberGalleryBitmap(file.path, 720)
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val ratio = (load as? ImageLoad.Ready)?.bitmap?.let { (it.width.toFloat() / it.height).coerceIn(.5f, 2.2f) } ?: 1.33f
    val width = if (ratio >= 1f) 240.dp else (240 * ratio).dp.coerceAtLeast(140.dp)
    Column(Modifier.width(width)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Image, onClick = onOpen)
            .semantics { contentDescription = file.name }) { ImageFill(load, file) }
        if(load is ImageLoad.MediaUnavailable && load.status !in setOf(ToolMediaStatus.Unsupported,ToolMediaStatus.OwnerChanged)) GalleryRetry(file)
    }
}

@Composable
private fun GridCell(file: MentionedFile, size: Dp, shape: RoundedCornerShape, more: Int, onOpen: () -> Unit) {
    val load = rememberGalleryBitmap(file.path, 360)
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Image, onClick = onOpen)
        .semantics { contentDescription = file.name }) {
        ImageFill(load, file)
        if (more > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
            Text("+$more", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Shimmer while loading, a fade in when ready, a quiet glyph and name when bytes are unavailable. */
@Composable
private fun ImageFill(load: ImageLoad, file: MentionedFile) {
    val c = MaterialTheme.colorScheme
    val appear by animateFloatAsState(if (load is ImageLoad.Ready) 1f else 0f, tween(pocketMotionMillis(220)), label = "image-appear")
    Box(Modifier.fillMaxSize().background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when (load) {
            is ImageLoad.Ready -> Image(load.bitmap, file.name, Modifier.fillMaxSize().graphicsLayer { alpha = appear }, contentScale = ContentScale.Crop)
            ImageLoad.Loading -> PocketShimmer(Modifier.fillMaxSize())
            ImageLoad.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(PocketIcons.Image, null, Modifier.size(20.dp), tint = c.onSurfaceVariant)
                Text(file.name.ifBlank { file.path.substringAfterLast('\\').substringAfterLast('/') }, style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            is ImageLoad.MediaUnavailable -> Text(toolMediaLabel(load.status), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant, maxLines = 2)
        }
    }
}

/**
 * Full-screen viewer: swipe between images, pinch or double-tap to zoom, drag down to dismiss.
 * The backdrop fades with the drag, the way the iOS Photos viewer lets go of an image.
 */
@Composable
internal fun ImageViewer(images: List<MentionedFile>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(start) { images.size }
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val enter = remember { Animatable(if (pocketMotionMillis(1) == 0) 1f else 0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = .82f, stiffness = 420f)) }
        val fade = (1f - abs(drag.value) / 900f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade * enter.value)).testTag("image-viewer")) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { images[it].path.ifBlank { images[it].name } }) { page ->
                ZoomableImage(images[page], drag.value, enter.value,
                    onDrag = { delta -> scope.launch { drag.snapTo(drag.value + delta) } },
                    onRelease = {
                        scope.launch {
                            if (abs(drag.value) > 220f) { drag.animateTo(drag.value * 2.4f, tween(pocketMotionMillis(160))); onDismiss() }
                            else drag.animateTo(0f, spring(dampingRatio = .8f, stiffness = 500f))
                        }
                    })
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp).graphicsLayer { alpha = fade },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(PocketCapsule).background(Color.White.copy(alpha = .14f)).clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "关闭" }, contentAlignment = Alignment.Center) {
                    Icon(PocketIcons.Close, null, Modifier.size(20.dp), tint = Color.White)
                }
                Text(images[pager.currentPage].name, Modifier.weight(1f).padding(horizontal = 12.dp), color = Color.White.copy(alpha = .9f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", Modifier.clip(PocketCapsule)
                    .background(Color.White.copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: MentionedFile, drag: Float, enter: Float, onDrag: (Float) -> Unit, onRelease: () -> Unit) {
    val load = rememberGalleryBitmap(file.path, 2048)
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { scope.launch {
                if (zoom.value > 1.05f) { pan = Offset.Zero; zoom.animateTo(1f, spring(dampingRatio = .85f, stiffness = 400f)) }
                else zoom.animateTo(2.5f, spring(dampingRatio = .85f, stiffness = 400f))
            } })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var vertical: Boolean? = null
                do {
                    val event = awaitPointerEvent()
                    val pinch = event.changes.size > 1
                    val change = event.calculatePan()
                    if (pinch || zoom.value > 1.01f) {
                        // Zoomed: this page owns the gesture, the pager must not scroll.
                        val next = (zoom.value * event.calculateZoom()).coerceIn(1f, 5f)
                        scope.launch { zoom.snapTo(next) }
                        pan = if (next <= 1.01f) Offset.Zero else pan + change
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else {
                        if (vertical == null && (abs(change.x) > 2f || abs(change.y) > 2f)) vertical = abs(change.y) > abs(change.x) * 1.4f
                        if (vertical == true) { onDrag(change.y); event.changes.forEach { if (it.positionChanged()) it.consume() } }
                    }
                } while (event.changes.any { it.pressed })
                if (vertical == true) onRelease()
            }
        }, contentAlignment = Alignment.Center) {
        val shrink = 1f - (abs(drag) / 2400f).coerceAtMost(.25f)
        when (load) {
            is ImageLoad.Ready -> Image(load.bitmap, file.name, Modifier.fillMaxSize().graphicsLayer {
                val s = zoom.value * shrink * (.92f + .08f * enter)
                scaleX = s; scaleY = s; translationX = pan.x; translationY = pan.y + drag; alpha = enter
            }, contentScale = ContentScale.Fit)
            ImageLoad.Loading -> PocketLoader()
            ImageLoad.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PocketIcons.Image, null, Modifier.size(36.dp), tint = Color.White.copy(alpha = .7f))
                Spacer(Modifier.height(8.dp))
                Text("无法读取这张图片。电脑助手需要更新到 0.5 才能预览电脑上的图片。", Modifier.padding(horizontal = 32.dp),
                    color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
            }
            is ImageLoad.MediaUnavailable -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(toolMediaLabel(load.status), Modifier.padding(32.dp), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
                GalleryRetry(file)
            }
        }
    }
}
