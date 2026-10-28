package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-13 ③ 权益清单接进订单详情 —— 玩家侧唯一能看到「我这张票还剩什么」的地方。
 *
 * <p><b>为什么非接不可</b>:`verification_status` 对 ③ 只表示「被核销过至少一次」,
 * 看不出还欠几章。不接的话 {@code listEntitlements} 就是死代码(接线前全仓零调用方),
 * 玩家买了 5 章的探索票,跑完 1 章后在订单页看到的是「已核销」—— 他会以为票用完了。
 *
 * <p>⚠️ 负控已实测:
 * <ul>
 *   <li>删掉 /info 里的 setEntitlements 那句 ⇒ explorePass_infoCarriesEntitlements 红</li>
 *   <li>去掉 purchase_kind 前置判断(无条件查)⇒ guidedActivityTicket_doesNotQueryLedger 红</li>
 *   <li>把闸写成 `!= EXPLORE_PASS` ⇒ 上面两条同时红</li>
 * </ul>
 */
class ExploreInfoEntitlementsTest {

    private static final Long MEMBER = 42L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;

    private ApiRegistrationController controller;
    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private IPerkLedgerService perkSvc;
    private IRefundService refundSvc;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);
        perkSvc = mock(IPerkLedgerService.class);
        refundSvc = mock(IRefundService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", regSvc);
        // ⚠️ controller 里有**两个** ICmsActivityService 字段:activityService(:65)与 cmsActivityService(:94)。
        //    /info 用的是前者。注错那个 ⇒ 字段为 null ⇒ NPE ⇒ 被 /info 的 catch 吞成 AjaxResult.error
        //    ⇒ 测试看到的是 data=null 而不是异常栈,排查起来像"断言写错了"。这里踩过。
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundSvc);
        when(refundSvc.selectRefundList(any(RefundApplication.class))).thenReturn(Collections.emptyList());

        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
    }

    private CmsRegistration reg(Integer purchaseKind) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setOwnerType(2L);          // ③ 与 ① 的活动票都是 owner_type=2
        r.setOwnerId(ACTIVITY);
        r.setPaymentStatus(2);
        r.setPurchaseKind(purchaseKind);
        return r;
    }

    private RegistrationEntitlement ent(Long chapterId, Integer status) {
        RegistrationEntitlement e = new RegistrationEntitlement();
        e.setChapterId(chapterId);
        e.setStatus(status);
        return e;
    }

    /** ★ ③ 票 ⇒ 订单详情带上权益清单(哪几章待核销/已核销)。 */
    @Test
    void explorePass_infoCarriesEntitlements() {
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg(PurchaseKind.EXPLORE_PASS));
        List<RegistrationEntitlement> ents = Arrays.asList(ent(101L, 1), ent(102L, 0));
        when(perkSvc.listEntitlements(REG)).thenReturn(ents);

        AjaxResult r = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));

        CmsRegistration data = (CmsRegistration) r.get("data");
        assertNotNull(data.getEntitlements(), "③ 票必须带权益清单");
        assertEquals(2, data.getEntitlements().size());
        verify(perkSvc).listEntitlements(REG);
    }

    /**
     * ★ ① 的活动票**也走 owner_type=2 这条分支**,但不该查权益账本。
     *
     * <p>这条不是洁癖:生产 12 笔付费单全是 ①,且 0 个 ③。无条件查 = 每次订单详情白查一次库,
     * 100% 命中空表。与 enrichSportFeed 那次「分页 20 行 = 20 次白查」同型。
     */
    @Test
    void guidedActivityTicket_doesNotQueryLedger() {
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg(PurchaseKind.GUIDED_TICKET));

        AjaxResult r = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));

        CmsRegistration data = (CmsRegistration) r.get("data");
        assertNull(data.getEntitlements(), "① 没有权益概念,该字段应保持 null 而不是空表");
        verify(perkSvc, never()).listEntitlements(anyLong());
    }

    /** purchase_kind 为 NULL(存量单/后台手工建单)⇒ 不查,逐字走老路。 */
    @Test
    void nullPurchaseKind_doesNotQueryLedger() {
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg(null));

        RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));

        verify(perkSvc, never()).listEntitlements(anyLong());
    }

    /**
     * ★ 归属校验仍在:别人的 ③ 票查不到,更不该泄漏权益清单。
     *
     * <p>这条守的是「我把 setEntitlements 塞进了归属校验之后」这个前提 ——
     * 若哪天有人把它挪到 :540 之前,这条红。
     */
    @Test
    void otherMembersTicket_rejected_andNoLedgerLeak() {
        CmsRegistration someoneElse = reg(PurchaseKind.EXPLORE_PASS);
        someoneElse.setMemberId(999L);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(someoneElse);

        AjaxResult r = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));

        assertEquals("无权查看该报名信息", r.get("msg"));
        verify(perkSvc, never()).listEntitlements(anyLong());
    }
}
