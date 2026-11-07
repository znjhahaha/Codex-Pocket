package dev.codexpocket.app.ui

import java.net.URI
import java.net.URLDecoder

internal data class ReviewLine(val index: Int, val text: String, val oldLine: Int? = null, val newLine: Int? = null, val hunk: Boolean = false) {
    val context: Boolean get() = text.startsWith(' ') && oldLine != null && newLine != null
}
internal data class VisibleReviewLine(val row: ReviewLine, val hiddenCount: Int = 0)

/** Counts in the hunk, not visual row indices, decide whether a row has a comment target. */
internal fun parseReviewLines(content: String, diff: Boolean): List<ReviewLine> {
    val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*")
    var old = 0; var new = 0; var oldLeft = 0; var newLeft = 0
    return content.lines().mapIndexed { index, text ->
        if (!diff) return@mapIndexed ReviewLine(index, text, newLine = index + 1)
        val match = header.matchEntire(text)
        if (text.startsWith("@@") || text.startsWith("diff --git")) {
            oldLeft = 0; newLeft = 0
            if (match != null) {
                val a = match.groupValues[1].toIntOrNull()
                val b = match.groupValues[3].toIntOrNull()
                val ac = match.groupValues[2].ifEmpty { "1" }.toIntOrNull()
                val bc = match.groupValues[4].ifEmpty { "1" }.toIntOrNull()
                if (a != null && b != null && ac != null && bc != null && a.toLong() + ac <= Int.MAX_VALUE && b.toLong() + bc <= Int.MAX_VALUE) {
                    old = a; new = b; oldLeft = ac; newLeft = bc
                }
            }
            return@mapIndexed ReviewLine(index, text, hunk = match != null)
        }
        when {
            text.startsWith('-') && oldLeft > 0 -> ReviewLine(index, text, oldLine = old++).also { oldLeft-- }
            text.startsWith('+') && newLeft > 0 -> ReviewLine(index, text, newLine = new++).also { newLeft-- }
            text.startsWith(' ') && oldLeft > 0 && newLeft > 0 -> ReviewLine(index, text, old++, new++).also { oldLeft--; newLeft-- }
            else -> ReviewLine(index, text)
        }
    }
}

internal fun foldReviewLines(rows: List<ReviewLine>, expanded: Set<Int>): List<VisibleReviewLine> = buildList {
    var index = 0
    while (index < rows.size) {
        val start = index
        if (!rows[index].context) { add(VisibleReviewLine(rows[index++])); continue }
        while (index < rows.size && rows[index].context) index++
        val length = index - start
        if (length <= 8 || rows[start + 3].index in expanded) {
            for (i in start until index) add(VisibleReviewLine(rows[i]))
        } else {
            for (i in start until start + 3) add(VisibleReviewLine(rows[i]))
            add(VisibleReviewLine(rows[start + 3], length - 6))
            for (i in index - 3 until index) add(VisibleReviewLine(rows[i]))
        }
    }
}

internal class SourceLineIndex(val source: String) {
    val starts: IntArray = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }.toIntArray()
    val size: Int get() = starts.size
    fun end(index: Int): Int = (starts.getOrNull(index + 1)?.minus(1) ?: source.length).let { if (it > starts[index] && source[it - 1] == '\r') it - 1 else it }
    fun line(index: Int): String = source.substring(starts[index], end(index))
    fun matchingLines(query: String): List<Int> = if (query.isEmpty()) emptyList() else starts.indices.filter { line(it).contains(query, ignoreCase = true) }
    fun jumpIndex(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..size }?.minus(1)
}

private val grammarLanguages = setOf("clike", "c", "cpp", "csharp", "css", "dart", "go", "java", "javascript", "json", "kotlin", "markdown", "markup", "python", "sql", "swift", "yaml")
internal fun normalizeCodeLanguage(language: String?): String? {
    val value = language.orEmpty().trim().substringBefore(' ').lowercase()
    return when (value) { "js" -> "javascript"; "py" -> "python"; "kt", "kts" -> "kotlin"; "html", "xml", "svg" -> "markup"; "yml" -> "yaml"; "md" -> "markdown"; "cs" -> "csharp"; "h" -> "c"; "cc", "hpp" -> "cpp"; else -> value }.takeIf { it in grammarLanguages }
}
internal fun sourceLanguage(path: String) = normalizeCodeLanguage(path.substringAfterLast('.', ""))

/** Resolve only a literal relative file reference, with no URI dispatch or traversal outside root. */
internal fun resolveWorkspaceLink(sourcePath: String, href: String, workspaceRoot: String? = null): String? {
    val uri = runCatching { URI(href) }.getOrNull() ?: return null
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null) return null
    val raw = uri.rawPath?.takeIf(String::isNotEmpty) ?: return null
    val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    if (decoded.startsWith('/') || decoded.any { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.isISOControl() }) return null
    val source = sourcePath.replace('\\', '/')
    val root = workspaceRoot?.replace('\\', '/')?.trimEnd('/')?.takeIf(String::isNotEmpty)
    val absolute = source.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(source)
    val relative = if (absolute) {
        val windows = Regex("^[A-Za-z]:/").containsMatchIn(source)
        if (root == null || !source.startsWith("$root/", ignoreCase = windows)) return null
        source.substring(root.length + 1)
    } else source
    val components = relative.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    if (components.any { it == ".." || it == "." }) return null
    for (part in decoded.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (components.isEmpty()) return null else components.removeAt(components.lastIndex)
        else -> components += part
    }
    if (components.isEmpty()) return null
    val result = components.joinToString("/")
    return if (absolute) "$root/$result" else result
}
