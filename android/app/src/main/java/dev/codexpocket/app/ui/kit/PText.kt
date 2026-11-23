package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

@Composable
private fun resolveStyle(style: TextStyle?, color: Color, weight: FontWeight?, align: TextAlign?, size: TextUnit = TextUnit.Unspecified,
    slant: FontStyle? = null, family: FontFamily? = null, tracking: TextUnit = TextUnit.Unspecified, decoration: TextDecoration? = null,
    leading: TextUnit = TextUnit.Unspecified): TextStyle {
    val base = style ?: LocalPTextStyle.current ?: Pocket.type.body
    val ink = if (color != Color.Unspecified) color else if (base.color != Color.Unspecified) base.color else Pocket.ink
    return base.merge(TextStyle(color = ink, fontWeight = weight, textAlign = align ?: TextAlign.Unspecified, fontSize = size, fontStyle = slant,
        fontFamily = family, letterSpacing = tracking, textDecoration = decoration, lineHeight = leading))
}

/** Text in the container's ink and style. Foundation only; the ink follows [ProvidePInk]. */
@Composable
internal fun PText(text: String, modifier: Modifier = Modifier, style: TextStyle? = null, color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null, lineHeight: TextUnit = TextUnit.Unspecified) {
    BasicText(text, modifier, resolveStyle(style, color, fontWeight, textAlign, fontSize, fontStyle, fontFamily, letterSpacing, textDecoration, lineHeight),
        onTextLayout, overflow, softWrap, maxLines, minLines)
}

@Composable
internal fun PText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle? = null, color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null, fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    lineHeight: TextUnit = TextUnit.Unspecified) {
    BasicText(text, modifier, resolveStyle(style, color, fontWeight, textAlign, fontSize, fontStyle, fontFamily, letterSpacing, textDecoration, lineHeight),
        onTextLayout, overflow, softWrap, maxLines, minLines, inlineContent)
}
