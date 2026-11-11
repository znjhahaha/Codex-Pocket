package dev.codexpocket.app.ui

import dev.codexpocket.app.data.PocketState

/** Directory refresh is independent of transport recovery. Terminal auth errors never spin. */
internal val PocketState.restoringConnection: Boolean
    get() = paired && !online && connectionPhase in setOf("connecting", "authenticating", "syncing", "recovering")
