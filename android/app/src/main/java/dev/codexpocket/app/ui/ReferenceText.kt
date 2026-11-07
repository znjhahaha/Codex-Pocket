package dev.codexpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.codexpocket.app.data.*

internal val ReferenceBlue = Color(0xFF3078D0)
internal fun referenceIcon(type: String) = when (type) {
    "skill" -> PocketIcons.Cube
    "mention" -> PocketIcons.Link
    "directory" -> PocketIcons.Folder
    else -> PocketIcons.Description
}

/** Rendering adds icon slots only. The actual TextFieldValue never changes or loses its IME composition. */
internal class ReferenceVisualTransformation(private val spans: List<ReferenceSpan>, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val valid = validReferenceSpans(text.text, spans)
        val offsets = ReferenceOffsetMap(text.length, valid)
        val annotated = buildAnnotatedString {
            var cursor = 0
            valid.forEach { span ->
                append(text.subSequence(cursor, span.start))
                pushStyle(SpanStyle(color = color))
                append('\u2003') // An em-sized space is painted with the actual outline vector glyph.
                append(text.subSequence(span.start, span.end))
                pop()
                cursor = span.end
            }
            append(text.subSequence(cursor, text.length))
        }
        return TransformedText(annotated, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offsets.originalToTransformed(offset)
            override fun transformedToOriginal(offset: Int) = offsets.transformedToOriginal(offset)
        })
    }
}

/** Keep this editor inside ONE outer verticalScroll, unconstrained in its text direction.
 * The vector drawing and hit testing therefore share CoreTextField's layout/scroll coordinates.
 * Short taps reveal metadata; drags/long presses still belong to the native text selection/IME editor.
 */
@Composable
internal fun ReferenceEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    spans: List<ReferenceSpan>, modifier: Modifier = Modifier, minLines: Int = 1, readOnly: Boolean = false,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
    onMetadataOpenChange: (Boolean) -> Unit = {}) {
    val valid = remember(value.text, spans) { validReferenceSpans(value.text, spans) }
    val transformed = remember(valid) { ReferenceVisualTransformation(valid, ReferenceBlue) }
    val renderedText = remember(value.annotatedString, transformed) { transformed.filter(value.annotatedString).text.text }
    val currentRenderedText by rememberUpdatedState(renderedText)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var selected by remember { mutableStateOf<ReferenceSpan?>(null) }
    val painters = valid.map { rememberVectorPainter(referenceIcon(it.reference.string("type"))) }
    val currentSpans by rememberUpdatedState(valid)
    val reportMetadata by rememberUpdatedState(onMetadataOpenChange)
    val inputActive by rememberUpdatedState(LocalPocketInputActive.current)
    val closeMetadata: () -> Unit = { selected = null; reportMetadata(false) }
    LaunchedEffect(valid) { if (selected != null && selected !in valid) closeMetadata() }
    DisposableEffect(Unit) { onDispose { if (selected != null) reportMetadata(false) } }
    BasicTextField(value, onValueChange, modifier.onGloballyPositioned { anchor = it.boundsInRoot() }
        .drawWithContent {
            drawContent()
            val result = layout ?: return@drawWithContent
            if (result.layoutInput.text.text != renderedText) return@drawWithContent
            valid.forEachIndexed { index, span ->
                val box = result.getBoundingBox(span.start + index)
                val size = minOf(box.width, box.height) * .85f
                translate(box.left + (box.width - size) / 2, box.top + (box.height - size) / 2) {
                    with(painters[index]) { draw(Size(size, size), colorFilter = ColorFilter.tint(ReferenceBlue)) }
                }
            }
        }.pointerInput(readOnly) {
            if (readOnly) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Sample at event time: a nested modal may cover us before our recomposition.
                if (!inputActive() || selected != null) return@awaitEachGesture
                val result = layout?.takeIf { it.layoutInput.text.text == currentRenderedText }
                val hit = result?.let { textLayout ->
                    val offset = textLayout.getOffsetForPosition(down.position)
                    currentSpans.withIndex().firstOrNull { (index, span) ->
                        offset in (span.start + index)..(span.end + index) &&
                            textLayout.getBoundingBox(offset).contains(down.position)
                    }?.value
                }
                if (hit != null) {
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null && inputActive() && hit in currentSpans && selected == null &&
                        up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        up.consume(); selected = hit; reportMetadata(true)
                    }
                }
            }
        }, minLines = minLines, singleLine = false, readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary), visualTransformation = transformed,
        onTextLayout = { layout = it }, decorationBox = decorationBox)
    selected?.let { ReferenceMetadata(it, anchor, closeMetadata) }
}

/** Read-only display for the separate history preview and for a parent SelectionContainer. */
@Composable
internal fun ReferenceText(text: String, spans: List<ReferenceSpan>, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, color: Color = MaterialTheme.colorScheme.onSurface) {
    val valid = remember(text, spans) { validReferenceSpans(text, spans) }
    var selected by remember { mutableStateOf<ReferenceSpan?>(null) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val inputActive by rememberUpdatedState(LocalPocketInputActive.current)
    val openMetadata: (ReferenceSpan) -> Unit = { if (inputActive() && selected == null) selected = it }
    LaunchedEffect(valid) { if (selected !in valid) selected = null }
    val annotated = buildAnnotatedString {
        var cursor = 0
        valid.forEachIndexed { index, span ->
            append(text.substring(cursor, span.start))
            appendInlineContent("reference:$index", " ")
            withLink(LinkAnnotation.Clickable("reference:$index", TextLinkStyles(SpanStyle(color = ReferenceBlue))) { openMetadata(span) }) {
                append(span.displayName)
            }
            cursor = span.end
        }
        append(text.substring(cursor))
    }
    val inline = valid.mapIndexed { index, span -> "reference:$index" to InlineTextContent(
        Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize().clickable { openMetadata(span) }, contentAlignment = Alignment.Center) {
            Icon(referenceIcon(span.reference.string("type")), "查看${span.displayName}", Modifier.fillMaxSize().padding(1.dp), tint = ReferenceBlue)
        }
    } }.toMap()
    Text(annotated, modifier.onGloballyPositioned { anchor = it.boundsInRoot() }, inlineContent = inline,
        style = MaterialTheme.typography.bodyLarge, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    selected?.let { ReferenceMetadata(it, anchor) { selected = null } }
}

/** Transcript integration: supply the item's raw native content, never a parsed legacy text identity. */
@Composable
internal fun SentReferenceText(text: String, raw: kotlinx.serialization.json.JsonObject, modifier: Modifier = Modifier) {
    val display = remember(text, raw) { sentReferenceDraft(text, raw) }
    ReferenceText(display.text, display.spans, modifier)
}

@Composable
private fun ReferenceMetadata(span: ReferenceSpan, anchor: Rect?, onDismiss: () -> Unit) {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val below = anchor != null && anchor.top < screenHeight * .45f
    PocketAnchoredMenu("引用详情", onDismiss, anchor, widthDp = 288f, below = below) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("reference-metadata"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(referenceIcon(span.reference.string("type")), null, Modifier.size(18.dp), tint = ReferenceBlue)
                Text(span.displayName, style = MaterialTheme.typography.titleSmall, color = ReferenceBlue)
            }
            listOf("description", "source", "path").map { span.reference.string(it) }.filter { it.isNotBlank() }.distinct().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
