package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** All ranges are UTF-16 offsets in DISPLAY text, matching TextFieldValue and Android IME. */
data class ReferenceSpan(val start: Int, val end: Int, val displayName: String, val reference: JsonObject)
data class ReferenceDraft(val text: String, val spans: List<ReferenceSpan>)
data class ReferenceInsertion(val text: String, val caret: Int, val spans: List<ReferenceSpan>)
data class PreparedReferenceInputs(val text: String, val references: List<JsonObject>)
/** A native editor replacement in BEFORE text coordinates, never a range inferred from repeated text. */
data class ReferenceTextEdit(val start: Int, val endExclusive: Int, val insertedText: String)

fun referenceTextEdit(before: String, after: String, start: Int, endExclusive: Int): ReferenceTextEdit? {
    if (start < 0 || endExclusive < start || endExclusive > before.length) return null
    val insertedLength = after.length - before.length + endExclusive - start
    if (insertedLength < 0 || start + insertedLength > after.length) return null
    val edit = ReferenceTextEdit(start, endExclusive, after.substring(start, start + insertedLength))
    return edit.takeIf { before.replaceRange(start, endExclusive, it.insertedText) == after }
}

private fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
fun referenceDisplayName(reference: JsonObject): String? = reference.field("displayName").ifBlank { reference.field("name") }
    .takeIf { it.isNotBlank() && it.none(Char::isISOControl) }

/** Treat conflicting/overlapping records as unverified, never guess which one the user selected. */
fun validReferenceSpans(text: String, spans: List<ReferenceSpan>): List<ReferenceSpan> {
    val valid = spans.filter { span ->
        span.start >= 0 && span.end > span.start && span.end <= text.length &&
            referenceToken(span.reference) != null && referenceDisplayName(span.reference) == span.displayName &&
            text.substring(span.start, span.end) == span.displayName
    }.sortedBy { it.start }
    return valid.filter { span -> valid.count { it.start < span.end && it.end > span.start } == 1 }
}

/** Rebase one editor mutation; any replacement/deletion through a reference revokes its identity.
 * Text-only clipboard paste does not carry references. Equal text (selection/composition changes) is a no-op.
 */
fun rebaseReferenceSpans(before: String, after: String, spans: List<ReferenceSpan>, edit: ReferenceTextEdit? = null): List<ReferenceSpan> {
    val valid = validReferenceSpans(before, spans)
    if (edit != null) {
        if (referenceTextEdit(before, after, edit.start, edit.endExclusive) != edit) return emptyList()
        if (edit.start == edit.endExclusive && edit.insertedText.isEmpty()) return valid
        return rebaseAtEdit(before, after, valid, edit.start, edit.endExclusive)
    }
    if (before == after) return valid
    var start = 0
    while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
    var oldEnd = before.length
    var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
    // Prefix-first diff chooses one of several equal copies. The suffix-first diff can choose another.
    // Revoke references touched by ANY possible minimal insertion/deletion, rather than transferring proof.
    var suffix = 0
    while (suffix < minOf(before.length, after.length) && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
    val earliest = minOf(before.length, after.length) - suffix
    val ambiguous = earliest < start
    val removed = (before.length - after.length).coerceAtLeast(0)
    val safe = if (!ambiguous) valid else valid.filter { span ->
        if (removed > 0) span.end <= earliest || span.start >= start + removed
        else span.end <= earliest || span.start >= start
    }
    return rebaseAtEdit(before, after, safe, start, oldEnd)
}

private fun rebaseAtEdit(before: String, after: String, spans: List<ReferenceSpan>, start: Int, oldEnd: Int): List<ReferenceSpan> {
    val delta = after.length - before.length
    return validReferenceSpans(after, spans.mapNotNull { span -> when {
        span.end <= start -> span
        span.start >= oldEnd -> span.copy(start = span.start + delta, end = span.end + delta)
        else -> null
    } })
}

/** Parent calls after checking current catalog/runtime/generation, persists text + encoded spans atomically. */
fun insertVerifiedReference(current: String, expected: String, start: Int, endExclusive: Int,
    reference: JsonObject, spans: List<ReferenceSpan> = emptyList()): ReferenceInsertion? {
    referenceToken(reference) ?: return null
    val name = referenceDisplayName(reference) ?: return null
    val edit = replaceReferenceAt(current, expected, start, endExclusive, name) ?: return null
    return ReferenceInsertion(edit.text, edit.caret, rebaseReferenceSpans(current, edit.text, spans,
        ReferenceTextEdit(start, endExclusive, edit.text.substring(start, edit.caret))) +
        ReferenceSpan(start, start + name.length, name, reference))
}

/** draft.references remains a JSON array. A versioned nested record cannot be mistaken for legacy input. */
fun encodeReferenceSpans(spans: List<ReferenceSpan>): String = JsonArray(spans.map { span -> buildJsonObject {
    put("version", 1); put("start", span.start); put("end", span.end); put("displayName", span.displayName)
    put("reference", span.reference)
} }).toString()

private fun records(json: String): List<JsonObject> = runCatching {
    (Json.parseToJsonElement(json) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}.getOrDefault(emptyList())

/** Backward compatibility requires explicit stored legacy metadata AND one bounded token occurrence.
 * A legacy text-only draft, corrupt record or ambiguous repeated token stays ordinary text.
 * Parent should persist the returned display text/spans together when opening an old draft.
 */
fun restoreReferenceDraft(text: String, json: String): ReferenceDraft {
    val entries = records(json)
    val spans = entries.mapNotNull { entry ->
        if ((entry["version"] as? JsonPrimitive)?.intOrNull != 1) return@mapNotNull null
        val reference = entry["reference"] as? JsonObject ?: return@mapNotNull null
        val start = (entry["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val end = (entry["end"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        ReferenceSpan(start, end, entry.field("displayName"), reference)
    }
    val legacy = entries.filter { "version" !in it && it.field("token").isNotBlank() && referenceToken(it) == it.field("token") }
    // Never mix display offsets with wire offsets from a corrupt/mixed schema.
    return if (entries.any { "version" in it }) ReferenceDraft(text, validReferenceSpans(text, spans))
    else decorateNativeTokens(text, legacy)
}

/** No substring-token authorization: only current valid spans generate native skill/mention inputs.
 * Parent must still revalidate catalog/runtime authorization before sending. Files are wire text only.
 */
fun prepareReferenceInputs(text: String, spans: List<ReferenceSpan>): PreparedReferenceInputs {
    val valid = validReferenceSpans(text, spans)
    val wire = StringBuilder(text)
    valid.asReversed().forEach { wire.replace(it.start, it.end, referenceToken(it.reference)!!) }
    val native = valid.mapNotNull { span ->
        val reference = span.reference
        reference.field("type").takeIf { it == "skill" || it == "mention" }?.let { type -> buildJsonObject {
            put("type", type); put("name", reference.field("name")); put("path", reference.field("path"))
        } }
    }.distinct()
    return PreparedReferenceInputs(wire.toString(), native)
}

/** Sent-message identity comes ONLY from raw native content of type skill/mention. */
fun sentReferenceDraft(text: String, raw: JsonObject): ReferenceDraft = decorateNativeTokens(text,
    (raw["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it.field("type") in setOf("skill", "mention") })

private fun decorateNativeTokens(text: String, references: List<JsonObject>): ReferenceDraft {
    val matches = references.distinct().mapNotNull { reference ->
        val token = referenceToken(reference) ?: return@mapNotNull null
        val name = referenceDisplayName(reference) ?: return@mapNotNull null
        val offsets = mutableListOf<Int>()
        var from = 0
        while (from < text.length) {
            val start = text.indexOf(token, from)
            if (start < 0) break
            val end = start + token.length
            if ((start == 0 || !text[start - 1].let { it.isLetterOrDigit() || it in "_$@" }) &&
                (end == text.length || !text[end].let { it.isLetterOrDigit() || it in "_-/$@" })) offsets += start
            from = end
        }
        offsets.singleOrNull()?.let { ReferenceSpan(it, it + token.length, name, reference) }
    }.sortedBy { it.start }
    val unique = matches.filter { span -> matches.count { it.start < span.end && it.end > span.start } == 1 }
    val output = StringBuilder(); val spans = mutableListOf<ReferenceSpan>(); var cursor = 0
    unique.forEach { span ->
        output.append(text, cursor, span.start)
        val start = output.length
        output.append(span.displayName)
        spans += span.copy(start = start, end = output.length)
        cursor = span.end
    }
    output.append(text, cursor, text.length)
    return ReferenceDraft(output.toString(), spans)
}

/** One visual icon slot before each display name. Cursor/selection remain in unchanged display text. */
class ReferenceOffsetMap(private val textLength: Int, spans: List<ReferenceSpan>) {
    private val starts = spans.sortedBy { it.start }.map { it.start }
    fun originalToTransformed(offset: Int): Int {
        val bounded = offset.coerceIn(0, textLength)
        return bounded + starts.count { it <= bounded }
    }
    fun transformedToOriginal(offset: Int): Int {
        val bounded = offset.coerceIn(0, textLength + starts.size)
        return (bounded - starts.withIndex().count { (index, start) -> start + index < bounded }).coerceIn(0, textLength)
    }
}
