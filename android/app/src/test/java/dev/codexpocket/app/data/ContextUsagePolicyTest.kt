package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContextUsagePolicyTest {
    @Test fun outputCachedAndCumulativeTokensDoNotInflateLatestInput() {
        val usage = ContextUsage.from(pocketJson.parseToJsonElement("""{"last":{"inputTokens":25000,"cachedInputTokens":20000,"outputTokens":80000,"totalTokens":105000},"total":{"inputTokens":400000,"totalTokens":900000},"modelContextWindow":100000}""").jsonObject)
        assertEquals(105000L, usage.used)
        assertEquals(1f, usage.fraction!!, .00001f)
        assertEquals(900000L, usage.cumulative)
    }

    @Test fun invalidOrMissingRecentInputAndWindowRemainUnknown() {
        listOf("""{"last":{"outputTokens":100},"modelContextWindow":1000}""",
            """{"last":{"inputTokens":-1},"modelContextWindow":1000}""",
            """{"last":{"inputTokens":1},"modelContextWindow":0}""",
            """{"last":{"inputTokens":1},"modelContextWindow":-1}""").forEach {
            assertNull(ContextUsage.from(pocketJson.parseToJsonElement(it).jsonObject).fraction)
        }
        assertEquals(0f, ContextUsage(0, 1000, 900000).fraction!!, 0f)
        assertEquals(1f, ContextUsage(Long.MAX_VALUE, 1, null).fraction!!, 0f)
    }
}
