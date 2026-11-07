package dev.codexpocket.app.data

data class MentionedFile(val name: String, val path: String, val image: Boolean)
data class UserMessage(val request: String, val files: List<MentionedFile>)

/**
 * Codex desktop prefixes a user turn that carries attachments with a Markdown wrapper:
 *
 * ```
 * # Files mentioned by the user:
 *
 * ## name.png: C:\path\name.png
 * Image attachment: true
 * ...
 * ## My request:
 * the text the user typed
 * ```
 *
 * The wrapper is model context, not the user's words. Pocket shows the request and the files separately.
 */
object UserMessageParsing {
    private const val FILES_HEADER = "# Files mentioned by the user:"
    private const val REQUEST_HEADER = "## My request:"

    fun parse(text: String): UserMessage {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(FILES_HEADER)) return UserMessage(text, emptyList())
        val requestAt = trimmed.lastIndexOf("\n$REQUEST_HEADER")
        if (requestAt < 0) return UserMessage(text, emptyList())
        val request = trimmed.substring(requestAt + REQUEST_HEADER.length + 1).trim('\n', '\r', ' ')
        val files = mutableListOf<MentionedFile>()
        trimmed.substring(FILES_HEADER.length, requestAt).lineSequence().forEach { line ->
            when {
                line.startsWith("## ") -> {
                    val body = line.removePrefix("## ").trim()
                    // "name: path". A Windows drive letter has its own colon, so split at the first ": ".
                    val split = body.indexOf(": ")
                    val name = if (split > 0) body.substring(0, split).trim() else body
                    val path = if (split > 0) body.substring(split + 2).trim() else ""
                    if (name.isNotBlank()) files += MentionedFile(name, path, false)
                }
                line.trim().equals("Image attachment: true", ignoreCase = true) && files.isNotEmpty() ->
                    files[files.lastIndex] = files.last().copy(image = true)
            }
        }
        return UserMessage(request, files.distinctBy { it.path.ifBlank { it.name } })
    }
}
