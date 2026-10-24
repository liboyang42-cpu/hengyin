package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★★ [资损·根因] {@code scan_qr_code} 的 {@code type} 必须与 {@code registration.owner_type} 一致。
 *
 * <p><b>这个类守的是一个「假不变量」翻车</b>:代码里曾写过
 * 「③ 定死是 owner_type=2 的活动单 ⇒ 到不了 topic 分支」并据此拆掉了 topic 分支的守卫。
 * <b>那个不变量是假的</b> —— 决定走哪个分支的是**扫码方传的 {@code type} 字符串**,
 * 而 {@code resolveRegistrationByScanCode} 只从 code 解析单子、**从不回头核对两者**。
 *
 * <p><b>两个分支对同一个 owner_id 的解释根本不同</b>,这才是资损的引信:
 * <ul>
 *   <li>{@code type="activity"}:{@code selectCmsActivityById(reg.getOwnerId())} ⇒ ownerId 当 <b>activityId</b></li>
 *   <li>{@code type="topic"}:{@code setTopicId(reg.getOwnerId())} ⇒ ownerId 当 <b>topicId</b></li>
 * </ul>
 * {@code cms_topic} 与 {@code cms_activity} 是两条独立自增序列、id 区间大量重叠 ⇒ 撞上是常态。
 *
 * <p><b>怎么变成钱</b>:一张 owner_type=2 的单(①活动票 或 ③探索票)被以 {@code type="topic"} 扫
 * → topic 分支 → {@code setTopicId(activityId)} → 若扫码商家恰好在 {@code topic#activityId} 上中标
 * → {@code verifyAndSettle(reg, memberId, memberId)} ⇒ <b>整单票款结算给一个跟本单毫无关系的商家</b>,
 * 且 verification_status=1 永久锁死退款。反方向同型。
 *
 * <p>⚠️ 负控已实测:去掉 {@code assertScanTypeMatchesOwner} 两处调用中的任一处 ⇒ 对应用例红。
 */
class ScanTypeOwnerMismatchGateTest {

    private static final Long ATTACKER = 500L;   // 在 topic#800 上中标的商家
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;   // ③ 的 ownerId
    private static final Long TOPIC = 700L;
    private static final String CODE = "STATIC-CODE";

    private ICmsRegistrationService regSvc;
    private ICmsRegistrationMerchantService merchantSvc;
    private IPerkLedgerService perkSvc;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        merchantSvc = mock(ICmsRegistrationMerchantService.class);
        perkSvc = mock(IPerkLedgerService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(ATTACKER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService", merchantSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");

        // 这个商家在 topic#800 上中标 —— 而 800 恰好是那张 ③ 票的 activityId(两表 id 区间重叠)
        CmsRegistrationMerchant winner = new CmsRegistrationMerchant();
        winner.setValidationMethod(1);
        when(merchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(winner));
    }

    private CmsRegistration activityOrder(Integer purchaseKind) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);          // 活动单
        r.setOwnerId(ACTIVITY);      // ownerId = activityId = 800
        r.setMemberId(77L);
        r.setPaymentStatus(2);
        r.setPurchaseKind(purchaseKind);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(r);
        return r;
    }

    /**
     * ★ 核心:③ 的活动单用 {@code type="topic"} 扫 ⇒ **必须拒**,绝不能落进 topic 分支被整单结算。
     * 去掉 topic 分支的 assertScanTypeMatchesOwner ⇒ 本例红(会走到 verifyAndSettle)。
     */
    @Test
    void explorePassScannedAsTopic_rejected_neverSettles() {
        activityOrder(PurchaseKind.EXPLORE_PASS);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals(500, r.get("code"));
        assertTrue(String.valueOf(r.get("msg")).contains("不匹配"), "实际:" + r.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /**
     * ★ ① 的活动单用 {@code type="topic"} 扫同样必须拒 —— 这个洞不是 ③ 独有的。
     * 这条是「在两个分支各补一道 ③ 守卫」那种补法**抓不到**的:它跟 purchase_kind 无关。
     */
    @Test
    void guidedActivityTicketScannedAsTopic_rejected_neverSettles() {
        activityOrder(PurchaseKind.GUIDED_TICKET);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals(500, r.get("code"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** purchase_kind 为 NULL 的存量活动单同样拒 —— 闸的判据是 ownerType,与 purchase_kind 无关。 */
    @Test
    void legacyActivityOrderScannedAsTopic_rejected() {
        activityOrder(null);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals(500, r.get("code"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /**
     * ★ 反方向:owner_type=1 的主题单用 {@code type="activity"} 扫也必须拒。
     * 否则 {@code selectCmsActivityById(topicId)} 会查到一个不相干的活动,
     * 若其主办方恰是扫码人 ⇒ 同样整单结算走人。
     */
    @Test
    void topicOrderScannedAsActivity_rejected_neverSettles() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(1L);      // 主题单
        r.setOwnerId(TOPIC);
        r.setPaymentStatus(2);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(r);

        AjaxResult res = controller.scanQrCode("activity", CODE);

        assertEquals(500, res.get("code"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** ownerType 为 null(数据异常)⇒ fail-closed 拒,别让它进任一分支。 */
    @Test
    void nullOwnerType_rejected() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(null);
        r.setPaymentStatus(2);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(r);

        AjaxResult res = controller.scanQrCode("activity", CODE);

        assertEquals(500, res.get("code"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** ★ 闸在支付态闸**之前** —— 类型不匹配时,连「未支付」这种信息都不该泄漏给乱扫的人。 */
    @Test
    void typeMismatch_checkedBeforePaymentGate() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setPaymentStatus(0);   // 未支付
        when(regSvc.selectByverificationCode(CODE)).thenReturn(r);

        AjaxResult res = controller.scanQrCode("topic", CODE);

        assertTrue(String.valueOf(res.get("msg")).contains("不匹配"),
                "类型不匹配应先于支付态报出,实际:" + res.get("msg"));
    }
}
