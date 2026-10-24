package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [F2] activity 码核销的前置闸**只有一份**,且**两个入口都真的过它**。
 *
 * <p><b>这个类守的是什么</b>:{@code scan_qr_code}(activity 分支)与 {@code scan_qr_code_chapter}
 * 是同一条核销路的两个入口。前置闸(解析 + type/ownerType 一致 + 仅已支付 + 载入活动)必须逐字一致 ——
 * 抄成两份 ⇒ 加第四道闸时只改一处 = 另一个入口裸奔。
 *
 * <p><b>为什么必须有这个类</b>:{@code resolveScanContext} 的 javadoc 从第一天就写着「两个入口……
 * 前置闸必须逐字一致」,但那句话曾经**是空头支票** —— 上一版把「解析+通用闸」和「断言必须是 ③」焊死在
 * 一个方法里,而 {@code scan_qr_code} 需要的是通用闸之后**分流**、不是 ③ 的断言 ⇒ 它只能自己内联
 * 第二份 ⇒ helper 实际只有 {@code scan_qr_code_chapter} 一个调用方。**声称收口 ≠ 收口**,
 * 而不生效的恰恰是 {@code scan_qr_code} —— 商家实际扫码走的主入口。
 * <blockquote>光读 javadoc 会以为收口了;只有「让两个入口都跑同一道闸」的测试能证伪。</blockquote>
 *
 * <p>⚠️ <b>{@code scanQrCodeChapter} 此前零测试覆盖</b>(全仓 grep 0 命中)⇒ 卡里那条
 * 「🔴 负控:临时加第四道恒拒闸,**两个入口必须都拒**」原本**根本没法验** —— 第二个入口压根没有
 * 用例会红。本类先把它接进测试,那条负控才有意义。
 *
 * <p>🔴 <b>负控已实测</b>:在 {@code resolveScanContext} 开头临时插一道恒拒
 * {@code return ScanContext.fail(AjaxResult.error("恒拒"));} ⇒ 本类 <b>8 条全红</b>
 * (两个入口各 4 条)。把恒拒挪进 {@code resolveExploreScanContext}(只拦 chapter 入口)⇒
 * 只有 chapter 侧红、scan_qr_code 侧仍绿 = <b>没收口成功</b>,正是重构前的形态。
 */
class ScanContextSharedGateTest {

    private static final Long MERCHANT = 500L;
    private static final Long PLAYER = 77L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long TOPIC = 700L;
    private static final Long CHAPTER = 101L;
    private static final String CODE = "STATIC-CODE";

    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService",
                mock(ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", merchantAccessService);
        when(merchantAccessService.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_OWNER));
    }

    /** ③ 的标准单:owner_type=2 / owner_id=activityId,已支付。各用例按需改坏其中一个前提。 */
    private CmsRegistration explorePassReg() {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        reg.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        return reg;
    }

    private void givenActivity() {
        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        act.setEndDate(new java.util.Date(System.currentTimeMillis() + 86400000L));
        act.setMemberId(999L);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
    }

    /**
     * 闸的判据:**两个入口都必须拒,且文案一致**。
     *
     * <p>只断言其中一个入口 = 回到了「一份闸、一个入口生效」的老形态而测试照样绿。
     */
    private void assertBothEntriesRejectWith(String expectedMsg) {
        AjaxResult viaScan = controller.scanQrCode("activity", CODE);
        assertEquals(expectedMsg, viaScan.get("msg"),
                "入口1 scan_qr_code(activity 分支)没被这道闸拦住");

        AjaxResult viaChapter = controller.scanQrCodeChapter(CODE, CHAPTER);
        assertEquals(expectedMsg, viaChapter.get("msg"),
                "入口2 scan_qr_code_chapter 没被这道闸拦住 —— 闸没收口成一份");

        // 拒了就绝不能留下任何核销副作用(两个入口都是)
        verify(regSvc, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    @Test
    void bothExistingScanEntriesRejectExpiredAndMissingEndBeforeConsumption() {
        when(regSvc.selectByverificationCode(CODE)).thenReturn(explorePassReg());
        givenActivity();
        CmsActivity activity = activitySvc.selectCmsActivityById(ACTIVITY);
        activity.setEndDate(new java.util.Date(1));
        assertBothEntriesRejectWith("活动已结束，探索票不可继续使用");
        activity.setEndDate(null);
        assertBothEntriesRejectWith("活动结束时间未完善，暂不可使用");
    }

    /** 闸①前置:码解析不出 ⇒ 两个入口都拒。 */
    @Test
    void invalidCode_rejectedByBothEntries() {
        when(regSvc.selectByverificationCode(CODE)).thenReturn(null);

        assertBothEntriesRejectWith("核销码无效");
    }

    /**
     * 闸①:type 与 ownerType 不符 ⇒ 两个入口都拒。
     *
     * <p>这道闸是**资损根因**:没有它时,③(owner_type=2)的码用 type="topic" 扫会走进 topic 分支,
     * 把 activityId 当 topicId 去匹配中标记录(两表 id 区间重叠,撞上是常态)⇒ 整单票款结算给
     * 一个跟本单毫无关系的商家。
     */
    @Test
    void ownerTypeMismatch_rejectedByBothEntries() {
        CmsRegistration topicOrder = explorePassReg();
        topicOrder.setOwnerType(1L);          // 主题单,却拿 activity 语义来扫
        when(regSvc.selectByverificationCode(CODE)).thenReturn(topicOrder);

        assertBothEntriesRejectWith("核销类型与订单不匹配");
    }

    /**
     * 闸②(资损闸):非「已支付」单 ⇒ 两个入口都拒。
     * 防已退款单被旧静态核销码扫核销 → verification_status=1 → 二次分账。
     */
    @Test
    void refundedRegistration_rejectedByBothEntries() {
        CmsRegistration refunded = explorePassReg();
        refunded.setPaymentStatus(4);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(refunded);

        assertBothEntriesRejectWith("订单未完成支付或已退款,无法核销");
    }

    /** 闸③:活动查不到 ⇒ 两个入口都拒(拿不到 activity 就拿不到 topicId,再往下全是空指针或错表)。 */
    @Test
    void missingActivity_rejectedByBothEntries() {
        when(regSvc.selectByverificationCode(CODE)).thenReturn(explorePassReg());
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(null);

        assertBothEntriesRejectWith("活动不存在");
    }

    /**
     * ★ 边界:「必须真是 ③」这道断言**只属于 chapter 入口**,不能被提进通用闸。
     *
     * <p>提进去 ⇒ ①② 的活动票走 {@code scan_qr_code} 会被「该订单不是探索票」拒掉 ⇒
     * 整单核销全站挂掉。这条用例就是防这个:同一张 ①票,chapter 入口拒、scan_qr_code 入口**必须放行**。
     */
    @Test
    void guidedTicket_rejectedOnlyByChapterEntry_scanQrCodeStillPasses() {
        CmsRegistration guided = explorePassReg();
        guided.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(guided);
        givenActivity();

        // chapter 入口:③ 专用断言拒掉它
        AjaxResult viaChapter = controller.scanQrCodeChapter(CODE, CHAPTER);
        assertEquals("该订单不是探索票,请走普通核销", viaChapter.get("msg"));

        // scan_qr_code 入口:必须放行到 ①② 原路(这里扫码人非主办方 ⇒ 明确提示权限与领队团码,
        // 关键是**没有**被「不是探索票」拒 —— 那才是通用闸被污染的信号)
        AjaxResult viaScan = controller.scanQrCode("activity", CODE);
        assertEquals("该店无权核销本场玩家票；俱乐部带队请扫描领队团码", viaScan.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void dynamicTicket_usesServerVerifiedTypeWithoutClientTypeParameter() {
        CmsRegistration guided = explorePassReg();
        guided.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        String dynamicCode = VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis(),
                300_000L, 1L, "test-secret");
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(guided);
        givenActivity();

        AjaxResult result = controller.scanDynamicCode(dynamicCode);

        assertEquals("该店无权核销本场玩家票；俱乐部带队请扫描领队团码", result.get("msg"),
                "服务端应从验签后的动态码确定 activity 路径，而非接受前端 type");
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any(), anyLong(), any());
    }
}
