package dev.codexpocket.app.ui

/** Draft contents, attachments and running turns deliberately do not affect the layout. */
internal fun composerIsCompact(readingHistory: Boolean, focused: Boolean, expanded: Boolean,
    menuOpen: Boolean, keyboardVisible: Boolean): Boolean =
    readingHistory && !focused && !expanded && !menuOpen && !keyboardVisible

internal fun composerWidthDp(availableWidth: Float, compact: Boolean): Float =
    if (compact) minOf(availableWidth * .84f, 480f) else minOf(availableWidth, 720f)
