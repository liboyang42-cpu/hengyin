package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** ORDER-01：扫码完成读回不需要通关/核销，仍保留真实零元退出资格。 */
class ApiRegistrationFirstScanReadTest {
    private ApiRegistrationController controller;
    private ICmsRegistrationService registrations;
    private CmsRegistration scanned;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiRegistrationController());
        doReturn(99L).when(controller).getAppUserId();
        registrations = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IRefundService refunds = mock(IRefundService.class);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activities);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refunds);
        when(refunds.selectRefundList(any())).thenReturn(Collections.emptyList());
        CmsActivity activity = new CmsActivity();
        activity.setId(100L);
        activity.setName("扫码读回");
        activity.setStartDate(new Date(System.currentTimeMillis() + 2 * 86400000L));
        activity.setEndDate(new Date(System.currentTimeMillis() + 3 * 86400000L));
        when(activities.selectCmsActivityById(100L)).thenReturn(activity);
        scanned = row(1L);
        scanned.setFirstSuccessfulScanAt(new Date(System.currentTimeMillis() - 1000L));
    }

    @Test
    void completedAndUnusedListsUseTheSameRealScanFactWithoutFilteringByVerification() {
        CmsRegistration unused = row(2L);
        CmsRegistration verified = row(3L);
        verified.setVerificationStatus(1);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(scanned, unused, verified));
        TableDataInfo complete = (TableDataInfo) RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "3", null).get("data");
        assertEquals(Arrays.asList(scanned, verified), complete.getRows());
        TableDataInfo pending = (TableDataInfo) RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "2", null).get("data");
        assertEquals(Collections.singletonList(unused), pending.getRows());
        ArgumentCaptor<CmsRegistration> query = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations, times(2)).selectCmsRegistrationList(query.capture());
        for (CmsRegistration probe : query.getAllValues()) {
            assertEquals(99L, probe.getMemberId());
            assertEquals(2, probe.getRegistrationStatus());
            assertNull(probe.getVerificationStatus());
        }
        assertEquals(0, scanned.getVerificationStatus());
    }

    @Test
    void orderDetailReturnsFirstScanAndZeroUnredeemedExitVerdict() {
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(scanned);
        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("1");
        assertEquals(200, result.get("code"));
        CmsRegistration read = (CmsRegistration) result.get("data");
        assertEquals(scanned.getFirstSuccessfulScanAt(), read.getFirstSuccessfulScanAt());
        assertEquals(0, read.getVerificationStatus());
        assertNull(read.getVerificationTime());
        assertTrue(read.getRefundInfo().refundable);
        assertEquals("可免费取消", read.getRefundInfo().reason);
    }

    @Test
    void participationReturnsTheSameTimestampAndActualExitVerdict() {
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            return Long.valueOf(2L).equals(probe.getOwnerType()) ? Collections.singletonList(scanned) : Collections.emptyList();
        });
        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).myJoined();
        assertEquals(200, result.get("code"));
        List<?> rows = (List<?>) result.get("data");
        assertEquals(1, rows.size());
        CmsRegistration read = (CmsRegistration) rows.get(0);
        assertEquals(scanned.getFirstSuccessfulScanAt(), read.getFirstSuccessfulScanAt());
        assertTrue(read.getRefundInfo().refundable);
        assertEquals(0, read.getVerificationStatus());
    }

    @Test
    void orderDetailKeepsEachScannedTemplateAndStripsItsOwnAnswers() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ICmsRegistrationScanService scans = mock(ICmsRegistrationScanService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topics);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationScanService", scans);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "memberTemplateService", templates);
        CmsTopic topic = new CmsTopic();
        topic.setId(100L);
        when(topics.selectCmsTopicById(100L)).thenReturn(topic);
        scanned.setOwnerType(1L);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(scanned);
        CmsRegistrationScan first = new CmsRegistrationScan();
        first.setRegistrationType(2);
        first.setTemplateId(6016L);
        CmsRegistrationScan second = new CmsRegistrationScan();
        second.setRegistrationType(2);
        second.setTemplateId(6018L);
        when(scans.selectCmsRegistrationScanList(any())).thenReturn(Arrays.asList(first, second));
        for (Long templateId : Arrays.asList(6016L, 6018L)) {
            CmsMemberTemplate template = new CmsMemberTemplate();
            template.setId(templateId);
            template.setQuestionAnswer("private-answer-" + templateId);
            template.setHint1("private-hint-" + templateId);
            when(templates.selectCmsMemberTemplateById(templateId)).thenReturn(template);
        }
        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("1");
        assertEquals(200, result.get("code"));
        List<CmsRegistrationScan> rows = ((CmsRegistration) result.get("data")).getRegistrationScanList();
        assertEquals(Arrays.asList(first, second), rows);
        for (CmsRegistrationScan row : rows) {
            assertEquals(row.getTemplateId(), row.getMemberTemplate().getId());
            assertNull(row.getMemberTemplate().getQuestionAnswer());
            assertNull(row.getMemberTemplate().getHint1());
        }
        verify(templates).selectCmsMemberTemplateById(6016L);
        verify(templates).selectCmsMemberTemplateById(6018L);
    }

    private CmsRegistration row(Long id) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id);
        row.setMemberId(99L);
        row.setOwnerType(2L);
        row.setOwnerId(100L);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setWechatPaymentAmount(BigDecimal.ZERO);
        row.setPayableAmount(BigDecimal.ZERO);
        row.setPointUsed(0L);
        row.setCreateTime(new Date(System.currentTimeMillis() - 10000L));
        return row;
    }
}
