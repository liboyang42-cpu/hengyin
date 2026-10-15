package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantCrmNoteRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBatchTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmExportRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmMarketingConsentRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRemoveRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerDetailVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.domain.vo.MerchantCrmExportTaskVO;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentVO;
import com.chengyinhub.business.service.IMerchantCrmAppService;
import com.chengyinhub.business.service.IMerchantCrmExportService;
import com.chengyinhub.business.service.IMerchantCrmSegmentService;
import com.chengyinhub.business.service.IMerchantCrmCampaignService;
import com.chengyinhub.business.service.IMerchantCrmMarketingConsentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantCrmControllerTest {
    private static final long PRINCIPAL = 901L;

    @Test
    void endpointsForwardOnlyPrincipalAndPathCustomerId() {
        IMerchantCrmAppService service = mock(IMerchantCrmAppService.class);
        MerchantCrmCustomerDetailVO detail = new MerchantCrmCustomerDetailVO();
        MerchantCrmNoteRequest note = new MerchantCrmNoteRequest();
        MerchantCrmTagRequest tag = new MerchantCrmTagRequest();
        MerchantCrmTagRemoveRequest remove = new MerchantCrmTagRemoveRequest();
        remove.setTagId(3L);
        remove.setRequestId("tag-remove-1");
        when(service.detail(PRINCIPAL, 41L)).thenReturn(detail);
        ApiMerchantCrmController controller = controller(service);

        AjaxResult result = controller.detail(41L);
        controller.addNote(41L, note);
        controller.assignTag(41L, tag);
        controller.removeTag(41L, remove);

        assertEquals(detail, result.get(AjaxResult.DATA_TAG));
        verify(service).detail(PRINCIPAL, 41L);
        verify(service).addNote(PRINCIPAL, 41L, note);
        verify(service).assignTag(PRINCIPAL, 41L, tag);
        verify(service).removeTag(PRINCIPAL, 41L, 3L, "tag-remove-1");
    }

    @Test
    void idorDenialKeepsTheServiceStatusCode() {
        IMerchantCrmAppService service = mock(IMerchantCrmAppService.class);
        when(service.detail(PRINCIPAL, 99L))
                .thenThrow(new ServiceException("客户不存在或不属于当前商家", 404));

        AjaxResult result = controller(service).detail(99L);

        assertEquals(404, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void phase1AEndpointsPassOnlyAuthenticatedPrincipalAndServerPathIds() {
        IMerchantCrmAppService appService = mock(IMerchantCrmAppService.class);
        IMerchantCrmSegmentService segmentService = mock(IMerchantCrmSegmentService.class);
        IMerchantCrmExportService exportService = mock(IMerchantCrmExportService.class);
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        MerchantCrmBatchTagRequest batch = new MerchantCrmBatchTagRequest();
        MerchantCrmSegmentRequest segment = new MerchantCrmSegmentRequest();
        MerchantCrmExportRequest export = new MerchantCrmExportRequest();
        when(appService.listCustomers(PRINCIPAL, query)).thenReturn(new MerchantCrmCustomerPageVO());
        when(appService.reconcileProfiles(PRINCIPAL)).thenReturn(new MerchantCrmProfileReconcileVO());
        when(segmentService.list(PRINCIPAL)).thenReturn(Collections.<MerchantCrmSegmentVO>emptyList());
        when(exportService.create(PRINCIPAL, export)).thenReturn(new MerchantCrmExportTaskVO());
        when(exportService.status(PRINCIPAL, 88L)).thenReturn(new MerchantCrmExportTaskVO());
        ApiMerchantCrmController controller = controller(appService);
        ReflectionTestUtils.setField(controller, "merchantCrmSegmentService", segmentService);
        ReflectionTestUtils.setField(controller, "merchantCrmExportService", exportService);

        controller.customers(query);
        controller.reconcileProfiles();
        controller.batchAssignTag(batch);
        controller.saveSegment(segment);
        controller.segments();
        controller.createExport(export);
        controller.exportStatus(88L);

        verify(appService).listCustomers(PRINCIPAL, query);
        verify(appService).reconcileProfiles(PRINCIPAL);
        verify(appService).batchAssignTag(PRINCIPAL, batch);
        verify(segmentService).save(PRINCIPAL, segment);
        verify(segmentService).list(PRINCIPAL);
        verify(exportService).create(PRINCIPAL, export);
        verify(exportService).status(PRINCIPAL, 88L);
    }

    @Test
    void campaignAndConsentEndpointsForwardOnlyAuthenticatedPrincipalAndServerPathIds() {
        IMerchantCrmAppService appService = mock(IMerchantCrmAppService.class);
        IMerchantCrmCampaignService campaignService = mock(IMerchantCrmCampaignService.class);
        IMerchantCrmMarketingConsentService consentService = mock(IMerchantCrmMarketingConsentService.class);
        MerchantCrmCampaignRequest campaign = new MerchantCrmCampaignRequest();
        MerchantCrmMarketingConsentRequest consent = new MerchantCrmMarketingConsentRequest();
        ApiMerchantCrmController controller = controller(appService);
        ReflectionTestUtils.setField(controller, "merchantCrmCampaignService", campaignService);
        ReflectionTestUtils.setField(controller, "marketingConsentService", consentService);

        controller.createCampaign(campaign);
        controller.dispatchCampaign(8L);
        controller.retryCampaign(8L);
        controller.campaignDetail(8L);
        controller.updateMarketingConsent(consent);

        verify(campaignService).create(PRINCIPAL, campaign);
        verify(campaignService).dispatch(PRINCIPAL, 8L);
        verify(campaignService).retry(PRINCIPAL, 8L);
        verify(campaignService).detail(PRINCIPAL, 8L);
        verify(consentService).update(PRINCIPAL, consent);
    }

    @Test
    void campaignIdorDenialKeepsServiceStatusCode() {
        IMerchantCrmCampaignService campaignService = mock(IMerchantCrmCampaignService.class);
        when(campaignService.detail(PRINCIPAL, 99L))
                .thenThrow(new ServiceException("触达任务不存在或不属于当前商家", 404));
        ApiMerchantCrmController controller = controller(mock(IMerchantCrmAppService.class));
        ReflectionTestUtils.setField(controller, "merchantCrmCampaignService", campaignService);

        AjaxResult result = controller.campaignDetail(99L);

        assertEquals(404, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void exportCompletionIsRecordedOnlyAfterTheResponseStreamFinishes(@TempDir Path temp) throws Exception {
        byte[] payload = new byte[] { 1, 2, 3, 4 };
        Path file = temp.resolve("crm.xlsx");
        Files.write(file, payload);
        MerchantCrmExportFile export = mock(MerchantCrmExportFile.class);
        when(export.getFile()).thenReturn(file.toFile());
        when(export.getDownloadName()).thenReturn("merchant-crm-88.xlsx");
        IMerchantCrmExportService exportService = mock(IMerchantCrmExportService.class);
        when(exportService.authorizeDownload(PRINCIPAL, 88L, "download-token"))
                .thenReturn(export);
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(output(bytes, closed, false));
        doAnswer(call -> {
            assertTrue(closed.get(), "下载完成回执必须晚于响应流关闭");
            assertArrayEquals(payload, bytes.toByteArray());
            return null;
        }).when(exportService).completeDownload(export);
        ApiMerchantCrmController controller = controller(mock(IMerchantCrmAppService.class));
        ReflectionTestUtils.setField(controller, "merchantCrmExportService", exportService);

        controller.downloadExport(88L, "download-token", response);

        verify(exportService).authorizeDownload(PRINCIPAL, 88L, "download-token");
        verify(exportService).completeDownload(export);
    }

    @Test
    void brokenResponseStreamNeverRecordsDownloadCompletion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("crm.xlsx");
        Files.write(file, new byte[] { 1, 2, 3, 4 });
        MerchantCrmExportFile export = mock(MerchantCrmExportFile.class);
        when(export.getFile()).thenReturn(file.toFile());
        when(export.getDownloadName()).thenReturn("merchant-crm-88.xlsx");
        IMerchantCrmExportService exportService = mock(IMerchantCrmExportService.class);
        when(exportService.authorizeDownload(PRINCIPAL, 88L, "download-token"))
                .thenReturn(export);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(
                output(new ByteArrayOutputStream(), new AtomicBoolean(false), true));
        ApiMerchantCrmController controller = controller(mock(IMerchantCrmAppService.class));
        ReflectionTestUtils.setField(controller, "merchantCrmExportService", exportService);

        assertThrows(IOException.class,
                () -> controller.downloadExport(88L, "download-token", response));

        verify(exportService, never()).completeDownload(export);
    }

    private ServletOutputStream output(ByteArrayOutputStream bytes, AtomicBoolean closed,
                                       boolean failWrite) {
        return new ServletOutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (failWrite) throw new IOException("client disconnected");
                bytes.write(value);
            }

            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }

            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
    }

    private ApiMerchantCrmController controller(IMerchantCrmAppService service) {
        ApiMerchantCrmController controller = new ApiMerchantCrmController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "merchantCrmAppService", service);
        return controller;
    }
}
