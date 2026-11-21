package dev.codexpocket.app.data

import org.junit.Assert.*
import org.junit.Test

class NativeProjectPathTest {
    @Test fun extendedDriveAndOrdinaryNativeRootsAreEquivalent() {
        assertEquals(nativeProjectPathKey("D:/Work/项目/"), nativeProjectPathKey("\\\\?\\d:\\work\\项目"))
        assertEquals(nativeProjectPathKey("C:/"), nativeProjectPathKey("\\\\?\\C:\\"))
    }

    @Test fun extendedUncAndOrdinaryShareAreEquivalent() {
        assertEquals(nativeProjectPathKey("\\\\server\\share\\项目"), nativeProjectPathKey("\\\\?\\UNC\\SERVER\\Share\\项目\\"))
    }

    @Test fun distinctRootsAndPosixCaseRemainDistinct() {
        assertNotEquals(nativeProjectPathKey("D:/work"), nativeProjectPathKey("D:/worker"))
        assertNotEquals(nativeProjectPathKey("/work/Project"), nativeProjectPathKey("/work/project"))
        assertNotEquals(nativeProjectPathKey("/"), nativeProjectPathKey(""))
        assertNotEquals(nativeProjectPathKey("/work/a\\b"), nativeProjectPathKey("/work/a/b"))
    }
}
