package dev.codexpocket.app.ui

import dev.codexpocket.app.data.ActivityStatus
import dev.codexpocket.app.data.TimelineItem
import dev.codexpocket.app.data.pocketJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineDiffPolicyTest {
    @Test fun emptyBinaryAndExplicitTruncationHaveDistinctFallbacks() {
        assertEquals(InlineDiffFallback.Empty, inlineDiffFallback(" \n"))
        assertEquals(InlineDiffFallback.Binary, inlineDiffFallback("diff --git a/x.png b/x.png\nBinary files a/x.png and b/x.png differ"))
        assertEquals(InlineDiffFallback.Binary, inlineDiffFallback("GIT binary patch\nliteral 12\nz+encoding"))
        assertEquals(InlineDiffFallback.Truncated, inlineDiffFallback("@@ -1 +1 @@\n-old\n+new", true))
        assertEquals(null, inlineDiffFallback("@@ -1 +1 @@\n-Binary files a and b differ\n+GIT binary patch"))
    }

    @Test fun nativeUpdateWithMovePathUsesDestinationAndRenameLabel() {
        val item = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{"id":"rename","type":"fileChange","changes":[{"path":"src/Old.kt","kind":{"type":"update","movePath":"src/New.kt"},"diff":"@@ -1 +1 @@\n-old\n+new"}]}""").jsonObject)
        val file = reviewActivityFiles(item).single()
        assertEquals("src/New.kt", file.path)
        assertEquals("rename", file.kind)
        assertEquals("file:0", file.id)
    }

    @Test fun binaryCreationNoticeDoesNotBecomeAddedSourceThroughNormalization() {
        val item = TimelineItem.from("turn", pocketJson.parseToJsonElement("""{"id":"binary","type":"fileChange","changes":[{"path":"image.png","kind":{"type":"add"},"diff":"Binary files /dev/null and b/image.png differ"}]}""").jsonObject)
        val file = reviewActivityFiles(item).single()
        assertEquals(InlineDiffFallback.Binary, inlineDiffFallback(file.diff))
        assertEquals("Binary files /dev/null and b/image.png differ", file.diff)
        assertEquals("已创建", fileActionLabel(file.kind, ActivityStatus.Completed))
    }

    @Test fun nativeChangeKindsUseDistinctVerbs() {
        assertEquals("已创建", fileActionLabel("add", ActivityStatus.Completed))
        assertEquals("已编辑", fileActionLabel("update", ActivityStatus.Completed))
        assertEquals("已删除", fileActionLabel("delete", ActivityStatus.Completed))
        assertEquals("已重命名", fileActionLabel("rename", ActivityStatus.Completed))
        assertEquals("已创建", fileActionLabel("created", ActivityStatus.Completed))
    }

    @Test fun incompleteOrFailedChangesNeverClaimSuccessfulEditing() {
        assertEquals("正在创建", fileActionLabel("add", ActivityStatus.Running))
        assertEquals("待批准删除", fileActionLabel("delete", ActivityStatus.Waiting))
        assertEquals("重命名失败", fileActionLabel("rename", ActivityStatus.Failed))
        assertEquals("编辑已中断", fileActionLabel("update", ActivityStatus.Interrupted))
        assertEquals("编辑", fileActionLabel("unknown", ActivityStatus.Unknown))
    }
}
