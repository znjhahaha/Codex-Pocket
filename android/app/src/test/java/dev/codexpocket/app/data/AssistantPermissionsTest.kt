package dev.codexpocket.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssistantPermissionsTest {
    @Test fun currentAndLegacyPermissionPathsAreCompletelyDescribed() {
        val profile=Json.parseToJsonElement("""{"network":{"enabled":true},"fileSystem":{"read":["D:/docs"],"write":["D:/output"],"entries":[{"access":"write","path":{"type":"path","path":"D:/project"}},{"access":"read","path":{"type":"glob_pattern","pattern":"D:/shared/*.md"}},{"access":"deny","path":{"type":"special","value":{"kind":"root"}}}],"globScanMaxDepth":2}}""")
        assertEquals(listOf("允许网络访问","读取 · D:/docs","写入 · D:/output","写入 · D:/project","读取 · 匹配路径 D:/shared/*.md","禁止访问 · 整个文件系统","路径匹配最多扫描 2 层"),assistantPermissionLines(profile))
    }
    @Test fun unknownOrMalformedPermissionsCannotBecomeApprovalEvidence() {
        for(raw in listOf("""{"network":{"enabled":"true"}}""","""{"network":{"enabled":true,"extra":true}}""","""{"fileSystem":{"entries":[{"access":"execute","path":{"type":"path","path":"D:/p"}}]}}""","""{"fileSystem":{"entries":[{"access":"write","path":{"type":"special","value":{"kind":"unknown","path":"all"}}}]}}""")) {
            assertNull(assistantPermissionLines(Json.parseToJsonElement(raw)))
        }
    }
}
