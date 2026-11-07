package dev.codexpocket.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactCommandRegressionTest {
    @Test fun nativeLauncherAndArgumentsStayAtStartOfHeadline() {
        val command = "\"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -Command 'Get-Content README.md'"
        assertEquals(command, compactCommandLabel(command))
    }

    @Test fun multilineInvocationKeepsItsLiteralFirstLine() {
        assertEquals("@'", compactCommandLabel("  @'\nGet-Content README.md\n'@"))
        assertEquals("pwsh -Command", compactCommandLabel("\n  pwsh -Command\r\n'echo test'"))
        assertEquals("命令", compactCommandLabel(" \n\t"))
    }
}
