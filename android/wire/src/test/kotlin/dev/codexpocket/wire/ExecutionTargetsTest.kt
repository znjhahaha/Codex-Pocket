package dev.codexpocket.wire

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionTargetsTest {
    @Test fun importingPairedComputerPreservesNativeIdentityAndIsIdempotent() {
        val first = ExecutionTargetDirectory().withComputer("original-computer", "工作电脑")
        val again = first.withComputer("original-computer", "更新的名称")
        assertEquals(1, again.targets.size)
        assertEquals(first.selectedId, again.selectedId)
        assertEquals("original-computer", again.selected?.endpointId)
        assertEquals("更新的名称", again.selected?.label)
        assertEquals(again, ExecutionTargetDirectory.parse(again.toJson()))
    }

    @Test fun targetSwitchDoesNotChangeBindingsOrLoseOtherLocations() {
        val computer = ExecutionTargetDirectory().withComputer("computer", "电脑")
        val ssh = ExecutionTarget("ssh-id", ExecutionTargetKind.LinuxSsh, "开发机")
        val phone = ExecutionTarget("phone-id", ExecutionTargetKind.ThisPhone, "此手机")
        val catalog = computer.put(ssh).put(phone).bind("ssh-id", "linux-native").select("ssh-id")
        assertEquals("linux-native", catalog.selected?.endpointId)
        assertEquals("computer", catalog.select(computer.selectedId!!).selected?.endpointId)
        assertEquals(3, catalog.targets.size)
        assertNull(catalog.select("phone-id").selected?.endpointId)
        assertThrows(IllegalArgumentException::class.java) { catalog.select("missing") }
        assertThrows(IllegalArgumentException::class.java) { catalog.bind("ssh-id", "different-host") }
    }

    @Test fun sameEndpointCannotAppearUnderTwoLocations() {
        val catalog = ExecutionTargetDirectory().withComputer("host", "电脑")
            .put(ExecutionTarget("ssh-id", ExecutionTargetKind.LinuxSsh, "SSH"))
        assertThrows(IllegalArgumentException::class.java) { catalog.bind("ssh-id", "host") }
    }

    @Test fun removingLocationRetainsOthersAndSelectsAnExistingTarget() {
        val catalog = ExecutionTargetDirectory().withComputer("first", "一")
            .withComputer("second", "二").select("second")
        val next = catalog.remove("second")
        assertEquals("first", next.selectedId)
        assertEquals(listOf("first"), next.targets.map { it.id })
    }

    @Test fun unknownFormatsAreRejectedWithoutSilentlyDroppingSavedLocations() {
        val catalog = ExecutionTargetDirectory().withComputer("computer", "电脑").toJson()
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionTargetDirectory.parse(JsonObject(catalog + ("version" to JsonPrimitive(99))))
        }
        val target = catalog.getValue("targets").jsonArray.single().jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionTargetDirectory.parse(JsonObject(catalog + ("targets" to JsonArray(listOf(
                JsonObject(target + ("kind" to JsonPrimitive("unknown")))
            )))))
        }
    }
}
