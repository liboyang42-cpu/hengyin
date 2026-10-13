package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundAssessmentRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundHandleRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReopenRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReworkRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundVerifyRequest;
import com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.AppUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityCancelManualRefundCaseControllerTest {
    @Test
    void routesUseExistingRefundReviewPermission() throws Exception {
        RequestMapping root = ActivityCancelManualRefundCaseController.class.getAnnotation(RequestMapping.class);
        assertEquals("/business/refund/activity-cancel-cases", root.value()[0]);

        Method list = ActivityCancelManualRefundCaseController.class
                .getMethod("list", Long.class, String.class);
        Method detail = ActivityCancelManualRefundCaseController.class
                .getMethod("detail", Long.class);
        Method assess = ActivityCancelManualRefundCaseController.class
                .getMethod("assess", ActivityCancelManualRefundAssessmentRequest.class);
        Method handle = ActivityCancelManualRefundCaseController.class
                .getMethod("handle", ActivityCancelManualRefundHandleRequest.class);
        Method verify = ActivityCancelManualRefundCaseController.class
                .getMethod("verify", ActivityCancelManualRefundVerifyRequest.class);
        Method rework = ActivityCancelManualRefundCaseController.class
                .getMethod("rework", ActivityCancelManualRefundReworkRequest.class);
        Method reopen = ActivityCancelManualRefundCaseController.class
                .getMethod("reopenAssessment", ActivityCancelManualRefundReopenRequest.class);
        Method upload = ActivityCancelManualRefundCaseController.class
                .getMethod("uploadEvidence", org.springframework.web.multipart.MultipartFile.class,
                        Long.class, Long.class, String.class);
        assertEquals("/list", list.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/{caseId}", detail.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/assess", assess.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/handle", handle.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/verify", verify.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/rework", rework.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/reopen-assessment", reopen.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/evidence/upload", upload.getAnnotation(PostMapping.class).value()[0]);
        assertTrue(list.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(detail.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(assess.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(handle.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(verify.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(rework.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(reopen.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertTrue(upload.getAnnotation(PreAuthorize.class).value().contains("business:refund:review"));
        assertEquals(com.chengyinhub.common.core.page.TableDataInfo.class, list.getReturnType());
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com",
                "chengyinhub", "web", "controller", "business",
                "ActivityCancelManualRefundCaseController.java")), StandardCharsets.UTF_8);
        assertTrue(source.indexOf("startPage();")
                        < source.indexOf("manualRefundCaseService.list(clubId, status)"),
                "列表必须先启用 PageHelper，再查询并用 TableDataInfo 返回 rows/total");
        assertTrue(source.contains("getDataTable(manualRefundCaseService.list(clubId, status))"));
    }

    @Test
    void assessUsesAuthenticatedAdminAndReturnsAuthoritativeReadback() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        ActivityCancelManualRefundAssessmentRequest request = new ActivityCancelManualRefundAssessmentRequest();
        request.setCaseId(9L);
        ActivityCancelManualRefundCaseVO stored = new ActivityCancelManualRefundCaseVO();
        stored.setId(9L);
        when(service.assess(7L, request)).thenReturn(stored);
        ActivityCancelManualRefundCaseController controller = controller(service);

        AjaxResult result = controller.assess(request);

        assertEquals(stored, result.get(AjaxResult.DATA_TAG));
        verify(service).assess(7L, request);
    }

    @Test
    void detailReturnsOnlyServiceValidatedVo() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        ActivityCancelManualRefundCaseVO stored = new ActivityCancelManualRefundCaseVO();
        stored.setId(9L);
        when(service.detail(9L)).thenReturn(stored);
        ActivityCancelManualRefundCaseController controller = controller(service);

        AjaxResult result = controller.detail(9L);

        assertNotNull(result.get(AjaxResult.DATA_TAG));
        verify(service).detail(9L);
    }

    @Test
    void handleAndVerifyUseAuthenticatedAdmin() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        ActivityCancelManualRefundHandleRequest handle = new ActivityCancelManualRefundHandleRequest();
        ActivityCancelManualRefundVerifyRequest verifyRequest = new ActivityCancelManualRefundVerifyRequest();
        ActivityCancelManualRefundCaseVO stored = new ActivityCancelManualRefundCaseVO();
        when(service.handle(7L, handle)).thenReturn(stored);
        when(service.verify(7L, verifyRequest)).thenReturn(stored);
        ActivityCancelManualRefundCaseController controller = controller(service);

        controller.handle(handle);
        controller.verify(verifyRequest);

        verify(service).handle(7L, handle);
        verify(service).verify(7L, verifyRequest);
    }

    @Test
    void correctionActionsUseAuthenticatedAdminAndReturnAuthoritativeReadback() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        ActivityCancelManualRefundReworkRequest rework = new ActivityCancelManualRefundReworkRequest();
        ActivityCancelManualRefundReopenRequest reopen = new ActivityCancelManualRefundReopenRequest();
        ActivityCancelManualRefundCaseVO stored = new ActivityCancelManualRefundCaseVO();
        when(service.rework(7L, rework)).thenReturn(stored);
        when(service.reopenAssessment(7L, reopen)).thenReturn(stored);
        ActivityCancelManualRefundCaseController controller = controller(service);

        controller.rework(rework);
        controller.reopenAssessment(reopen);

        verify(service).rework(7L, rework);
        verify(service).reopenAssessment(7L, reopen);
    }

    @Test
    void evidenceUploadUsesDedicatedSensitiveUploaderAndReturnsOnlyObjectKey() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        AppUploadService uploadService = mock(AppUploadService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", "image/jpeg", new byte[] { 1 });
        String objectKey = "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
        String versionId = "CAEQARiBgMC-example-version-1";
        when(service.prepareEvidenceUpload(7L, 9L, 1L, "finance-handle-1")).thenReturn(null);
        when(uploadService.uploadManualRefundEvidence(file)).thenReturn(
                new AppUploadService.ManualRefundEvidenceUpload(objectKey, versionId));
        when(service.recordEvidenceUpload(7L, 9L, 1L, "finance-handle-1",
                objectKey, versionId)).thenReturn(objectKey);
        ActivityCancelManualRefundCaseController controller = controller(service);
        ReflectionTestUtils.setField(controller, "appUploadService", uploadService);

        AjaxResult result = controller.uploadEvidence(file, 9L, 1L, "finance-handle-1");

        assertEquals(objectKey, result.get("objectKey"));
        assertEquals("finance-handle-1", result.get("requestId"));
        assertTrue(!result.containsKey("url"));
        assertTrue(!result.containsKey("versionId"));
        verify(uploadService).uploadManualRefundEvidence(file);
        verify(service).recordEvidenceUpload(7L, 9L, 1L, "finance-handle-1",
                objectKey, versionId);
    }

    @Test
    void repeatedEvidenceUploadRequestReadsBackTheBoundVersionWithoutAnotherPut() {
        IActivityCancelManualRefundCaseService service = mock(IActivityCancelManualRefundCaseService.class);
        AppUploadService uploadService = mock(AppUploadService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "proof.jpg", "image/jpeg", new byte[] { 1 });
        String objectKey = "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
        when(service.prepareEvidenceUpload(7L, 9L, 1L, "finance-handle-1"))
                .thenReturn(objectKey);
        ActivityCancelManualRefundCaseController controller = controller(service);
        ReflectionTestUtils.setField(controller, "appUploadService", uploadService);

        AjaxResult result = controller.uploadEvidence(file, 9L, 1L, "finance-handle-1");

        assertEquals(objectKey, result.get("objectKey"));
        verify(uploadService, org.mockito.Mockito.never()).uploadManualRefundEvidence(file);
    }

    private ActivityCancelManualRefundCaseController controller(
            IActivityCancelManualRefundCaseService service) {
        ActivityCancelManualRefundCaseController controller =
                new ActivityCancelManualRefundCaseController() {
                    @Override public Long getUserId() { return 7L; }
                };
        ReflectionTestUtils.setField(controller, "manualRefundCaseService", service);
        return controller;
    }
}
