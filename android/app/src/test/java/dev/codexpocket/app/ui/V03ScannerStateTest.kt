package dev.codexpocket.app.ui

import org.junit.Assert.*
import org.junit.Test

class V03ScannerStateTest {
    private fun code(expiry: Long = 200) = """{"v":1,"relayUrl":"wss://relay.example/pocket","room":"room_123","phoneToken":"fixture-phone","certificateSha256":"${"ab".repeat(32)}","pairingToken":"fixture-pair","expiresAt":$expiry,"computerName":"Desktop"}"""

    @Test fun invalidAndExpiredNeverBecomeSubmissionCandidates() {
        val gate = PairingScanGate()
        assertEquals(ScanDecision.Invalid, gate.accept("not a QR", 100))
        assertEquals(ScanDecision.Expired, gate.accept(code(100), 100))
        assertEquals(ScanDecision.Accepted, gate.accept(code(), 100))
        assertFalse(gate.toString().contains("fixture"))
    }
    @Test fun duplicateResultsStayBlockedUntilExplicitRetry() {
        val gate = PairingScanGate()
        assertEquals(ScanDecision.Accepted, gate.accept(code(), 100))
        assertEquals(ScanDecision.Duplicate, gate.accept(code(), 101))
        assertEquals(ScanDecision.Duplicate, gate.accept(code(300), 101))
        gate.retry()
        assertEquals(ScanDecision.Accepted, gate.accept(code(300), 101))
    }
    @Test fun expiryIsRecheckedAfterUserReviewsCode() {
        val gate = PairingScanGate()
        assertEquals(ScanDecision.Accepted, gate.accept(code(), 100))
        assertEquals(ScanDecision.Expired, validatePairingScan(code(), 200))
    }
    @Test fun cameraRequiresPermissionForegroundAndVisibleScanner() {
        assertTrue(scannerShouldRun(true, true, false, false, false))
        assertFalse(scannerShouldRun(false, true, false, false, false))
        assertFalse(scannerShouldRun(true, false, false, false, false))
        assertFalse(scannerShouldRun(true, true, true, false, false))
        assertFalse(scannerShouldRun(true, true, false, true, false))
        assertFalse(scannerShouldRun(true, true, false, false, true))
    }
    @Test fun oversizedAndUnsafeCodesAreRejectedWithoutEchoingInput() {
        assertEquals(ScanDecision.Invalid, validatePairingScan("x".repeat(65_537), 100))
        assertEquals(ScanDecision.Invalid, validatePairingScan(code().replace("wss:", "ws:"), 100))
        assertEquals(ScanDecision.Invalid, validatePairingScan(code(90).replace("wss:", "ws:"), 100))
    }
}
