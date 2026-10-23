package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S531-3:审核员在后台申诉单详情里直接看到申诉附件(5 分钟预览),不必拿编号去媒体审核页手查。 */
class AuditAppealAttachmentDetailTest {
    private static final String WITH_ATTACHMENTS = "{\"appealId\":9,\"appealOfTaskId\":8,\"evidence\":"
            + "\"{\\\"evidence\\\":{\\\"note\\\":\\\"见附件\\\"},\\\"attachments\\\":[\\\"media-asset:31\\\",\\\"media-asset:32\\\"]}\"}";
    private static final String PLAIN = "{\"appealId\":9,\"appealOfTaskId\":8,\"evidence\":\"{\\\"note\\\":\\\"x\\\"}\"}";

    private IAuditTaskService audits;
    private AppMediaAssetService media;
    private AuditWorkbenchController controller;
    private AuditTask task;

    @BeforeEach
    void setUp() {
        audits = mock(IAuditTaskService.class);
        media = mock(AppMediaAssetService.class);
        controller = new AuditWorkbenchController() { @Override public Long getUserId() { return 100L; } };
        ReflectionTestUtils.setField(controller, "auditTaskService", audits);
        ReflectionTestUtils.setField(controller, "appMediaAssetService", media);
        task = new AuditTask();
        task.setId(40L);
        task.setBizType(AuditTask.BIZ_ACTIVITY);
        task.setBizId(7L);
        task.setSourceType("appeal");
        when(audits.selectAuditTaskById(40L)).thenReturn(task);
        when(media.reviewerAttachmentPreview(31L)).thenReturn(preview(31L, "IMAGE", "https://oss.example.invalid/31?sig=1"));
        when(media.reviewerAttachmentPreview(32L)).thenThrow(new RuntimeException("oss down"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reviewerWithMediaPermissionGetsPreviewUrlsAndOneFailureDoesNotBreakDetail() {
        loginWith("business:audit:list", "business:mediaReview:query");
        records(submit(WITH_ATTACHMENTS));

        AjaxResult result = controller.detail(40L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<?> items = (List<?>) result.get("appealAttachments");
        assertEquals(2, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(31L, first.get("assetId"));
        assertEquals("IMAGE", first.get("mediaType"));
        assertEquals("https://oss.example.invalid/31?sig=1", first.get("previewUrl"));
        Map<?, ?> second = (Map<?, ?>) items.get(1);
        assertEquals(32L, second.get("assetId"));
        assertNull(second.get("previewUrl"));
        assertEquals(Boolean.FALSE, result.get("appealAttachmentsPreviewDenied"));
    }

    @Test
    void reviewerWithoutMediaPermissionSeesOnlyIdsAndNoSignatureIsMinted() {
        loginWith("business:audit:list");
        records(submit(WITH_ATTACHMENTS));

        AjaxResult result = controller.detail(40L);

        List<?> items = (List<?>) result.get("appealAttachments");
        assertEquals(2, items.size());
        assertNull(((Map<?, ?>) items.get(0)).get("previewUrl"));
        assertEquals(Boolean.TRUE, result.get("appealAttachmentsPreviewDenied"));
        verify(media, never()).reviewerAttachmentPreview(anyLong());
    }

    @Test
    void appealWithoutAttachmentsOrNonAppealTaskAddsNothing() {
        loginWith("business:audit:list", "business:mediaReview:query");
        records(submit(PLAIN));
        AjaxResult plain = controller.detail(40L);
        assertFalse(plain.containsKey("appealAttachments"));
        assertFalse(plain.containsKey("appealAttachmentsPreviewDenied"));

        task.setSourceType("user_submit");
        records(submit(WITH_ATTACHMENTS));
        AjaxResult other = controller.detail(40L);
        assertFalse(other.containsKey("appealAttachments"));
        verify(media, never()).reviewerAttachmentPreview(anyLong());
        assertTrue(other.containsKey("records"));
    }

    private void records(AuditRecord... records) {
        when(audits.listAuditRecords(40L)).thenReturn(Arrays.asList(records));
    }

    private static AuditRecord submit(String snapshot) {
        AuditRecord record = new AuditRecord();
        record.setId(1L);
        record.setTaskId(40L);
        record.setAction(AuditRecord.ACTION_SUBMIT);
        record.setReasonCode("APPEAL");
        record.setSnapshotJson(snapshot);
        return record;
    }

    private static Map<String, Object> preview(Long id, String type, String url) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("assetId", id);
        item.put("mediaType", type);
        item.put("previewUrl", url);
        return item;
    }

    private static void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(100L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
