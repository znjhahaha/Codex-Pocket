package dev.codexpocket.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Remember only: the pending QR is consumed before calling out and never enters saved UI state. */
internal class PairingScannerState(private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val gate = PairingScanGate()
    private var payload: String? = null
    var computerName by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<ScanDecision?>(null)
        private set

    fun receive(value: String): ScanDecision {
        val now = nowSeconds()
        val decision = gate.accept(value, now)
        if (decision == ScanDecision.Duplicate) return decision
        paused = true
        feedback = decision.takeUnless { it == ScanDecision.Accepted }
        if (decision == ScanDecision.Accepted) {
            payload = value
            computerName = PairingCode.parse(value, now).computerName
        } else {
            payload = null
            computerName = null
        }
        return decision
    }

    fun confirm(deviceName: String, onPair: (String, String) -> Any): ScanDecision {
        val value = payload ?: return ScanDecision.Duplicate
        val decision = validatePairingScan(value, nowSeconds())
        // Clear before invoking onPair, including reentrant/double clicks or an expired result.
        payload = null
        computerName = null
        feedback = decision.takeUnless { it == ScanDecision.Accepted }
        if (decision == ScanDecision.Accepted) onPair(value, deviceName.trim())
        return decision
    }

    fun cancel() {
        payload = null
        computerName = null
        feedback = null
        gate.retry()
        paused = false
    }
}

internal fun scannerShouldRun(permission: Boolean, foreground: Boolean, paused: Boolean, manual: Boolean, busy: Boolean) =
    permission && foreground && !paused && !manual && !busy
