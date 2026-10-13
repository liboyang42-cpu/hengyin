package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.domain.vo.ComplainableTopicVO;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICoopComplaintService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受理口 {@code /api/coop/complaint/report} 的**控制器职责**:
 * 准入取自 {@code selectComplainableTopics}(与投诉页选择源同一份判据)、白名单防伪造、重复工单拒绝。
 *
 * <p>⚠️ 2026-08-18 并入 master #738 后换了打桩面:归一判据已从本控制器搬进 service。
 * 继续 stub {@code selectCmsRegistrationList} 就是**对着一个没人再调的方法打桩** ——
 * 那会让所有「应当拒绝」的用例因为新 seam 默认返回空而恒绿。本次合并实测:12 条里
 * 8 条拒绝用例正是这样变成假绿的,只有 4 条断言「应当放行」的诚实地红了。
 * 归一判据本身(owner1/owner2 溯源、未支付排除、fail-closed 不兜底、去重)的用例
 * 已搬到 {@code ComplainableTopicsDerivationTest}。
 */
class ApiCoopComplaintReportTest {

    private static final Long MEMBER_ID = 41L;
    private static final Long TOPIC_ID = 91L;
    private static final Long REGISTRATION_ID = 291L;

    /** 在可投诉集合里 ⇒ 放行,且客户端塞的处置字段一个都不许落库。 */
    @Test
    void complainableTopicIsAcceptedAndEveryClientSuppliedVerdictFieldIsDropped() {
        Fixture fixture = fixture();
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Collections.singletonList(complainable(TOPIC_ID)));
        when(fixture.complaintService.create(any())).thenReturn(701L);

        CoopComplaint body = body(TOPIC_ID);
        body.setStatus(CoopComplaint.STATUS_CLOSED);
        body.setReporterMemberId(999L);
        body.setRegistrationId(998L);
        body.setRespondentType("merchant");
        body.setRespondentMemberId(997L);
        body.setAdvanceAmount(new BigDecimal("88.00"));
        body.setFaultRatio(100);
        body.setDeductAmount(new BigDecimal("66.00"));
        body.setHandleNote("客户端伪造处理意见");
        body.setHandlerId(996L);

        AjaxResult result = fixture.controller.complaintReport(body);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<CoopComplaint> captor = ArgumentCaptor.forClass(CoopComplaint.class);
        verify(fixture.complaintService).create(captor.capture());
        CoopComplaint safe = captor.getValue();
        assertEquals(TOPIC_ID, safe.getTopicId());
        assertEquals(MEMBER_ID, safe.getReporterMemberId(), "投诉人只认登录态,不认 body");
        assertEquals(REGISTRATION_ID, safe.getRegistrationId(), "报名单取自准入集合,不认 body");
        assertEquals("商家未按约定时间接待", safe.getReason());
        assertNull(safe.getStatus());
        assertNull(safe.getRespondentType());
        assertNull(safe.getRespondentMemberId());
        assertNull(safe.getAdvanceAmount());
        assertNull(safe.getFaultRatio());
        assertNull(safe.getDeductAmount());
        assertNull(safe.getHandleNote());
        assertNull(safe.getHandlerId());
    }

    /** ★ 不在集合里的主题一律拒:这是 IDOR / 非参与者刷冻结结算的唯一入口闸。 */
    @Test
    void topicOutsideTheComplainableSetIsRejected() {
        Fixture fixture = fixture();
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Collections.singletonList(complainable(TOPIC_ID)));

        assertRejected(fixture, fixture.controller.complaintReport(body(TOPIC_ID + 1)));
    }

    /** 集合为空(没参与过 / 全是未支付或反查不出主题的单)⇒ 拒。 */
    @Test
    void emptyComplainableSetRejectsEverything() {
        Fixture fixture = fixture();
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Collections.emptyList());

        assertRejected(fixture, fixture.controller.complaintReport(body(TOPIC_ID)));
    }

    /** 集合里有多项时,必须按 topicId 精确挑中那一项,带它自己的 registrationId。 */
    @Test
    void theMatchingEntryProvidesItsOwnRegistrationId() {
        Fixture fixture = fixture();
        ComplainableTopicVO other = new ComplainableTopicVO(TOPIC_ID + 1, "另一期", REGISTRATION_ID + 1);
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Arrays.asList(other, complainable(TOPIC_ID)));
        when(fixture.complaintService.create(any())).thenReturn(702L);

        assertEquals(200, fixture.controller.complaintReport(body(TOPIC_ID)).get("code"));

        ArgumentCaptor<CoopComplaint> captor = ArgumentCaptor.forClass(CoopComplaint.class);
        verify(fixture.complaintService).create(captor.capture());
        assertEquals(REGISTRATION_ID, captor.getValue().getRegistrationId());
    }

    /** 缺 topicId 的 body 连准入查询都不该发。 */
    @Test
    void bodyWithoutTopicIdIsRejectedBeforeAnyLookup() {
        Fixture fixture = fixture();

        assertRejected(fixture, fixture.controller.complaintReport(body(null)));
        verify(fixture.registrationService, never()).selectComplainableTopics(any());
    }

    /** 同一人对同一主题已有未结工单 ⇒ 不重复建(防刷冻结)。 */
    @Test
    void duplicateOpenComplaintIsRejected() {
        Fixture fixture = fixture();
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Collections.singletonList(complainable(TOPIC_ID)));
        CoopComplaint open = new CoopComplaint();
        open.setReporterMemberId(MEMBER_ID);
        open.setStatus(CoopComplaint.STATUS_ACCEPTED);
        when(fixture.complaintService.listByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(open));

        assertRejected(fixture, fixture.controller.complaintReport(body(TOPIC_ID)));
    }

    /** 已结工单不算「处理中」,不该挡住新投诉。 */
    @Test
    void closedComplaintDoesNotBlockANewOne() {
        Fixture fixture = fixture();
        when(fixture.registrationService.selectComplainableTopics(MEMBER_ID))
                .thenReturn(Collections.singletonList(complainable(TOPIC_ID)));
        CoopComplaint closed = new CoopComplaint();
        closed.setReporterMemberId(MEMBER_ID);
        closed.setStatus(CoopComplaint.STATUS_CLOSED);
        when(fixture.complaintService.listByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(closed));
        when(fixture.complaintService.create(any())).thenReturn(703L);

        assertEquals(200, fixture.controller.complaintReport(body(TOPIC_ID)).get("code"));
    }

    private Fixture fixture() {
        Fixture fixture = new Fixture();
        fixture.controller = spy(new ApiCoopController());
        doReturn(MEMBER_ID).when(fixture.controller).getAppUserId();
        fixture.registrationService = mock(ICmsRegistrationService.class);
        fixture.complaintService = mock(ICoopComplaintService.class);
        when(fixture.registrationService.selectComplainableTopics(any())).thenReturn(Collections.emptyList());
        when(fixture.complaintService.listByTopic(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(fixture.controller, "cmsRegistrationService", fixture.registrationService);
        ReflectionTestUtils.setField(fixture.controller, "coopComplaintService", fixture.complaintService);
        return fixture;
    }

    private ComplainableTopicVO complainable(Long topicId) {
        return new ComplainableTopicVO(topicId, "外滩夜行", REGISTRATION_ID);
    }

    private CoopComplaint body(Long topicId) {
        CoopComplaint body = new CoopComplaint();
        body.setTopicId(topicId);
        body.setReason("商家未按约定时间接待");
        return body;
    }

    private void assertRejected(Fixture fixture, AjaxResult result) {
        assertEquals(500, result.get("code"));
        verify(fixture.complaintService, never()).create(any());
    }

    private static class Fixture {
        private ApiCoopController controller;
        private ICmsRegistrationService registrationService;
        private ICoopComplaintService complaintService;
    }
}
