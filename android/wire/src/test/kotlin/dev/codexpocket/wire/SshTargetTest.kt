package dev.codexpocket.wire

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SshTargetTest {
    private val target = SshTarget("example.com", 22, "alice", "SHA256:" + "A".repeat(43), SshAuthentication.Password("secret-password"), 43821)
    @Test fun savedCredentialsRoundTripButNeverAppearInDiagnosticStrings() {
        val restored = SshTarget.parse(target.toJson())
        assertEquals("example.com", restored.host)
        assertEquals("secret-password", (restored.authentication as SshAuthentication.Password).value)
        assertFalse(target.toString().contains("example.com"))
        assertFalse(target.authentication.toString().contains("secret-password"))
    }
    @Test fun invalidPortsAndUnpinnedHostsCannotBecomeSavedConnections() {
        for (port in listOf(0, 65536, -1)) assertThrows(IllegalArgumentException::class.java) {
            SshTarget(target.host, port, target.username, target.hostKey, target.authentication, 43821)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshTarget(target.host, 22, target.username, "accept-any", target.authentication, 43821)
        }
        for (host in listOf("host\ncommand", "user@host", "../host", "host name")) assertThrows(IllegalArgumentException::class.java) {
            SshTarget(host, 22, target.username, target.hostKey, target.authentication, 43821)
        }
    }
    @Test fun shellPathsCannotTurnIntoCommands() {
        assertEquals("'/home/a b'", shellWord("/home/a b"))
        assertEquals("'a'\"'\"';touch /tmp/wrong'", shellWord("a';touch /tmp/wrong"))
        assertThrows(IllegalArgumentException::class.java) { shellWord("/home/x\ncommand") }
        assertThrows(IllegalArgumentException::class.java) { shellWord("bad\u0000name") }
    }
}
