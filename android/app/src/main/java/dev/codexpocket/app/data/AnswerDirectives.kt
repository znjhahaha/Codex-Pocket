package dev.codexpocket.app.data

/** An answer with Codex desktop directives (`::codex-file-citation{...}` and the like) taken out of the prose. */
data class AnswerContent(val text: String, val files: List<MentionedFile>)

/**
 * The desktop renders `:codex-*{key="value"}` directives as chips or hides them. Markdown would
 * show them as raw text, so strip them outside code fences and keep cited files for chips.
 */
object AnswerDirectives {
    private val directive = Regex(""":{1,3}codex-([a-z0-9-]+)\{([^}\n]*)\}""")
    private val attribute = Regex("""([A-Za-z_][\w-]*)\s*=\s*"([^"]*)"""")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic")

    fun parse(text: String): AnswerContent {
        if (!text.contains("codex-")) return AnswerContent(text, emptyList())
        val files = mutableListOf<MentionedFile>()
        val seen = mutableSetOf<String>()
        var fence: String? = null
        val lines = text.split('\n').mapNotNull { line ->
            val trimmed = line.trimStart()
            val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
            if (marker != null) {
                if (fence == null) fence = marker else if (marker.startsWith(fence!!)) fence = null
                return@mapNotNull line
            }
            if (fence != null || !line.contains("codex-")) return@mapNotNull line
            var found = false
            val stripped = directive.replace(line) { match ->
                found = true
                if (match.groupValues[1] == "file-citation") {
                    val attrs = attribute.findAll(match.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                    val path = attrs["path"].orEmpty().trim()
                    if (path.isNotEmpty() && seen.add(path)) {
                        val name = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                        files += MentionedFile(name, path, name.substringAfterLast('.', "").lowercase() in imageExtensions)
                    }
                }
                ""
            }
            // A line that held only directives disappears instead of leaving a blank paragraph.
            if (found && stripped.isBlank()) null else stripped
        }
        // Extra blank lines left behind are harmless: Markdown folds them into one paragraph break.
        return AnswerContent(lines.joinToString("\n").trim('\n'), files)
    }
}
