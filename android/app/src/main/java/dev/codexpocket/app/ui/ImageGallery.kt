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
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where gallery bytes come from. Root provides the repository; previews and tests provide nothing. */
internal fun interface DesktopImageSource { suspend fun file(path: String): File? }
internal val LocalDesktopImages = staticCompositionLocalOf<DesktopImageSource?> { null }

internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
internal fun looksLikeImage(file: MentionedFile) = file.image ||
    file.name.ifBlank { file.path }.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** Thumbnails decode at cell size; the viewer decodes larger. Both share one byte-bounded cache. */
private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
}

@Composable
private fun rememberGalleryBitmap(path: String, maxSide: Int): ImageLoad {
    val source = LocalDesktopImages.current
    val key = "$maxSide:$path"
    val load by produceState<ImageLoad>(bitmapCache.get(key)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Loading, key, source) {
        if (value is ImageLoad.Ready) return@produceState
        value = try {
            val file = source?.file(path) ?: return@produceState run { value = ImageLoad.Failed }
            val bitmap = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val ratio = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }
            bitmapCache.put(key, bitmap)
            ImageLoad.Ready(bitmap)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { ImageLoad.Failed }
    }
    return load
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
    Row(modifier.testTag("image-grid"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (deck && alignEnd) DeckPill(open, images.size) { open = !open }
        androidx.compose.animation.AnimatedContent(deck && !open, transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(pocketMotionMillis(200))) + androidx.compose.animation.scaleIn(spring(dampingRatio = .78f, stiffness = 520f), initialScale = .86f))
                .togetherWith(androidx.compose.animation.fadeOut(tween(pocketMotionMillis(120))) + androidx.compose.animation.scaleOut(tween(pocketMotionMillis(140)), targetScale = .92f))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        }, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart, label = "image-deck") { folded ->
            if (folded) ImageDeck(images) { viewer = 0 }
            else ImageCells(images, alignEnd) { viewer = it }
        }
        if (deck && !alignEnd) DeckPill(open, images.size) { open = !open }
    }
    viewer?.let { start -> ImageViewer(images, start.coerceIn(0, images.lastIndex)) { viewer = null } }
}

@Composable
private fun ImageCells(images: List<MentionedFile>, alignEnd: Boolean, onOpen: (Int) -> Unit) {
    val shown = images.take(9)
    val columns = when (shown.size) { 1 -> 1; 2, 4 -> 2; else -> 3 }
    val gap = 4.dp
    val cell: Dp = if (columns == 2) 104.dp else 84.dp
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
private fun ImageDeck(images: List<MentionedFile>, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val card = 150.dp
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
    Text(if (open) "收起" else "展开 $count", Modifier.testTag("image-deck-toggle").graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(PocketCapsule).background(c.surfaceContainerHigh)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
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
    Box(Modifier.width(width).aspectRatio(ratio).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(16.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Image, onClick = onOpen)
        .semantics { contentDescription = file.name }) { ImageFill(load, file) }
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
        }
    }
}
