package dev.codexpocket.wire

/** Taken only from this connection's authorized WSS upgrade; never inferred from the APK. */
data class RelayVersion(val version: String, val protocol: Int, val build: String?) {
    companion object {
        fun parse(version: String?, protocol: String?, build: String?): RelayVersion? {
            if (version == null || version.length > 96 || !version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) return null
            if (protocol != "1") return null
            val safeBuild = build?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) }
            return RelayVersion(version, 1, safeBuild)
        }
    }
}
