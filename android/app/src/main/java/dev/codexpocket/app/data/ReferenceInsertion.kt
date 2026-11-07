package dev.codexpocket.app.data

import kotlinx.serialization.json.*

private fun JsonObject.referenceValue(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

fun referenceToken(value: JsonObject): String? {
    val name = value.referenceValue("name")
    val path = value.referenceValue("path")
    if (name.isBlank() || path.isBlank() || name.any { it.isISOControl() } || path.any { it.isISOControl() }) return null
    if (listOf("supported", "enabled", "installed").any { key ->
            key in value && (value[key] as? JsonPrimitive)?.booleanOrNull != true }) return null
    val token = when (value.referenceValue("type")) {
        "file", "directory" -> "@$path"
        "skill" -> "$$name"
        "mention" -> "@$name"
        else -> null
    }
    return token?.takeIf { "token" !in value || value.referenceValue("token") == it }
}

data class ReferenceEdit(val text: String, val caret: Int)
fun replaceReferenceAt(current: String, expected: String, start: Int, endExclusive: Int, token: String): ReferenceEdit? {
    if (current != expected || start !in current.indices || endExclusive !in (start + 1)..current.length || current[start] !in "@/") return null
    val suffix = current.substring(endExclusive)
    val inserted = token + if (suffix.startsWith(" ")) "" else " "
    return ReferenceEdit(current.substring(0, start) + inserted + suffix, start + inserted.length)
}
