package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S531-3:申诉补充材料两种形状并存,只从带附件的形状里取 media-asset:{id}。 */
class AppealAttachmentRefsTest {

    @Test
    void attachmentShapeYieldsAssetIdsInOrderWithoutDuplicates() {
        assertEquals(Arrays.asList(31L, 32L), AppealAttachmentRefs.fromEvidenceJson(
                "{\"evidence\":{\"note\":\"见附件\"},\"attachments\":[\"media-asset:31\",\"media-asset:32\",\"media-asset:31\"]}"));
        assertEquals(Collections.singletonList(5L), AppealAttachmentRefs.fromEvidenceJson(
                "{\"evidence\":null,\"attachments\":[\"media-asset:5\"]}"));
    }

    @Test
    void plainEvidenceOrTextOrGarbageHasNoAttachments() {
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromEvidenceJson(null));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromEvidenceJson("现场照片在群里"));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromEvidenceJson("{\"note\":\"x\"}"));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromEvidenceJson("[\"media-asset:1\"]"));
        // 原补充材料自带 attachments 字段但不是附件形状(缺 evidence 包装):不当附件
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromEvidenceJson("{\"attachments\":[\"media-asset:1\"]}"));
        // 非法引用(外链/非数字/0)一律忽略
        assertEquals(Collections.singletonList(7L), AppealAttachmentRefs.fromEvidenceJson(
                "{\"evidence\":{},\"attachments\":[\"https://x.invalid/a.png\",\"media-asset:abc\",\"media-asset:0\",3,\"media-asset:7\"]}"));
    }

    @Test
    void submitRecordSnapshotCarriesEvidenceAsJsonString() {
        String snapshot = "{\"appealId\":9,\"appealOfTaskId\":8,\"evidence\":"
                + "\"{\\\"evidence\\\":{\\\"note\\\":\\\"x\\\"},\\\"attachments\\\":[\\\"media-asset:31\\\"]}\"}";
        assertEquals(Collections.singletonList(31L), AppealAttachmentRefs.fromSubmitSnapshot(snapshot));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromSubmitSnapshot(
                "{\"appealId\":9,\"appealOfTaskId\":8,\"evidence\":\"{\\\"note\\\":\\\"x\\\"}\"}"));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromSubmitSnapshot(
                "{\"appealId\":9,\"appealOfTaskId\":8,\"evidence\":null}"));
        assertEquals(Collections.emptyList(), AppealAttachmentRefs.fromSubmitSnapshot("not json"));
    }

    @Test
    void refsAreCappedAtSubmissionLimit() {
        StringBuilder json = new StringBuilder("{\"evidence\":{},\"attachments\":[");
        for (int i = 1; i <= 10; i++) json.append(i > 1 ? "," : "").append("\"media-asset:").append(i).append('"');
        json.append("]}");
        assertEquals(AppealAttachmentRefs.MAX_REFS, AppealAttachmentRefs.fromEvidenceJson(json.toString()).size());
    }
}
