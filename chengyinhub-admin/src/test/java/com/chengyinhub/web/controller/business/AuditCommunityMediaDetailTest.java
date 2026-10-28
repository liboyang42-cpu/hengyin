package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.github.pagehelper.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditCommunityMediaDetailTest {
    private static final String ORIGINAL = "{\"post\":{\"body\":\"original text\"},\"media\":[{\"derived_object_key\":\"old bearer\"}]}";
    private static final String CLEAN = "{\"post\":{\"body\":\"original text\"},\"media\":[{\"derived_object_key\":\"upload/stable.png\"}]}";
    private IAuditTaskService audits;
    private ICommunityPostService community;
    private AuditWorkbenchController controller;
    private AuditTask task;

    @BeforeEach void setUp() {
        audits = mock(IAuditTaskService.class); community = mock(ICommunityPostService.class);
        controller = new AuditWorkbenchController() { @Override public Long getUserId() { return 100L; } };
        ReflectionTestUtils.setField(controller, "auditTaskService", audits);
        ReflectionTestUtils.setField(controller, "communityPostService", community);
        task = new AuditTask(); task.setId(30L); task.setVersion(2); task.setBizId(7L);
        task.setBizType(AuditTask.BIZ_COMMUNITY_POST); task.setSnapshotJson(ORIGINAL);
        when(audits.selectAuditTaskById(30L)).thenReturn(task);
        when(audits.listAuditRecords(30L)).thenReturn(Collections.emptyList());
        when(community.auditSnapshotForRead(ORIGINAL)).thenReturn(CLEAN);
    }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void detailKeepsFrozenTaskAndRecordsReadableWhenPreviewIsUnavailable() {
        AuditRecord record = new AuditRecord(); record.setSnapshotJson(ORIGINAL); record.setReasonText("original reason");
        when(audits.listAuditRecords(30L)).thenReturn(Collections.singletonList(record));
        Map<String, Object> preview = new LinkedHashMap<>(); preview.put("status", "UNAVAILABLE");
        preview.put("taskId", 30L); preview.put("taskVersion", 2); preview.put("items", Collections.emptyList());
        when(community.auditMediaPreview(task)).thenReturn(preview);
        AjaxResult result = controller.detail(30L);
        AuditTask projected = (AuditTask) result.get("task");
        AuditRecord projectedRecord = (AuditRecord) ((List<?>) result.get("records")).get(0);
        assertEquals(200, result.get(AjaxResult.CODE_TAG)); assertSame(preview, result.get("communityMediaPreview"));
        assertNotSame(task, projected); assertEquals(CLEAN, projected.getSnapshotJson());
        assertEquals(2, projected.getVersion()); assertEquals(CLEAN, projectedRecord.getSnapshotJson());
        assertEquals("original reason", projectedRecord.getReasonText());
        assertEquals(ORIGINAL, task.getSnapshotJson()); assertEquals(ORIGINAL, record.getSnapshotJson());
    }

    @Test void getInfoUsesASanitizedCopyWithoutMintingASignature() {
        AuditTask projected = (AuditTask) controller.getInfo(30L).get(AjaxResult.DATA_TAG);
        assertNotSame(task, projected); assertEquals(CLEAN, projected.getSnapshotJson());
        assertEquals(ORIGINAL, task.getSnapshotJson()); verify(community, never()).auditMediaPreview(any());
    }

    @Test void listKeepsPageTotalAndOriginalObjectsWhileSanitizingMediaFields() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Page<AuditTask> page = new Page<>(2, 10); page.setTotal(77); page.add(task);
        when(audits.selectWorkbenchQueueWithGuardScope(any(), eq(List.of()))).thenReturn(page);
        com.chengyinhub.common.core.page.TableDataInfo result = controller.list(new AuditTask());
        assertEquals(77L, result.getTotal());
        AuditTask projected = (AuditTask) result.getRows().iterator().next();
        assertEquals(CLEAN, projected.getSnapshotJson()); assertEquals(ORIGINAL, task.getSnapshotJson());
        assertSame(task, page.get(0)); assertNotSame(page, result.getRows());
        verify(community, never()).auditMediaPreview(any());
    }

    @Test void immutableServiceListRemainsUnchangedAndNonCommunityRowsArePreserved() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        AuditTask other = new AuditTask(); other.setBizType(AuditTask.BIZ_TOPIC);
        other.setSnapshotJson("unrelated snapshot");
        List<AuditTask> immutable = Collections.unmodifiableList(Arrays.asList(task, other));
        when(audits.selectWorkbenchQueueWithGuardScope(any(), eq(List.of()))).thenReturn(immutable);
        com.chengyinhub.common.core.page.TableDataInfo result = controller.list(new AuditTask());
        assertEquals(2L, result.getTotal());
        assertNotSame(immutable, result.getRows());
        assertSame(task, immutable.get(0)); assertEquals(ORIGINAL, task.getSnapshotJson());
        assertEquals(CLEAN, ((AuditTask) result.getRows().get(0)).getSnapshotJson());
        assertSame(other, result.getRows().get(1)); assertEquals("unrelated snapshot", other.getSnapshotJson());
        verify(community, never()).auditMediaPreview(any());
    }
}
