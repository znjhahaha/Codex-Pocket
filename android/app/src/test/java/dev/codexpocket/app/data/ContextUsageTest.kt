package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContextUsageTest {
    @Test fun cumulativeTokensNeverBecomeContextPercentage() {
        val usage = ContextUsage.from(pocketJson.parseToJsonElement("""{"total":{"totalTokens":900000},"modelContextWindow":100000}""").jsonObject)
        assertNull(usage.fraction)
        assertEquals(900000L, usage.cumulative)
    }
    @Test fun recentInputIsBoundedAndMissingCapacityIsUnknown() {
        assertEquals(.25f, ContextUsage.from(pocketJson.parseToJsonElement("""{"last":{"inputTokens":24000,"totalTokens":25000},"modelContextWindow":100000}""").jsonObject).fraction!!, .001f)
        assertNull(ContextUsage.from(null).fraction)
        assertNull(ContextUsage(1, 0, null).fraction)
    }
}
