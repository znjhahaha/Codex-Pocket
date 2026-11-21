package dev.codexpocket.app.data

import java.util.Locale

/** Compare native roots without changing the path sent to the executing computer. */
internal fun nativeProjectPathKey(path: String): String {
    val slashes = path.replace('\\', '/')
    val ordinary = when {
        slashes.startsWith("//?/UNC/", ignoreCase = true) -> "//" + slashes.substring(8)
        slashes.startsWith("//?/") && Regex("^[A-Za-z]:/").containsMatchIn(slashes.substring(4)) -> slashes.substring(4)
        else -> slashes
    }
    val windows = Regex("^[A-Za-z]:/").containsMatchIn(ordinary) || ordinary.startsWith("//")
    if (windows) return ordinary.trimEnd('/').lowercase(Locale.ROOT)
    return path.trimEnd('/').ifEmpty { if (path.startsWith('/')) "/" else "" }
}
