package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/** How the arriving page relates to the one it replaces. */
internal enum class PStageMove {
    /** Deeper: arrives from the trailing edge over the page it came from, which recedes. */
    Push,
    /** Back out: leaves toward the trailing edge, uncovering the page beneath. */
    Pop,
    /** A sibling further along: both pages shift a short way as one fades through the other. */
    Forward,
    Backward,
    /** Unrelated: the new page settles in as the old one fades. */
    Fade;

    val reversed: PStageMove get() = when (this) { Push -> Pop; Pop -> Push; Forward -> Backward; Backward -> Forward; Fade -> Fade }
}

/**
 * What a [PStage] is showing. [progress] runs from 0, where only [from] is seen, to 1, where only
 * [to] is. The numbers are plain so a transition can be checked frame by frame.
 */
@Stable
internal class PStageState<K : Any>(initial: K) {
    /** The page the caller asked for. It alone receives input and is read by accessibility. */
    var target by mutableStateOf(initial)
        private set
    var from by mutableStateOf<K?>(null)
        private set
    var to by mutableStateOf(initial)
        private set
    var move by mutableStateOf(PStageMove.Fade)
        private set
    /** A back gesture is holding the transition part-way and has not been let go. */
    var held by mutableStateOf(false)
        private set
    val progress = Animatable(1f)
    /** What the pages of this stage can open out of. */
    val sources = PMorphSources()
    /** Set while the page in front is growing out of a source or folding back into it, rather than sliding. */
    var morph by mutableStateOf<PStageMorph?>(null)
        private set
    /** Pages that opened out of a source and are still on the stack: they fold back the way they came. */
    private val opened = HashMap<K, PStageMorph>()

    internal suspend fun show(page: K, motion: Boolean, source: (K) -> Any? = { null }, between: (K, K) -> PStageMove) {
        if (page == target && from == null) return
        target = page
        if (!motion) { from = null; to = page; held = false; morph = null; opened.clear(); progress.snapTo(1f); return }
        var velocity = 0f
        var fresh = false
        when {
            // Already on its way there: a committed back gesture, or the same destination asked for again.
            from != null && page == to -> Unit
            // Turned round mid-way: carry on from here, with the speed it had.
            from != null && page == from -> {
                val at = progress.value
                velocity = -progress.velocity
                from = to; to = page; move = move.reversed
                progress.snapTo(1f - at)
            }
            else -> {
                // Under a held gesture the page still in front is the one being left, not the one peeking beneath.
                val leaving = if (held) from ?: to else to
                val step = between(leaving, page)
                morph = when (step) {
                    PStageMove.Push -> source(page)?.let { key -> sources.bounds(key)?.let { PStageMorph(key, it) } }
                        .also { if (it != null) opened[page] = it else opened.remove(page) }
                    // Only into the source it came from: a page now showing something else slides away.
                    PStageMove.Pop -> opened[leaving]?.takeIf { source(leaving).let { key -> key == null || key == it.key } }
                    else -> null
                }
                from = leaving; to = page; move = step; progress.snapTo(0f)
                fresh = true
            }
        }
        held = false
        // The page coming in is composed for the first time in the next frames, which can be long ones.
        // Moving starts once they are over, so the first steps of the way are seen rather than skipped.
        if (fresh) settleIn()
        // A page still loading as it arrives stretches the transition rather than cutting steps out of it.
        pocketSteadyFrames { progress.animateTo(1f, PocketMotion.page(), velocity) }
        if (move != PStageMove.Push) from?.let { opened.remove(it) }
        from = null; morph = null
    }

    /** A back gesture at [amount] of its travel: [peek] shows beneath the page being dragged away. */
    internal suspend fun hold(peek: K, amount: Float) {
        if (!held) {
            if (peek == target) return
            from = target; to = peek; move = PStageMove.Pop; morph = opened[target]; held = true
        }
        progress.snapTo(amount.coerceIn(0f, 1f) * HeldTravel)
    }

    /** The gesture was abandoned: the page comes back. */
    internal suspend fun letGo() {
        if (!held) return
        pocketSteadyFrames { progress.animateTo(0f, PocketMotion.page()) }
        if (!held) return
        from?.let { to = it }
        from = null; held = false; morph = null
        progress.snapTo(1f)
    }

    /** Waits until frames are arriving on time again, or until waiting longer would itself be felt. */
    private suspend fun settleIn() {
        val start = withFrameNanos { it }
        var last = start
        repeat(SettleFrames) {
            val now = withFrameNanos { it }
            if (now - last < LongFrameNanos || now - start > SettleNanos) return
            last = now
        }
    }

    private companion object {
        /** A held page stops short of leaving, so letting go always has somewhere to travel. */
        const val HeldTravel = .86f
        /** A frame this late was held up by work, on a 60 Hz display or a faster one. */
        const val LongFrameNanos = 34_000_000L
        const val SettleNanos = 280_000_000L
        const val SettleFrames = 6
    }
}

/** What a stage lets a stage inside it do with a back gesture that the inner one has no page for. */
internal interface PStageGesture {
    /** False when this stage has nowhere to go back to either. */
    fun hold(amount: Float): Boolean
    fun cancel()
    fun committed()
}

internal val LocalPStageGesture = staticCompositionLocalOf<PStageGesture?> { null }

@Composable
internal fun <K : Any> rememberPStageState(initial: K): PStageState<K> = remember { PStageState(initial) }

/**
 * Shows one page at a time and moves between them the way a navigation stack does. Both pages stay
 * composed while one replaces the other, so a page leaves as it was rather than vanishing; only
 * the page asked for takes input. A transition interrupted by its own reversal turns round where it
 * is, and where the system reports back-gesture progress the page follows the finger with [back]
 * showing beneath it.
 *
 * [depth] orders pages in the hierarchy and [order] orders siblings at the same depth. [content]
 * must be able to draw any page it has been given, including one the caller has just left.
 *
 * [source] names what a page opens out of. When the page beneath holds an element marked with
 * [pocketMorphSource] under that name, the page grows from it and later folds back into it, following
 * the element if it has moved; with no such element on screen the page slides as usual.
 */
@Composable
internal fun <K : Any> PStage(page: K, modifier: Modifier = Modifier, depth: (K) -> Int = { 0 }, order: (K) -> Int = { 0 },
    back: K? = null, onBack: (() -> Unit)? = null, state: PStageState<K> = rememberPStageState(page),
    source: (K) -> Any? = { null }, content: @Composable (K) -> Unit) {
    val motion = rememberPocketMotion()
    val scope = rememberCoroutineScope()
    val currentDepth by rememberUpdatedState(depth)
    val currentOrder by rememberUpdatedState(order)
    val currentSource by rememberUpdatedState(source)
    val peek by rememberUpdatedState(back)
    val motionNow by rememberUpdatedState({ motion })
    LaunchedEffect(page, motion) {
        state.show(page, motion, { currentSource(it) }) { old, new ->
            val change = currentDepth(new) - currentDepth(old)
            val step = currentOrder(new) - currentOrder(old)
            when { change > 0 -> PStageMove.Push; change < 0 -> PStageMove.Pop; step > 0 -> PStageMove.Forward; step < 0 -> PStageMove.Backward; else -> PStageMove.Fade }
        }
    }
    val outer = LocalPStageGesture.current
    val gesture = remember(state, scope) {
        object : PStageGesture {
            override fun hold(amount: Float): Boolean {
                val under = peek ?: return false
                if (motionNow()) scope.launch { state.hold(under, amount) }
                return true
            }
            override fun cancel() { scope.launch { state.letGo() } }
            // Back went somewhere other than the page that was showing beneath: put this one back.
            override fun committed() { if (state.held) scope.launch { repeat(2) { withFrameNanos { } }; if (state.held) state.letGo() } }
        }
    }
    if (onBack != null) {
        val commit by rememberUpdatedState(onBack)
        // With nothing of its own to go back to, a stage inside another lets the outer one preview the way out.
        PBackHandler(true,
            onProgress = { event -> if (!gesture.hold(event.progress)) outer?.hold(event.progress) },
            onCancel = { gesture.cancel(); outer?.cancel() }) {
            commit()
            gesture.committed(); outer?.committed()
        }
    }
    val background = Pocket.colors.bg
    val hairline = Pocket.colors.line
    Box(modifier.fillMaxSize()) {
        val from = state.from?.takeIf { it != state.to }
        val to = state.to
        val pages = if (from != null) listOf(from, to) else listOf(to)
        pages.forEach { shown ->
            key(shown) {
                val arriving = shown == to
                val active = shown == state.target
                // The page that covers the other is the one arriving on a push and the one leaving on a pop.
                val above = from == null || (arriving != (state.move == PStageMove.Pop))
                val morphPage = remember { PMorphPage(state.sources) }
                // A page that is being come back to shows itself as it was left, without arriving again.
                val returning = remember { arriving && from != null && state.move == PStageMove.Pop }
                Box(Modifier.fillMaxSize().zIndex(if (above) 1f else 0f).graphicsLayer {
                    if (from == null) return@graphicsLayer
                    val at = state.progress.value.coerceIn(0f, 1f)
                    val shift = if (arriving) 1f - at else at
                    val morph = state.morph
                    when (state.move) {
                        PStageMove.Push, PStageMove.Pop -> when {
                            morph == null -> translationX = if (above) shift * size.width else -shift * size.width * Parallax
                            above -> {
                                // The page is fitted to the width of its frame and shows as much of its height as the frame has.
                                val grown = 1f - shift
                                val frame = pocketMorphFrame(morph.resolve(state.sources), size.width, size.height, grown)
                                val fit = (frame.width / size.width).coerceAtLeast(.01f)
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = fit; scaleY = fit; translationX = frame.left; translationY = frame.top
                                alpha = GlyphMotion.window(grown, 0f, MorphFade)
                                shape = PMorphShape(frame.height / fit, morph.radius * (1f - grown) / fit); clip = true
                                shadowElevation = 20.dp.toPx() * GlyphMotion.pulse(grown)
                            }
                            else -> { val scale = 1f - MorphRecede * shift; scaleX = scale; scaleY = scale }
                        }
                        PStageMove.Forward, PStageMove.Backward -> {
                            val direction = if (state.move == PStageMove.Forward) 1f else -1f
                            translationX = (if (arriving) direction else -direction) * shift * (if (arriving) 26.dp else 18.dp).toPx()
                            alpha = if (arriving) GlyphMotion.window(at, .18f, .9f) else 1f - GlyphMotion.window(at, 0f, .5f)
                        }
                        PStageMove.Fade -> {
                            alpha = if (arriving) GlyphMotion.window(at, .1f, .9f) else 1f - GlyphMotion.window(at, 0f, .55f)
                            val scale = if (arriving) .975f + .025f * at else 1f
                            scaleX = scale; scaleY = scale
                        }
                    }
                }.testTag(if (active) "stage-page" else "stage-page-leaving")) {
                    Box(Modifier.fillMaxSize().onPlaced { morphPage.coordinates = it }.background(background)
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                        })) { CompositionLocalProvider(LocalPStageGesture provides gesture, LocalPMorphPage provides morphPage,
                            LocalPocketEntranceQuiet provides returning) { content(shown) } }
                    val stacked = from != null && (state.move == PStageMove.Push || state.move == PStageMove.Pop)
                    // The page beneath dims as it is covered; the page above casts a soft edge onto it.
                    if (stacked && !above) Box(Modifier.matchParentSize().graphicsLayer {
                        val at = state.progress.value.coerceIn(0f, 1f)
                        alpha = (if (arriving) 1f - at else at) * Dim
                    }.background(Color.Black))
                    // A page opening out of a row keeps a hairline round it until it fills the stage, so its edge reads on any background.
                    if (stacked && above && state.morph != null) Box(Modifier.matchParentSize().drawBehind {
                        val morph = state.morph ?: return@drawBehind
                        val at = state.progress.value.coerceIn(0f, 1f)
                        val grown = if (arriving) at else 1f - at
                        val edge = 1f - GlyphMotion.window(grown, .7f, 1f)
                        if (edge <= 0f) return@drawBehind
                        val frame = pocketMorphFrame(morph.rect, size.width, size.height, grown)
                        val fit = (frame.width / size.width).coerceAtLeast(.01f)
                        drawRoundRect(hairline.copy(alpha = hairline.alpha * edge), Offset.Zero, Size(size.width, frame.height / fit),
                            CornerRadius(morph.radius * (1f - grown) / fit), style = Stroke(2.dp.toPx() / fit))
                    })
                    if (stacked && above && state.morph == null) Box(Modifier.offset(x = (-18).dp).width(18.dp).fillMaxHeight().graphicsLayer {
                        val at = state.progress.value.coerceIn(0f, 1f)
                        alpha = GlyphMotion.pulse(if (arriving) at else 1f - at).coerceAtMost(1f)
                    }.background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .10f)))))
                }
            }
        }
    }
}

/** How far the covered page lags behind the one sliding over it. */
private const val Parallax = .28f
private const val Dim = .14f
/** How much the page beneath draws back while another opens out of one of its rows. */
private const val MorphRecede = .05f
/** The share of the way over which a page opening out of a row becomes opaque. */
private const val MorphFade = .22f
