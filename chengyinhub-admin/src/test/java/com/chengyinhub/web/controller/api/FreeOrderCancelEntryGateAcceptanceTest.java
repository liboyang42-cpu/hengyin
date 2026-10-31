package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.RegistrationTicketApplication;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-EXIT-ZERO 验收(取消之后):免费单取消落库为 payment_status=4 / registration_status=3,
 * 之后该单既不能出核销码,也不能被玩法入口当作有效报名。
 */
class FreeOrderCancelEntryGateAcceptanceTest {

    private static final Long MEMBER = 61001L;
    private static final Long REG = 51001L;
    private static final Long ACTIVITY = 81001L;

    /** 取消落库后的形状:claimRegistrationRefunded 同时置 payment_status=4、registration_status=3。 */
    private static CmsRegistration cancelledFreeOrder() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setPaymentStatus(4);
        r.setRegistrationStatus(3);
        r.setVerificationStatus(0);   // 从未核销:只有 registrationStatus 闸能拦住
        return r;
    }

    @Test
    void cancelledFreeOrderCannotIssueVerificationCode() {
        ICmsRegistrationService regSvc = mock(ICmsRegistrationService.class);
        IPerkLedgerService perkSvc = mock(IPerkLedgerService.class);
        ICmsActivityService activitySvc = mock(ICmsActivityService.class);
        ApiVerifyController controller = spy(new ApiVerifyController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "registrationTicketApplication",
                new RegistrationTicketApplication(regSvc, mock(com.chengyinhub.business.service.RegistrationSeatService.class),
                        activitySvc, perkSvc, mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), "test-secret", mock(com.chengyinhub.web.service.verify.RegistrationDynCodeLedger.class)));
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(cancelledFreeOrder());

        AjaxResult res = controller.issueDynCode(REG);

        assertEquals(500, res.get("code"));
        assertEquals("订单未完成支付或已结束,无法生成核销码", res.get("msg"));
        assertNull(res.get("data"));
        verify(perkSvc, never()).isTicketFullyConsumed(anyInt(), anyLong());
        verify(activitySvc, never()).selectCmsActivityById(any());
    }

    /**
     * 玩法入口的报名探针只按 registration_status=2 查:取消后的 status=3 行不在结果里 ⇒ 不算已报名。
     * (过滤由 selectCmsRegistrationList 的 SQL 完成;这里钉死探针必须带 status=2,而不是漏掉条件。)
     */
    @Test
    void playEntryProbesOnlyAcceptSuccessfulRegistrations() {
        ICmsRegistrationService regSvc = mock(ICmsRegistrationService.class);
        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        ApiPlayProgressController controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", regSvc);

        Boolean registered = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "isRegistered", MEMBER, ACTIVITY);
        CmsRegistration entry = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "activityRegistration",
                MEMBER, ACTIVITY, REG);

        assertFalse(registered);
        assertNull(entry);
        ArgumentCaptor<CmsRegistration> probes = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(regSvc, org.mockito.Mockito.times(2)).selectCmsRegistrationList(probes.capture());
        for (CmsRegistration probe : probes.getAllValues()) {
            assertEquals(Integer.valueOf(2), probe.getRegistrationStatus(), "玩法探针必须只认报名成功(2)");
            assertEquals(MEMBER, probe.getMemberId());
            assertEquals(ACTIVITY, probe.getOwnerId());
        }
    }
}
