package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceSpansTest {
    private fun ref(type: String = "skill", name: String = "review") = buildJsonObject {
        put("type", type); put("name", name); put("displayName", "代码审查🙂")
        put("path", if (type == "mention") "app://github" else "D:/skills/review/SKILL.md")
        put("description", "审查代码"); put("source", "工作区")
    }
    private fun inserted() = insertVerifiedReference("请 /rev 然后继续", "请 /rev 然后继续", 2, 6, ref())!!

    @Test fun displayAndWireStaySeparateAndRoundTripThroughDraftJson() {
        val draft = inserted()
        assertEquals("请 代码审查🙂 然后继续", draft.text)
        assertEquals(2 + "代码审查🙂".length, draft.caret)
        val restored = restoreReferenceDraft(draft.text, encodeReferenceSpans(draft.spans))
        assertEquals(draft.text, restored.text)
        assertEquals(draft.spans, restored.spans)
        val prepared = prepareReferenceInputs(restored.text, restored.spans)
        assertEquals("请 \$review 然后继续", prepared.text)
        assertEquals(listOf("skill"), prepared.references.map { it["type"]!!.jsonPrimitive.content })
        assertFalse(prepared.references.single().containsKey("displayName"))
    }
    @Test fun editsBeforeSpanRebaseUtf16OffsetsIncludingChineseAndEmoji() {
        val draft = inserted()
        val after = "中文🙂" + draft.text
        val spans = rebaseReferenceSpans(draft.text, after, draft.spans)
        assertEquals(draft.spans.single().start + "中文🙂".length, spans.single().start)
        assertEquals("中文🙂请 \$review 然后继续", prepareReferenceInputs(after, spans).text)
    }
    @Test fun editsInsideReferenceRevokeIdentityWithoutRewritingTheEdit() {
        val draft = inserted()
        val after = draft.text.replace("代码", "中文")
        assertTrue(rebaseReferenceSpans(draft.text, after, draft.spans).isEmpty())
        assertEquals(after, prepareReferenceInputs(after, draft.spans).text)
        assertTrue(prepareReferenceInputs(after, draft.spans).references.isEmpty())
    }
    @Test fun deletionAndReplacementAcrossSpansRemoveOnlyAffectedReferences() {
        val one = insertVerifiedReference("/rev @app", "/rev @app", 0, 4, ref())!!
        val start = one.text.indexOf('@')
        val two = insertVerifiedReference(one.text, one.text, start, one.text.length, ref("mention", "internal-app"), one.spans)!!
        val after = two.text.substring(two.spans.first().end)
        val surviving = rebaseReferenceSpans(two.text, after, two.spans)
        assertEquals(1, surviving.size)
        assertEquals("mention", surviving.single().reference["type"]!!.jsonPrimitive.content)
    }
    @Test fun insertionAtBoundariesPreservesButInsertionInsideRevokes() {
        val draft = inserted(); val span = draft.spans.single()
        assertEquals(span.start + 1, rebaseReferenceSpans(draft.text, draft.text.replaceRange(span.start, span.start, "X"), draft.spans).single().start)
        assertEquals(span, rebaseReferenceSpans(draft.text, draft.text.replaceRange(span.end, span.end, "X"), draft.spans).single())
        assertTrue(rebaseReferenceSpans(draft.text, draft.text.replaceRange(span.start + 1, span.start + 1, "X"), draft.spans).isEmpty())
    }
    @Test fun manuallyTypedAndCopiedDisplayNamesNeverAcquireIdentity() {
        val draft = inserted(); val copied = draft.text + " 代码审查🙂 \$review @internal-app"
        val prepared = prepareReferenceInputs(copied, draft.spans)
        assertEquals("请 \$review 然后继续 代码审查🙂 \$review @internal-app", prepared.text)
        assertEquals(1, prepared.references.size)
        assertTrue(prepareReferenceInputs("\$review @internal-app", emptyList()).references.isEmpty())
    }
    @Test fun invalidCatalogFlagsStaleSelectionAndOverlappingSpansFailClosed() {
        assertNull(insertVerifiedReference("/x", "/old", 0, 2, ref()))
        assertNull(insertVerifiedReference("/x", "/x", 0, 2, JsonObject(ref() + ("enabled" to JsonPrimitive(false)))))
        val draft = inserted()
        assertTrue(prepareReferenceInputs(draft.text, draft.spans + draft.spans).references.isEmpty())
        assertTrue(restoreReferenceDraft(draft.text, "not json").spans.isEmpty())
        val future = encodeReferenceSpans(draft.spans).replace("\"version\":1", "\"version\":99")
        assertTrue(restoreReferenceDraft(draft.text, future).spans.isEmpty())
    }
    @Test fun legacyMetadataMigratesOnlyAnUnambiguousExactToken() {
        val legacy = JsonArray(listOf(JsonObject(ref() + ("token" to JsonPrimitive("\$review"))))).toString()
        val restored = restoreReferenceDraft("请 \$review", legacy)
        assertEquals("请 代码审查🙂", restored.text)
        assertEquals(1, restored.spans.size)
        assertTrue(restoreReferenceDraft("\$review \$review", legacy).spans.isEmpty())
        assertTrue(restoreReferenceDraft("\$reviewer", legacy).spans.isEmpty())
        assertTrue(restoreReferenceDraft("\$review", "[]").spans.isEmpty())
    }
    @Test fun sentMessagesRequireNativeSkillOrMentionContentProof() {
        val raw = buildJsonObject { put("content", JsonArray(listOf(ref()))) }
        val sent = sentReferenceDraft("使用 \$review", raw)
        assertEquals("使用 代码审查🙂", sent.text)
        assertEquals(1, sent.spans.size)
        assertTrue(sentReferenceDraft("使用 \$review", buildJsonObject {}).spans.isEmpty())
        val file = buildJsonObject { put("content", JsonArray(listOf(ref("file")))) }
        assertTrue(sentReferenceDraft("使用 @D:/skills/review/SKILL.md", file).spans.isEmpty())
    }
    @Test fun duplicateNativeReferencesDoNotDecorateUnverifiedCopies() {
        val raw = buildJsonObject { put("content", JsonArray(listOf(ref()))) }
        assertTrue(sentReferenceDraft("\$review \$review", raw).spans.isEmpty())
    }
    @Test fun sameNamesWithDifferentPathsRetainDistinctWireIdentity() {
        val one = insertVerifiedReference("/a /b", "/a /b", 0, 2, ref())!!
        val start = one.text.indexOf("/b")
        val second = JsonObject(ref() + ("path" to JsonPrimitive("D:/other/SKILL.md")))
        val two = insertVerifiedReference(one.text, one.text, start, start + 2, second, one.spans)!!
        assertEquals(2, prepareReferenceInputs(two.text, two.spans).references.size)
    }
    @Test fun iconSlotsKeepOffsetsMonotonicAcrossEveryChineseUtf16Position() {
        val draft = inserted()
        val map = ReferenceOffsetMap(draft.text.length, draft.spans)
        var previous = -1
        for (offset in 0..draft.text.length) {
            val transformed = map.originalToTransformed(offset)
            assertTrue(transformed >= previous)
            assertEquals(offset, map.transformedToOriginal(transformed))
            previous = transformed
        }
        previous = -1
        for (offset in 0..draft.text.length + draft.spans.size) {
            val original = map.transformedToOriginal(offset)
            assertTrue(original >= previous)
            assertTrue(original in 0..draft.text.length)
            previous = original
        }
    }
    @Test fun filesAndDirectoriesConvertToWireTextWithoutNativeMentionAuthorization() {
        for (type in listOf("file", "directory")) {
            val draft = insertVerifiedReference("@file", "@file", 0, 5, ref(type))!!
            val prepared = prepareReferenceInputs(draft.text, draft.spans)
            assertEquals("@D:/skills/review/SKILL.md ", prepared.text)
            assertTrue(prepared.references.isEmpty())
        }
    }
    @Test fun mismatchedLegacyTokenAndMalformedReferenceIdentityStayPlain() {
        val invalid = JsonObject(ref() + ("token" to JsonPrimitive("\$someone-else")))
        assertNull(referenceToken(invalid))
        assertTrue(restoreReferenceDraft("\$review", JsonArray(listOf(invalid)).toString()).spans.isEmpty())
        assertNull(referenceToken(JsonObject(ref() + ("name" to JsonPrimitive("bad\nname")))))
    }
    @Test fun deletingVerifiedFirstCopyCannotTransferIdentityToThePlainSecondCopy() {
        val reference = JsonObject(ref() - "displayName")
        val spans = listOf(ReferenceSpan(0, 6, "review", reference))
        assertTrue(rebaseReferenceSpans("review review", "review", spans).isEmpty())
        assertTrue(rebaseReferenceSpans("review review", "review", spans,
            ReferenceTextEdit(0, 7, "")).isEmpty())
    }
    @Test fun exactDeletionOfPlainSecondCopyPreservesTheVerifiedFirstCopy() {
        val reference = JsonObject(ref() - "displayName")
        val spans = listOf(ReferenceSpan(0, 6, "review", reference))
        assertEquals(spans, rebaseReferenceSpans("review review", "review", spans,
            ReferenceTextEdit(6, 13, "")))
    }
    @Test fun genericAmbiguityRevokesOnlyReferencesInsideThePossibleEditRegion() {
        val review = JsonObject(ref() - "displayName")
        val other = JsonObject(ref("mention", "other") - "displayName")
        val spans = listOf(ReferenceSpan(0, 5, "other", other), ReferenceSpan(6, 12, "review", review))
        val surviving = rebaseReferenceSpans("other review review", "other review", spans)
        assertEquals(listOf(spans.first()), surviving)
    }
    @Test fun invalidEditProofFailsClosedAndEqualTextReplacementRevokesIdentity() {
        val reference = JsonObject(ref() - "displayName")
        val spans = listOf(ReferenceSpan(0, 6, "review", reference))
        assertTrue(rebaseReferenceSpans("review", "review!", spans, ReferenceTextEdit(0, 6, "other!")).isEmpty())
        assertTrue(rebaseReferenceSpans("review", "review", spans, ReferenceTextEdit(0, 6, "review")).isEmpty())
        assertEquals(spans, rebaseReferenceSpans("review", "review", spans))
    }
}
