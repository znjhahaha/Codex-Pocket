package dev.codexpocket.app.ui

import dev.codexpocket.wire.PairingCode

internal enum class ScanDecision { Accepted, Invalid, Expired, Duplicate }

/** The wire validator owns all trust boundaries. Only a valid expired payload gets that label. */
internal fun validatePairingScan(value: String, nowSeconds: Long = System.currentTimeMillis() / 1000): ScanDecision {
    if (value.length > 65_536 || value.isBlank()) return ScanDecision.Invalid
    return try {
        PairingCode.parse(value, nowSeconds)
        ScanDecision.Accepted
    } catch (_: IllegalArgumentException) {
        try {
            val code = PairingCode.parse(value, Long.MIN_VALUE)
            if (code.expiresAt <= nowSeconds) ScanDecision.Expired else ScanDecision.Invalid
        } catch (_: IllegalArgumentException) { ScanDecision.Invalid }
    }
}

/** No payload or digest is retained. A reviewed result requires an explicit retry before scanning. */
internal class PairingScanGate {
    private var accepted = false
    fun accept(value: String, nowSeconds: Long = System.currentTimeMillis() / 1000): ScanDecision {
        if (accepted) return ScanDecision.Duplicate
        return validatePairingScan(value, nowSeconds).also { if (it == ScanDecision.Accepted) accepted = true }
    }
    fun retry() { accepted = false }
}

internal fun scannerShouldRun(permission: Boolean, foreground: Boolean, paused: Boolean, manual: Boolean, busy: Boolean) =
    permission && foreground && !paused && !manual && !busy
