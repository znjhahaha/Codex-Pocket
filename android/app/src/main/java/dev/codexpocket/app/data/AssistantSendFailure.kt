package dev.codexpocket.app.data

import dev.codexpocket.wire.WireException

internal fun assistantSendFailureStatus(dispatched: Boolean, error: Exception): String {
    if (!dispatched) return "rejected"
    // Transport timeouts, lost acknowledgements and unknown server outcomes can
    // follow a committed send. Only explicit admission refusals allow retry.
    val refused = (error as? WireException)?.code in setOf(
        "rejected", "invalid_params", "unsupported", "unauthorized", "revoked", "protocol_mismatch", "version_conflict")
    return if (refused) "rejected" else "unknown"
}
