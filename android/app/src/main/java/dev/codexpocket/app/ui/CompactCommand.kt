package dev.codexpocket.app.ui

/** Preserve the native invocation's start. Only native structured actions may replace it with prose. */
internal fun compactCommandLabel(command: String): String =
    command.lineSequence().map(String::trim).firstOrNull(String::isNotBlank) ?: "命令"
