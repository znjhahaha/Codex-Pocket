package dev.codexpocket.wire
import org.junit.Assert.*
import org.junit.Test
class RelayVersionTest {
    @Test fun versionRequiresExactAuthorizedHandshakeFields() {
        assertNull(RelayVersion.parse(null, "1", null))
        assertNull(RelayVersion.parse("0.1.0", "2", null))
        assertNull(RelayVersion.parse("secret/path", "1", null))
        assertEquals(RelayVersion("0.1.0", 1, "a".repeat(40)), RelayVersion.parse("0.1.0", "1", "a".repeat(40)))
        assertNull(RelayVersion.parse("0.1.0", "1", "credential")!!.build)
    }
}
