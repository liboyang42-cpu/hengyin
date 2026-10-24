package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 核销入口的 **controller 侧**:资格闸 + 拒绝时不留副作用 + 传对入账收款方。
 *
 * <p>V2 跨商家重复核销:核销资格闸只按 topic 过滤中标记录,而 (topic, node) 竞争位允许同一 topic 有
 * 多个中标商家(mode=2 哨兵 node_id=0 更不构成竞争位)⇒ 第二家扫同一张票照样过闸,覆盖 verifier_id、
 * 插自己的台账行、返回「核销成功」。
 *
 * <p>「条件置位 + 入账 + 台账」三步已下沉进 {@code verifyAndSettle} 的事务里 ——
 * 那三步的正确性由 {@link com.chengyinhub.business.service.RegistrationVerifyAndSettleTest} 守。
 * 本类只负责证明 controller 把闸接对了:返 false 就拒、拒了不发积分、收款方传的是对的人。
 *
 * ⚠️ 负控已实测:
 *  - 把 topic 分支的 verifyAndSettle 返回值判断删掉 ⇒ secondMerchantScan_rejected 红
 *  - 把 activity 分支的同一道判断删掉 ⇒ activity_secondScan_rejected 红
 *  - topic 分支 payeeId 传成 cmsRegistration.getMemberId()(玩家)⇒ topicScan_payeeIsScanningMerchant 红
 */
class ApiRegistrationVerifyGateTest {

    private static final Long MEMBER = 77L;      // 扫码的商家
    private static final Long PLAYER = 9L;
    private static final Long REG = 5001L;
    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 400L;
    private static final Long GROUP_ACTIVITY = 401L;
    private static final Long ORGANIZER = 55L;
    private static final Long OWNER = 88L;
    private static final Long CLUB = 610L;      // 承接俱乐部(coop_order.to_id,命名空间与 memberId 不同)
    private static final String CODE = "STATIC-CODE";   // 非动态码 ⇒ 回落 selectByverificationCode

    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private IPointsAwardService pointsSvc;
    private ICmsRegistrationMerchantService merchantSvc;
    private IGrowthService growthSvc;
    private CoopOrderMapper coopOrderMapper;
    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private CmsTopicMapper cmsTopicMapper;
    private IMerchantAccessService merchantAccessService;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);
        pointsSvc = mock(IPointsAwardService.class);
        merchantSvc = mock(ICmsRegistrationMerchantService.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        growthSvc = mock(IGrowthService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService", merchantSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "activityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", pointsSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", growthSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", merchantAccessService);
        when(merchantAccessService.require(MEMBER, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MEMBER, 11L, MEMBER,
                        MerchantRoleCode.MERCHANT_OWNER));
        // 组织槽判据的真源在 ClubMemberServiceImpl(两个 controller 共用)。这里用真实实现 +
        // mock 的 mapper —— 换成 mock 掉 isTopicOrganizer 就成了「mock 掉争议那一环」的假绿。
        ClubMemberServiceImpl realClubMemberSvc = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(realClubMemberSvc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(realClubMemberSvc, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(realClubMemberSvc, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(realClubMemberSvc, "cmsTopicMapper", cmsTopicMapper);
        // 真实主理人判据先回读未删除主题；此套仍测试 club_id 为空的旧合作单路径。
        CmsTopic existingTopic = new CmsTopic();
        existingTopic.setId(TOPIC);
        existingTopic.setDelFlag(0);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(existingTopic);
        IClubAccessService clubAccessService = mock(IClubAccessService.class);
        when(clubAccessService.resolve(anyLong(), anyLong(), eq(null))).thenAnswer(invocation -> {
            Long principal = invocation.getArgument(0);
            Long clubId = invocation.getArgument(1);
            Club club = clubMapper.selectClubById(clubId);
            if (club == null || club.getMemberId() == null) return null;
            if (principal.equals(club.getMemberId())) {
                return new ClubAccessContext(principal, clubId, club.getMemberId(), null,
                        Collections.singleton(ClubRoleCode.CLUB_OWNER));
            }
            ClubMember membership = clubMemberMapper.selectByClubAndMember(clubId, principal);
            if (membership == null || !Integer.valueOf(1).equals(membership.getStatus())) return null;
            String role = Integer.valueOf(1).equals(membership.getRole())
                    ? ClubRoleCode.CLUB_CO_OWNER : ClubRoleCode.CLUB_MEMBER;
            return new ClubAccessContext(principal, clubId, club.getMemberId(), null,
                    Collections.singleton(role));
        });
        ReflectionTestUtils.setField(realClubMemberSvc, "clubAccessService", clubAccessService);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "clubMemberService", realClubMemberSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");
        // [P0-14] ③ 分流用:purchase_kind 为空时回落查主题 product_type(双读)。
        // 本类造的是 ①/② 的单(purchase_kind 未设 ⇒ null),会走到这次回落查询;
        // 返 null ⇒ 判不出 ③ ⇒ 走 ①② 原路,正是本类要测的路径。
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService",
                mock(com.chengyinhub.business.service.ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService",
                mock(com.chengyinhub.business.service.IPerkLedgerService.class));

        // 默认:该主题没有任何合作单 ⇒ 既有 T1 用例的行为完全不变(D22 的三条资格是「或」)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(anyLong())).thenReturn(Collections.emptyList());

        CmsRegistration reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(1L);
        reg.setOwnerId(TOPIC);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(reg);

        // 该商家是中标方(过 audit_status=1 的资格闸)
        CmsRegistrationMerchant winner = new CmsRegistrationMerchant();
        winner.setId(701L);
        winner.setMemberId(MEMBER);
        winner.setTopicId(TOPIC);
        winner.setValidationMethod(1);
        when(merchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(winner));
    }

    @Test
    void legacyCouponScanNeverPerformsFundsWriteEvenWithoutProofCode() {
        AjaxResult withCode = controller.scanQrCode("coupon", "COU-20");
        AjaxResult blankCode = controller.scanQrCode("coupon", "   ");

        assertEquals("请使用专用优惠券核销入口", withCode.get("msg"));
        assertEquals("请使用专用优惠券核销入口", blankCode.get("msg"));
    }

    private void givenActivityRegistration() {
        CmsRegistration actReg = new CmsRegistration();
        actReg.setId(REG);
        actReg.setOwnerType(2L);
        actReg.setOwnerId(ACTIVITY);
        actReg.setMemberId(PLAYER);
        actReg.setPaymentStatus(2);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(actReg);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setMemberId(MEMBER);                   // 扫码人 = 主办方
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    // ---------------------------------------------------------------- topic 分支

    /** 首次核销 ⇒ 成功 + 发积分。 */
    @Test
    void firstScan_succeedsAndAwardsPoints() {
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any())).thenReturn(true);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals("核销成功", r.get("msg"));
        verify(pointsSvc).award(eq(PLAYER), eq(11L), eq(REG), any());
    }

    /**
     * 第二个中标商家扫同一张票 ⇒ 必须拒,且**不得发积分**(拒的同时不能留副作用)。
     * 入账/台账不用在这里断言 —— 它们在 verifyAndSettle 的事务里,返 false 就没发生过。
     */
    @Test
    void secondMerchantScan_rejected() {
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any())).thenReturn(false);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals("该订单已核销", r.get("msg"));
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) r.get("data")).get("alreadyVerified"));
        verify(pointsSvc, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(growthSvc).deliverStoreVerification(REG);
    }

    @Test
    void growthFailureKeepsVerifiedResultAndAuthorizedRetryRecovers() {
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any()))
                .thenReturn(true, false);
        when(growthSvc.deliverStoreVerification(REG))
                .thenThrow(new RuntimeException("injected growth failure"))
                .thenReturn(1);

        AjaxResult first = controller.scanQrCode("topic", CODE);
        AjaxResult retry = controller.scanQrCode("topic", CODE);

        assertEquals("核销成功", first.get("msg"));
        assertEquals("该订单已核销", retry.get("msg"));
        verify(growthSvc, times(2)).deliverStoreVerification(REG);
    }

    /**
     * topic 分支的入账收款方 = **扫码的商家本人**(主题的执行商家在核销时才由商家池确定)。
     * 传错人 = 钱进错口袋。
     */
    @Test
    void topicScan_singleStationUsesScanningMerchantAndExactWinningRow() {
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any())).thenReturn(true);

        controller.scanQrCode("topic", CODE);

        // 唯一中标走的是「服务端自己筛出来的据点」那条(不必回读复核),但仍必须把**那一行**
        // 透传下去 —— 归属落错站和归属为空一样糟,所以断言到 id,不只断言方法被调过。
        ArgumentCaptor<CmsRegistrationMerchant> picked = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(regSvc).verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), picked.capture());
        assertEquals(701L, picked.getValue().getId());
    }

    /** 单据点也必须走事务内据点归属收口,不能只把 validationMethod 裸传给旧接口。 */
    @Test
    void topicScan_singleStationNeverFallsBackToGenericLedgerWrite() {
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any())).thenReturn(true);

        controller.scanQrCode("topic", CODE);

        verify(regSvc).verifyAndSettleForOwnStation(any(), eq(MEMBER), eq(MEMBER), any());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** 同一路线中标多个据点时,主扫码入口只能返回候选,不能默取第一行完成核销。 */
    @Test
    void multipleWinningStations_requireExplicitChoiceAndKeepCodeNonSuccess() {
        CmsRegistrationMerchant first = new CmsRegistrationMerchant();
        first.setId(701L);
        first.setMemberId(MEMBER);
        first.setTopicId(TOPIC);
        first.setNodeId(81L);
        first.setAddressName("东门咖啡");
        first.setValidationMethod(1);
        CmsRegistrationMerchant second = new CmsRegistrationMerchant();
        second.setId(702L);
        second.setMemberId(MEMBER);
        second.setTopicId(TOPIC);
        second.setNodeId(82L);
        second.setAddressName("西岸书店");
        second.setValidationMethod(3);
        when(merchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Arrays.asList(first, second));

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals(500, r.get("code"), "未选择据点时 code 必须保持非成功");
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) r.get("data");
        assertEquals(Boolean.TRUE, data.get("needStationChoice"));
        java.util.List<?> stations = (java.util.List<?>) data.get("stations");
        assertEquals(2, stations.size());
        assertEquals(701L, ((java.util.Map<?, ?>) stations.get(0)).get("registrationMerchantId"));
        assertEquals("东门咖啡", ((java.util.Map<?, ?>) stations.get(0)).get("name"));
        // 需要选择时,三条核销入口一个都不许被调过 —— 少列一条就等于给它开了后门
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
        verify(regSvc, never()).verifyAndSettleForStation(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettleForOwnStation(any(), anyLong(), anyLong(), any());
    }

    /** 选择端点把登录商家和 registrationMerchantId 原样交给事务内归属闸。 */
    @Test
    void selectedStationEndpointPassesExactChoiceToTransactionalGate() {
        when(regSvc.verifyAndSettleForStation(any(), eq(MEMBER), eq(702L))).thenReturn(true);

        AjaxResult r = controller.scanQrCodeStation(CODE, 702L);

        assertEquals("核销成功", r.get("msg"));
        verify(regSvc).verifyAndSettleForStation(any(), eq(MEMBER), eq(702L));
    }

    @Test
    void operatorSelectedStationUsesMerchantOwnerForTransactionalOwnershipGate() {
        when(merchantAccessService.require(MEMBER, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MEMBER, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(regSvc.verifyAndSettleForStation(any(), eq(OWNER), eq(702L), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN))).thenReturn(true);

        AjaxResult result = controller.scanQrCodeStation(CODE, 702L);

        assertEquals("核销成功", result.get("msg"));
        verify(regSvc).verifyAndSettleForStation(any(), eq(OWNER), eq(702L), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN));
    }

    /** 员工以 principal 授权，既有核销/结算业务口径仍必须落到商家 owner memberId。 */
    @Test
    void operatorTopicScanSeparatesActorFromMerchantOwner() {
        when(merchantAccessService.require(MEMBER, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MEMBER, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        CmsRegistrationMerchant ownerWinner = new CmsRegistrationMerchant();
        ownerWinner.setId(703L);
        ownerWinner.setMemberId(OWNER);
        ownerWinner.setTopicId(TOPIC);
        ownerWinner.setValidationMethod(1);
        when(merchantSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant query = invocation.getArgument(0);
            return OWNER.equals(query.getMemberId())
                    ? Collections.singletonList(ownerWinner) : Collections.emptyList();
        });
        when(regSvc.verifyAndSettleForOwnStation(any(), eq(OWNER), eq(OWNER), any(), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN)))
                .thenReturn(true);

        AjaxResult result = controller.scanQrCode("topic", CODE);

        assertEquals("核销成功", result.get("msg"));
        verify(merchantAccessService).require(MEMBER, MerchantPermission.VERIFY);
        verify(regSvc).verifyAndSettleForOwnStation(any(), eq(OWNER), eq(OWNER), any(), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN));
    }

    /** 权限拒绝必须保留 403，不能被 scan_qr_code 的大 catch 吞成普通 500。 */
    @Test
    void merchantAccessDenialInMixedTopicRouteKeepsForbiddenStatusAndHasNoWrite() {
        when(merchantAccessService.require(MEMBER, MerchantPermission.VERIFY))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        AjaxResult result = controller.scanQrCode("topic", CODE);

        assertEquals(HttpStatus.FORBIDDEN, result.get("code"));
        verify(regSvc, never()).verifyAndSettleForStation(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettleForOwnStation(any(), anyLong(), anyLong(), any());
    }

    /**
     * 第二轮拍板第 6 条:答题验票是「玩家扫商家的游戏码」,券走 /api/play/checkin 发给玩家本人。
     * 商家侧 verify_answer 是第二条发同一张模板券的路径(还发给商家 owner),已停用:
     * 不鉴商家权限、不碰报名服务、不发券、不改扫码记录。
     */
    @Test
    void verifyAnswerIsRetiredAndNeverGrantsAnything() {
        AjaxResult result = controller.VerifyAnswer("scan-1", "42");

        assertEquals(500, result.get("code"));
        assertEquals("请在游戏内扫码完成", result.get("msg"));
        verifyNoInteractions(merchantAccessService);
        verifyNoInteractions(regSvc);
    }

    @Test
    void operatorActivityScanSeparatesActorFromMerchantOwner() {
        givenActivityRegistration();
        CmsActivity ownerActivity = new CmsActivity();
        ownerActivity.setId(ACTIVITY);
        ownerActivity.setMemberId(OWNER);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(ownerActivity);
        when(merchantAccessService.require(MEMBER, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MEMBER, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(regSvc.verifyAndSettle(any(), eq(OWNER), eq(OWNER), any(), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN))).thenReturn(true);
        AjaxResult result = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", result.get("msg"));
        verify(regSvc).verifyAndSettle(any(), eq(OWNER), eq(OWNER), any(), eq(MEMBER),
                eq(MerchantRoleCode.MERCHANT_CHECKIN));
    }

    /** 事务内归属闸拒绝越权时,前端必须拿到明确原因且不发奖励。 */
    @Test
    void selectedStationOwnershipRejectionIsVisibleAndHasNoReward() {
        when(regSvc.verifyAndSettleForStation(any(), eq(MEMBER), eq(999L)))
                .thenThrow(new IllegalStateException("所选据点不属于当前商家或路线"));

        AjaxResult r = controller.scanQrCodeStation(CODE, 999L);

        assertEquals(500, r.get("code"));
        assertEquals("所选据点不属于当前商家或路线", r.get("msg"));
        verify(pointsSvc, never()).award(anyLong(), anyLong(), anyLong(), any());
    }

    /** 重复/并发选择由同一个 CAS 结果显式返回 alreadyVerified,不能二次放行。 */
    @Test
    void repeatedStationChoiceReturnsStructuredDuplicateResult() {
        when(regSvc.verifyAndSettleForStation(any(), eq(MEMBER), eq(702L))).thenReturn(false);

        AjaxResult r = controller.scanQrCodeStation(CODE, 702L);

        assertEquals("该订单已核销", r.get("msg"));
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) r.get("data")).get("alreadyVerified"));
        verify(pointsSvc, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(growthSvc).deliverStoreVerification(REG);
    }

    /** 未支付/已退款单仍先被资金闸拦下,轮不到核销闸(确认加闸没把既有闸挤掉)。 */
    @Test
    void unpaidRegistration_stillRejectedByPaymentGate() {
        CmsRegistration unpaid = new CmsRegistration();
        unpaid.setId(REG);
        unpaid.setOwnerType(1L);
        unpaid.setOwnerId(TOPIC);
        unpaid.setPaymentStatus(3);                                        // 已退款
        when(regSvc.selectByverificationCode(CODE)).thenReturn(unpaid);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertTrue(String.valueOf(r.get("msg")).contains("无法核销"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------- D22:type0 合作单商家(供给槽的第二种入槽)

    /** 没有中标报名行 ⇒ 该商家在该主题上没有任何合作单时,照旧拒。 */
    @Test
    void noWinnerRow_noCoopOrder_rejected() {
        givenNoWinnerRow();

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals("查询失败", r.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /**
     * ★ type0 主通路:商家**只有合作单、没有报名行**(acceptInviteWithLock 从不建报名行、reg_id 恒空)。
     * 定稿 §6.5-C「结算只认合作单」⇒ 他就该能核销;#58 之后他基数恒 0 正是因为这道闸把他挡在门外。
     * 全仓此前没有任何测试构造过这个形状 —— CoopSettlementPerMerchantTest 构造了(regId 默认 null),
     * 却用 mock 让 heads>0,断言了一个生产不可达的前提(§3.3 的假绿本体)。
     */
    @Test
    void type0CoopMerchant_withoutWinnerRow_isEligible() {
        givenNoWinnerRow();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(coopOrder("merchant", MEMBER)));
        when(regSvc.verifyAndSettle(any(), anyLong(), any(), any())).thenReturn(true);

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals("核销成功", r.get("msg"));
        ArgumentCaptor<Long> verifier = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> payee = ArgumentCaptor.forClass(Long.class);
        verify(regSvc).verifyAndSettle(any(), verifier.capture(), payee.capture(), any());
        assertEquals(MEMBER, verifier.getValue(), "核销人 = 扫码的 type0 商家");
        assertEquals(MEMBER, payee.getValue(), "供给槽的收款方 = 他本人,与 T1 同口径");
    }

    /** 别家的合作单不给我资格 —— 否则同主题任一合作商家都能核销别人的票。 */
    @Test
    void otherMerchantsCoopOrder_notEligible() {
        givenNoWinnerRow();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(coopOrder("merchant", ORGANIZER)));

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /**
     * to_type='club' 的合作单**不**给商家资格 —— toId 的命名空间不同(club 单的 toId 是 clubId,
     * 不是 memberId),不判 toType 就会让「clubId 恰好等于某 memberId」的商家凭空拿到核销权。
     */
    @Test
    void clubCoopOrder_doesNotMakeMerchantEligible() {
        givenNoWinnerRow();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(coopOrder("club", MEMBER)));
        when(clubMapper.selectClubById(MEMBER)).thenReturn(null);

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /**
     * ★★ 组织槽有人时,**中标商家也不许扫玩家票** —— 这是自审抓出来的静默漏付。
     *
     * verifyIfNotVerified 是一次性 CAS(where verification_status<>1),谁先扫谁把核销位永久占死。
     * 商家抢先扫 ⇒ 俱乐部再扫返 0 ⇒ 该票永远不会有 member_id=领队 的 type=1 台账行 ⇒ 结算时它不计入
     * 任何团的基数,却仍在 totalSales 里 ⇒ merchantTotal 偏小、残差 margin 偏大 ⇒ 守恒断言照样通过
     * ⇒ 钱静默流进发起人毛利、零告警。只在结算侧改口径救不了,必须在这道闸上挡。
     */
    @Test
    void winnerMerchant_cannotScanPlayer_whenOrganizerPresent() {
        givenOrganizerClub();                       // 组织槽有人
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(null);
        // MEMBER 仍是中标商家(setUp 里的默认 winner 行还在)

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertEquals("本主题由俱乐部带团，请扫领队出示的团码", r.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /** 组织槽有人时,type0 合作单商家同样不许扫玩家票(他该扫团码)。 */
    @Test
    void coopMerchant_cannotScanPlayer_whenOrganizerPresent() {
        givenNoWinnerRow();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                coopOrder("club", CLUB), coopOrder("merchant", MEMBER)));
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(null);

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    // ------------------------------------------------- D22:承接俱乐部(组织槽)核销玩家 = T2a

    /** 组织槽由俱乐部按精确 activity 核销玩家票，专用事务固定不写 settlement_detail。 */
    @Test
    void organizerLeader_isEligible_andPayeeIsNull() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        givenGroupActivity(CLUB);
        when(regSvc.verifyAndSettleForGroupActivity(any(), anyLong(), anyLong())).thenReturn(true);

        AjaxResult r = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("核销成功", r.get("msg"));
        ArgumentCaptor<Long> verifier = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> activityId = ArgumentCaptor.forClass(Long.class);
        verify(regSvc).verifyAndSettleForGroupActivity(any(), verifier.capture(), activityId.capture());
        assertEquals(MEMBER, verifier.getValue(), "台账记的核销人 = 主理人");
        assertEquals(GROUP_ACTIVITY, activityId.getValue(), "玩家核销必须绑定当前带队场次");
        verify(merchantAccessService, never()).require(anyLong(), any());
    }

    @Test
    void organizerLeader_missingTopicCannotSettleOrAward() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        givenGroupActivity(CLUB);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(null);

        AjaxResult result = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("仅俱乐部主理人可核销玩家票", result.get("msg"));
        verify(cmsTopicMapper).selectCmsTopicById(TOPIC);
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
        verify(regSvc, never()).verifyAndSettleForOwnStation(any(), anyLong(), anyLong(), any());
        verifyNoInteractions(pointsSvc, growthSvc);
    }

    @Test
    void organizerLeader_deletedTopicCannotSettleOrAward() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        givenGroupActivity(CLUB);
        CmsTopic deletedTopic = new CmsTopic();
        deletedTopic.setId(TOPIC);
        deletedTopic.setDelFlag(1);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(deletedTopic);

        AjaxResult result = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("仅俱乐部主理人可核销玩家票", result.get("msg"));
        verify(cmsTopicMapper).selectCmsTopicById(TOPIC);
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
        verify(regSvc, never()).verifyAndSettleForOwnStation(any(), anyLong(), anyLong(), any());
        verifyNoInteractions(pointsSvc, growthSvc);
    }

    /**
     * 规则 1：同 topic 下 activity 是独立团。组织槽玩家票必须带场次，且走专用事务写入场次事实，
     * 不能再用普通 verifyAndSettle 仅留下「谁扫了」的模糊台账。
     */
    @Test
    void organizerLeader_verifiesAgainstExactActivity() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        givenGroupActivity(CLUB);
        when(regSvc.verifyAndSettleForGroupActivity(any(), eq(MEMBER), eq(GROUP_ACTIVITY))).thenReturn(true);

        AjaxResult r = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("核销成功", r.get("msg"));
        verify(regSvc).verifyAndSettleForGroupActivity(any(), eq(MEMBER), eq(GROUP_ACTIVITY));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /** 动态主题票的 type 必须由服务端验签；验签后才能绑定当前带队场次。 */
    @Test
    void organizerLeader_dynamicTopicTicket_bindsExactActivity() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        givenGroupActivity(CLUB);
        CmsRegistration topicRegistration = new CmsRegistration();
        topicRegistration.setId(REG);
        topicRegistration.setOwnerType(1L);
        topicRegistration.setOwnerId(TOPIC);
        topicRegistration.setMemberId(PLAYER);
        topicRegistration.setPaymentStatus(2);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(topicRegistration);
        when(regSvc.verifyAndSettleForGroupActivity(any(), eq(MEMBER), eq(GROUP_ACTIVITY))).thenReturn(true);
        String code = VerifyDynCodeUtil.issue(REG, "topic", System.currentTimeMillis(), 300_000L,
                1L, "test-secret");

        AjaxResult r = controller.scanGroupMemberTicket(code, GROUP_ACTIVITY);

        assertEquals("核销成功", r.get("msg"));
        verify(regSvc).verifyAndSettleForGroupActivity(any(), eq(MEMBER), eq(GROUP_ACTIVITY));
    }

    /** 场次票走 D9 全团结算，不能误计进主题商家的 D22 分账。 */
    @Test
    void organizerLeader_dynamicActivityTicket_rejectedBeforeSettlement() {
        String code = VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis(), 300_000L,
                1L, "test-secret");

        AjaxResult r = controller.scanGroupMemberTicket(code, GROUP_ACTIVITY);

        assertEquals("本场次只可核销主题票", r.get("msg"));
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
    }

    /** 组织槽漏传 activityId 必须 fail closed，不能退化为按同 topic 的领队猜场次。 */
    @Test
    void organizerLeader_withoutActivity_rejected() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));

        AjaxResult r = controller.scanQrCode("topic", CODE);

        assertTrue(String.valueOf(r.get("msg")).contains("场次"));
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /** 同主题另一承接俱乐部的场次不能被当前俱乐部主理人用来落账。 */
    @Test
    void organizerLeader_otherClubActivity_rejected() {
        Long otherClub = 611L;
        givenNoWinnerRow();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                coopOrder("club", CLUB), coopOrder("club", otherClub)));
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(MEMBER));
        CmsActivity activity = new CmsActivity();
        activity.setId(GROUP_ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(otherClub);
        when(activitySvc.selectCmsActivityById(GROUP_ACTIVITY)).thenReturn(activity);

        AjaxResult r = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("该带队场次不可核销", r.get("msg"));
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
    }

    /** 管理员可出示团码，但核销玩家票会触发团场结算，必须收窄到主理人。 */
    @Test
    void organizerAdmin_cannotVerifyPlayerTicketOrTriggerSettlement() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));   // 主理人是别人
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(clubMember(1, 1));
        givenGroupActivity(CLUB);

        AjaxResult r = controller.scanQrCode("topic", CODE, GROUP_ACTIVITY);

        assertEquals("仅俱乐部主理人可核销玩家票", r.get("msg"));
        verify(regSvc, never()).verifyAndSettleForGroupActivity(any(), anyLong(), anyLong());
    }

    /** 普通成员(role=0)不是管理员 ⇒ 拒。这是新入口的越权面,漏了就是全团任意成员能核销。 */
    @Test
    void organizerPlainMember_rejected() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(clubMember(0, 1));

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /** 非正常态(status≠1)的管理员 ⇒ 拒。 */
    @Test
    void organizerAdmin_notActive_rejected() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(clubMember(1, 0));

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    /** 跟该主题无关的俱乐部的主理人 ⇒ 拒(资格必须挂在**本主题的**承接俱乐部上)。 */
    @Test
    void otherClubLeader_rejected() {
        givenNoWinnerRow();
        givenOrganizerClub();
        when(clubMapper.selectClubById(CLUB)).thenReturn(clubOwnedBy(ORGANIZER));
        when(clubMemberMapper.selectByClubAndMember(CLUB, MEMBER)).thenReturn(null);

        controller.scanQrCode("topic", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), any(), any());
    }

    // ---------------------------------------------------------------- 夹具

    private void givenNoWinnerRow() {
        when(merchantSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
    }

    private void givenOrganizerClub() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(coopOrder("club", CLUB)));
    }

    private static CoopOrder coopOrder(String toType, Long toId) {
        CoopOrder o = new CoopOrder();
        o.setToType(toType);
        o.setToId(toId);
        return o;
    }

    private static Club clubOwnedBy(Long leaderMemberId) {
        Club c = new Club();
        c.setId(CLUB);
        c.setMemberId(leaderMemberId);
        return c;
    }

    private static ClubMember clubMember(int role, int status) {
        ClubMember cm = new ClubMember();
        cm.setClubId(CLUB);
        cm.setMemberId(MEMBER);
        cm.setRole(role);
        cm.setStatus(status);
        return cm;
    }

    private void givenGroupActivity(Long clubId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(GROUP_ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(clubId);
        when(activitySvc.selectCmsActivityById(GROUP_ACTIVITY)).thenReturn(activity);
    }

    // ---------------------------------------------------------------- activity 分支

    /** activity 分支同样有重复核销闸(主办方重复扫会重跑入账链路)。 */
    @Test
    void activity_secondScan_rejected() {
        givenActivityRegistration();
        when(regSvc.verifyAndSettle(any(), eq(MEMBER), anyLong(), any())).thenReturn(false);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("该订单已核销", r.get("msg"));
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) r.get("data")).get("alreadyVerified"));
        verify(pointsSvc, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(growthSvc).deliverStoreVerification(REG);
    }

    /** activity 分支的收款方 = 活动主办方(与 topic 分支口径不同,别抄混)。 */
    @Test
    void activityScan_payeeIsActivityOwner() {
        givenActivityRegistration();
        when(regSvc.verifyAndSettle(any(), anyLong(), anyLong(), any())).thenReturn(true);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", r.get("msg"));
        ArgumentCaptor<Long> payee = ArgumentCaptor.forClass(Long.class);
        verify(regSvc).verifyAndSettle(any(), eq(MEMBER), payee.capture(), any());
        assertEquals(MEMBER, payee.getValue(), "活动核销的收款方 = 活动 memberId(本例中恰为扫码人)");
    }

    /** 非主办方扫活动票 ⇒ 连核销闸都不该走到。 */
    @Test
    void activity_nonOwnerScan_rejected() {
        givenActivityRegistration();
        CmsActivity other = new CmsActivity();
        other.setId(ACTIVITY);
        other.setMemberId(ORGANIZER);                  // 主办方是别人
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(other);

        controller.scanQrCode("activity", CODE);

        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** F-R2F-01:俱乐部场次开场前 60 分钟之外不能整单核销(避免提前核销被算作已到场/已交付)。 */
    @Test
    void clubSessionScanBeforeWindow_rejectedWithoutSettlement() {
        givenActivityRegistration();
        CmsActivity club = new CmsActivity();
        club.setId(ACTIVITY);
        club.setMemberId(MEMBER);
        club.setClubId(CLUB);
        club.setStartDate(new java.util.Date(System.currentTimeMillis() + 2L * 24 * 3600 * 1000));
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(club);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("俱乐部场次开始前60分钟内才可核销", r.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    /** PR10 复审:时间窗在权限之后 —— 无权扫码方对提前的俱乐部场次拿到的是权限拒绝,不是开场时间提示。 */
    @Test
    void clubSessionScanByUnauthorizedMerchant_getsPermissionDenialBeforeTimingHint() {
        givenActivityRegistration();
        CmsActivity club = new CmsActivity();
        club.setId(ACTIVITY);
        club.setMemberId(OWNER);
        club.setClubId(CLUB);
        club.setStartDate(new java.util.Date(System.currentTimeMillis() + 2L * 24 * 3600 * 1000));
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(club);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("该店无权核销本场玩家票；俱乐部带队请扫描领队团码", r.get("msg"));
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any(), anyLong(), any());
    }

    /** 窗口内(含已开场)与非俱乐部活动照常核销。 */
    @Test
    void clubSessionVerificationWindowBoundary() {
        java.util.Date now = new java.util.Date(1_800_000_000_000L);
        CmsActivity club = new CmsActivity();
        club.setClubId(CLUB);
        club.setStartDate(new java.util.Date(now.getTime() + 60L * 60 * 1000));
        assertNull(com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.clubSessionVerificationTooEarly(club, now));
        club.setStartDate(new java.util.Date(now.getTime() + 60L * 60 * 1000 + 1));
        assertEquals("俱乐部场次开始前60分钟内才可核销",
                com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.clubSessionVerificationTooEarly(club, now).get("msg"));
        club.setStartDate(new java.util.Date(now.getTime() - 1));
        assertNull(com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.clubSessionVerificationTooEarly(club, now));
        CmsActivity ordinary = new CmsActivity();
        ordinary.setStartDate(new java.util.Date(now.getTime() + 7L * 24 * 3600 * 1000));
        assertNull(com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.clubSessionVerificationTooEarly(ordinary, now));
    }
}
