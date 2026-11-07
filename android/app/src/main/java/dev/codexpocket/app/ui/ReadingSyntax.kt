package dev.codexpocket.app.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import io.noties.markwon.syntax.Prism4jSyntaxHighlight
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.prism4j.Prism4j
import io.noties.prism4j.annotations.PrismBundle

@PrismBundle(
    include = ["clike", "c", "cpp", "csharp", "css", "dart", "go", "java", "javascript", "json", "kotlin", "markdown", "markup", "python", "sql", "swift", "yaml"],
    grammarLocatorClassName = ".ReadingGrammarLocator",
)
internal class ReadingSyntax(foreground: Int, keyword: Int, literal: Int) {
    private val highlight = Prism4jSyntaxHighlight.create(Prism4j(ReadingGrammarLocator()), object : Prism4jTheme {
        override fun background() = android.graphics.Color.TRANSPARENT
        override fun textColor() = foreground
        override fun apply(language: String, syntax: Prism4j.Syntax, builder: SpannableStringBuilder, start: Int, end: Int) {
            val color = when (syntax.type()) {
                "keyword", "atrule", "function", "class-name", "tag", "property" -> keyword
                "string", "char", "number", "boolean", "constant", "symbol", "attr-value" -> literal
                "comment", "prolog", "doctype", "cdata" -> (foreground and 0x00FFFFFF) or 0x99000000.toInt()
                else -> return
            }
            builder.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    })

    // Renderer dispatcher serializes grammar creation and tokenization. No guessed fallback language.
    fun spans(language: String?, code: String): CharSequence {
        val known = normalizeCodeLanguage(language) ?: return code
        if (code.length > 256 * 1024) return code
        return try { highlight.highlight(known, code) } catch (_: RuntimeException) { code } catch (_: StackOverflowError) { code }
    }
}

internal fun CharSequence.codeAnnotation(): AnnotatedString {
    val builder = AnnotatedString.Builder(toString())
    if (this is Spanned) getSpans(0, length, ForegroundColorSpan::class.java).forEach {
        val start = getSpanStart(it).coerceIn(0, length)
        val end = getSpanEnd(it).coerceIn(start, length)
        if (end > start) builder.addStyle(SpanStyle(color = Color(it.foregroundColor)), start, end)
    }
    return builder.toAnnotatedString()
}
