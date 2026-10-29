package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.RegistrationTicketApplication;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * P0-14 ③ 出码闸 —— <b>核销完第 1 章之后还得出得了码</b>。
 *
 * <p><b>这个类守的是一条会静默断链的绑定关系</b>:
 * <ul>
 *   <li>「玩家只要核销一个就不能退款」要求 {@code verification_status} **首次核销就置位** ——
 *       它是三个退款入口共用的唯一判据({@code RefundPolicy.verifiedVerdict});</li>
 *   <li>但 {@code issueDynCode} 原本也拿 {@code verification_status==1} 当「不再出码」的判据;</li>
 *   <li>⇒ 两者一撞:③ 核销完第 1 章 → 置位 → **再也拿不到码** → 第 2~5 章永远核销不了。
 *       而且全程零报错:玩家只会看到「该票已核销」,商家只会看到没人来。</li>
 * </ul>
 * ⇒ ③ 的出码判据必须换成「还有没有欠的章节」({@code hasPendingEntitlements})。
 *
 * <p>⚠️ 负控已实测:把 {@code && !perkLedgerService.hasPendingEntitlements(...)} 去掉
 * ⇒ explorePass_verifiedButChaptersPending_stillIssuesCode 红。
 */
class ExploreIssueCodeGateTest {

    private static final Long MEMBER = 77L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 901L;

    private ICmsRegistrationService regSvc;
    private IPerkLedgerService perkSvc;
    private ICmsActivityService activitySvc;
    private ApiVerifyController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        perkSvc = mock(IPerkLedgerService.class);
        activitySvc = mock(ICmsActivityService.class);

        controller = spy(new ApiVerifyController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "registrationTicketApplication", new RegistrationTicketApplication(
                regSvc, mock(com.chengyinhub.business.service.RegistrationSeatService.class), activitySvc, perkSvc,
                mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), "test-secret", mock(com.chengyinhub.web.service.verify.RegistrationDynCodeLedger.class)));
    }

    /** verificationStatus=1 且 registrationStatus=2(报名成功) */
    private void givenRegistration(int verificationStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setRegistrationStatus(2);
        r.setPaymentStatus(2);
        r.setVerificationStatus(verificationStatus);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(r);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setDelFlag(0);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    @Test
    void exploreAtEndCannotIssueNewCodeAndMissingEndCanRecover() {
        givenRegistration(0);
        CmsRegistration current = regSvc.selectCmsRegistrationById(REG);
        current.setPurchaseKind(3);
        CmsActivity activity = activitySvc.selectCmsActivityById(ACTIVITY);
        activity.setEndDate(null);
        assertEquals(500, controller.issueDynCode(REG).get("code"));
        activity.setEndDate(new java.util.Date(1));
        assertEquals(500, controller.issueDynCode(REG).get("code"));
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86400000L));
        assertEquals(200, controller.issueDynCode(REG).get("code"));
    }

    @Test
    void oldSuccessfulStatusWithoutPaidCurrentStateCannotIssue() {
        givenRegistration(0);
        regSvc.selectCmsRegistrationById(REG).setPaymentStatus(4);
        assertEquals(500, controller.issueDynCode(REG).get("code"));
        org.mockito.Mockito.verify(perkSvc, org.mockito.Mockito.never()).isTicketFullyConsumed(0, REG);
    }

    /**
     * ★ 核心:③ 已核销 1 章(verification_status 已置位)但还欠着章节 ⇒ **必须继续出码**。
     * 不修 issueDynCode 就会在这里红 —— 而生产上的表现是「玩家核销完第一家就再也扫不了码」,零告警。
     */
    @Test
    void explorePass_verifiedButChaptersPending_stillIssuesCode() {
        givenRegistration(1);                                     // 首次核销已置位
        when(perkSvc.isTicketFullyConsumed(1, REG)).thenReturn(false);  // 还欠章节 ⇒ 没消费完

        AjaxResult r = controller.issueDynCode(REG);

        assertEquals(200, r.get("code"), "还有欠的章节就必须出码,否则 ③ 多章核销链路当场断掉");
        assertNotNull(r.get("data"));
    }

    /** ③ 全部章节核销完 ⇒ 不再出码(没有可核销的了)。 */
    @Test
    void explorePass_allChaptersRedeemed_stopsIssuing() {
        givenRegistration(1);
        when(perkSvc.isTicketFullyConsumed(1, REG)).thenReturn(true);   // 全核销完了

        AjaxResult r = controller.issueDynCode(REG);

        assertEquals(500, r.get("code"));
        assertEquals("该票已核销", r.get("msg"));
    }

    /**
     * ★ ①/② 不误伤:它们没有 entitlement ⇒ hasPendingEntitlements 恒 false
     * ⇒ 逐字走原逻辑「已核销就不再出码」。
     */
    @Test
    void guidedTicket_verified_stopsIssuingAsBefore() {
        givenRegistration(1);
        when(perkSvc.isTicketFullyConsumed(1, REG)).thenReturn(true);   // ① 没有 entitlement ⇒ 退化成 verification_status==1

        AjaxResult r = controller.issueDynCode(REG);

        assertEquals(500, r.get("code"));
        assertEquals("该票已核销", r.get("msg"));
    }

    /** 未核销的票照常出码(不该因为新判据多查/误拦)。 */
    @Test
    void notVerified_issuesCodeWithoutCheckingEntitlements() {
        givenRegistration(0);

        AjaxResult r = controller.issueDynCode(REG);

        assertEquals(200, r.get("code"));
        // 判据统一走 isTicketFullyConsumed;它内部对 verification_status != 1 短路,不查 entitlement
        org.mockito.Mockito.verify(perkSvc).isTicketFullyConsumed(0, REG);
    }

    /** 已退款/已取消(registrationStatus != 2)仍然一律拒 —— 这条闸在 ③ 判据之前,别被绕过。 */
    @Test
    void refundedRegistration_rejectedBeforeExploreBranch() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setRegistrationStatus(3);   // 已取消
        r.setVerificationStatus(1);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(r);

        AjaxResult res = controller.issueDynCode(REG);

        assertEquals(500, res.get("code"));
        // ★ registrationStatus 闸在 ③ 消费判定**之前** ⇒ 压根不该走到 isTicketFullyConsumed。
        // 顺序反了的话,一张已退款的 ③ 票会因为「还欠着章节」而继续出码。
        org.mockito.Mockito.verify(perkSvc, org.mockito.Mockito.never())
                .isTicketFullyConsumed(org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyLong());
    }
}
