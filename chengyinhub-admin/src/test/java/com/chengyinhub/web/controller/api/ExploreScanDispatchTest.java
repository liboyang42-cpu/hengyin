package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.when;

/**
 * P0-14 ③ 扫码分流 —— <b>从生产入口 scanQrCode 进</b>,证明这条路径真的走到了 redeemChapter。
 *
 * <p><b>这个类的存在理由,是一次真实的翻车</b>:初版把 ③ 的分流写进了 scanQrCode 的
 * {@code type="topic"} 分支,而 ③ 定死是 {@code owner_type=2 / owner_id=activityId} 的**活动单**
 * (createRegistration 落 ownerType=2;ApiRegistrationController 把 ownerType==1 无条件
 * early-return 进 createSelfPlayPass)⇒ ③ 的核销码只会走 {@code type="activity"} 分支
 * ⇒ <b>那个分流永不触发,P0-14 等于没接</b>,而 service 层的 resolveRedeemableChapters 单测**全绿**。
 *
 * <p>这正是交接文档 §4 的血教训:
 * <blockquote>「负控证明的是『删了闸会红』,不是『闸在路径上』。」
 * 同一道闸放错层放了两次,5 条负控全绿 + 源码断言全绿,洞照样在。</blockquote>
 * ⇒ 必须有一条**从 controller 入口进**的用例。就是本类。
 *
 * <p>⚠️ 两个分支的 ownerId 语义不同,是这次翻车的根源:
 * <ul>
 *   <li>{@code type="activity"} 分支:{@code selectCmsActivityById(reg.getOwnerId())} ⇒ ownerId = activityId</li>
 *   <li>{@code type="topic"} 分支:{@code setTopicId(reg.getOwnerId())} ⇒ ownerId = topicId</li>
 * </ul>
 * 所以 ③ 的 topicId 只能从 {@code activity.getTopicId()} 取,拿 ownerId 当 topicId 必然查空。
 */
class ExploreScanDispatchTest {
    private static java.util.Map<Long, Long> pendingCandidates(Long... chapters) {
        java.util.Map<Long, Long> candidates = new java.util.TreeMap<>();
        for (Long chapter : chapters) candidates.put(chapter, null);
        return candidates;
    }


    private static final Long MERCHANT = 500L;   // 扫码的入驻商家(= memberId)
    private static final Long PLAYER = 77L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long TOPIC = 700L;
    private static final Long CH1 = 101L;
    private static final Long CH2 = 102L;
    private static final String CODE = "STATIC-CODE";

    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private ICmsTopicService topicSvc;
    private IPerkLedgerService perkSvc;
    private IPointsAwardService pointsSvc;
    private ICmsTopicChapterService chapterSvc;
    private IMerchantAccessService merchantAccessService;
    private ApiRegistrationController controller;

    /** 造一条章节(只填选章面板用得上的两个字段)。 */
    private static CmsTopicChapter chapter(Long id, String name) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setName(name);
        return c;
    }

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);
        topicSvc = mock(ICmsTopicService.class);
        perkSvc = mock(IPerkLedgerService.class);
        pointsSvc = mock(IPointsAwardService.class);
        chapterSvc = mock(ICmsTopicChapterService.class);
        merchantAccessService = mock(IMerchantAccessService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", topicSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", pointsSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicChapterService", chapterSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService",
                mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", merchantAccessService);
        when(merchantAccessService.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_OWNER));

        // ③ 的报名单:owner_type=2 / owner_id=activityId —— 这是 ③ 的唯一形态
        CmsRegistration reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        reg.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(reg);

        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        act.setEndDate(new java.util.Date(System.currentTimeMillis() + 86400000L));
        act.setMemberId(999L);   // ★ 主办方是别人,不是扫码的商家
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
    }

    // ------------------------------------------------- 分流必须真的发生

    /**
     * ★ 本类的核心:③ 的码从 type="activity" 进,必须走到 redeemChapter,
     * 且**绝不能**走 verifyAndSettle(那会把整单票款结算给扫码的这一家 = 资损)。
     *
     * <p>初版把分流放在 topic 分支时,这条用例红(会走进 verifyAndSettle 或掉出去)。
     */
    @Test
    void explorePass_scannedAsActivity_dispatchesToRedeemChapter() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 36));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", r.get("msg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(36, data.get("completionEarnedXp"), "grantOnce 首次入账数必须进入核销回执");
        verify(regSvc).redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any());
        // ★ ③ 绝不走整单结算:一张票跨 N 家核销,整单入账给扫码那家 = 最后扫的白拿全额
        verify(regSvc, never()).verifyAndSettle(any(), anyLong(), anyLong(), any());
    }

    @Test
    void exploreOperatorUsesMerchantOwnerForOfferAndOwnMemberIdForRedemptionAudit() {
        Long operator = 501L;
        doReturn(operator).when(controller).getAppUserId();
        when(merchantAccessService.require(operator, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(operator, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(operator),
                eq(MerchantRoleCode.MERCHANT_CHECKIN), any()))
                .thenReturn(new ExploreChapterRedemptionResult(6L, 0));

        AjaxResult result = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", result.get("msg"));
        verify(regSvc).redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(operator),
                eq(MerchantRoleCode.MERCHANT_CHECKIN), any());
    }

    @Test
    void exploreChapterOperatorUsesOwnerScopeAndActorRole() {
        Long operator = 501L;
        doReturn(operator).when(controller).getAppUserId();
        when(merchantAccessService.require(operator, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(operator, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_CHECKIN));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(operator),
                eq(MerchantRoleCode.MERCHANT_CHECKIN), any()))
                .thenReturn(new ExploreChapterRedemptionResult(7L, 0));

        AjaxResult result = controller.scanQrCodeChapter(CODE, CH1);

        assertEquals("核销成功", result.get("msg"));
        verify(regSvc).redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(operator),
                eq(MerchantRoleCode.MERCHANT_CHECKIN), any());
    }

    /**
     * ★ topicId 必须来自 activity.getTopicId(),不是 registration.getOwnerId()。
     * ③ 的 ownerId 是 activityId(800),topicId 是 700 —— 传错了 selectByTopic 必然查空。
     */
    @Test
    void explorePass_resolvesChaptersWithActivityTopicId_notOwnerId() {
        when(perkSvc.resolveScannableChapters(anyLong(), anyLong(), anyLong()))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 0));

        controller.scanQrCode("activity", CODE);

        verify(perkSvc).resolveScannableChapters(REG, TOPIC, MERCHANT);
        verify(perkSvc, never()).resolveScannableChapters(REG, ACTIVITY, MERCHANT);
    }

    /**
     * ★ 分流必须在「活动主办方」鉴权之前:③ 的扫码方是入驻商家,不是主办方。
     * 本用例里 activity.memberId=999 而扫码者是 500 —— 若分流落在鉴权之后,商家会被当「不是主办方」掉出去。
     */
    @Test
    void explorePass_scannedByMerchantNotActivityOwner_stillRedeems() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 0));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", r.get("msg"));
    }

    /** merchantId 语义 = memberId,直接用 getAppUserId(),不走 mms_merchant.id 解析。 */
    @Test
    void explorePass_passesMemberIdAsMerchantId() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 0));

        controller.scanQrCode("activity", CODE);

        verify(regSvc).redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any());
    }

    // ------------------------------------------------- 交集三分

    /** 交集 ≥2 ⇒ 返候选让商家选,**不许默默取第一个**(取错章 = 玩家在另一章白跑)。 */
    @Test
    void multipleCandidates_returnsChoiceAndDoesNotRedeem() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertTrue(String.valueOf(r.get("msg")).contains("选择"));
        verify(regSvc, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * ★ [P0-2] 交集 ≥2 **绝不能返 success** —— 核销根本没发生。
     *
     * <p><b>这条用例的存在理由,是上面那条 {@link #multipleCandidates_returnsChoiceAndDoesNotRedeem}
     * 的假绿</b>:它只断言 msg 含「选择」+ 没调 redeem,**从没断言 code** ⇒ 返 success 还是 error
     * 它一样绿。于是「返 success」这个 bug 带着一条全绿的用例上了生产整整一轮:
     * 两个扫码页(merchant/member index.js)都只判 {@code code == '200'} ⇒ 弹「验票成功」
     * ⇒ 商家放人走,entitlement 从没消费 ⇒ 同一张票可反复「核销」。
     *
     * <p>⇒ 教训:{@code assertTrue(msg.contains(...))} 证明的是「文案对」,不是「结果对」。
     * 前端真正读的是 code,断言就必须落在 code 上。
     */
    @Test
    void multipleCandidates_mustNotReturnSuccess() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        // 🔴 负控实测:把 redeemExploreChapter 里的 error 改回 success ⇒ 本条红,上面那条仍绿。
        assertEquals(500, r.get("code"));
    }

    /**
     * ★ [P0-2] 改 error 之后,选章载荷必须**原样还在** —— 前端要靠它弹选章面板。
     *
     * <p>{@code AjaxResult.error(String, Object)} 这个重载存在,所以 error 一样能带 data。
     * 若哪天有人图省事改成 {@code AjaxResult.error("请选择要核销的章节")}(丢掉 data),
     * 商家会收到一句无法执行的话:让他选章,却没告诉他有哪些章可选 ⇒ 本条红。
     *
     * <p>⚠️ 前端的判据定为 {@code data.needChapterChoice} 而**不是** code —— 故这两个 key
     * 是前后端契约的一部分,改名 = 前端静默失效。
     */
    @SuppressWarnings("unchecked")
    @Test
    void multipleCandidates_carriesChoicePayloadInErrorBody() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertNotNull(data, "error 必须带 data,否则前端拿不到候选章节");
        assertEquals(Boolean.TRUE, data.get("needChapterChoice"));
        assertEquals(Arrays.asList(CH1, CH2), data.get("chapterIds"));
    }

    /**
     * ★ [P0-2] 选章载荷必须带**章节名**,不能只给 id。
     *
     * <p>商家认的是「第二章 · 外滩夜行」,不是 101。只给 id 的面板 = 让他闭眼选,
     * 而选错章**不可逆**:entitlement 当场消费、玩家在另一章白跑。
     */
    @SuppressWarnings("unchecked")
    @Test
    void multipleCandidates_carriesChapterNames() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));
        when(chapterSvc.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(chapter(CH1, "第二章 · 外滩夜行"), chapter(CH2, "第五章 · 老码头咖啡")));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) data.get("chapters");
        assertEquals(2, chapters.size());
        // 同序等长:前端可能按下标回取 id,错序 = 商家点 A 核销了 B
        assertEquals(CH1, chapters.get(0).get("id"));
        assertEquals("第二章 · 外滩夜行", chapters.get(0).get("name"));
        assertEquals(CH2, chapters.get(1).get("id"));
        assertEquals("第五章 · 老码头咖啡", chapters.get(1).get("name"));
        // 老 key 必须还在:老客户端还在用它
        assertEquals(Arrays.asList(CH1, CH2), data.get("chapterIds"));
    }

    /**
     * ★ 章节名查不到 ⇒ 兜底成「章节 <id>」,**绝不留空**。
     *
     * <p>空条目在面板上是个点不动的空行,商家会以为卡死 —— 而他其实还欠着一次核销。
     * 丑名字能用,空名字不能用。
     */
    @SuppressWarnings("unchecked")
    @Test
    void chapterNameMissing_fallsBackToIdLabel_neverBlank() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));
        // CH1 有名字、CH2 名字是空串(脏数据)⇒ CH2 必须兜底,不能返空
        when(chapterSvc.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(chapter(CH1, "第二章 · 外滩夜行"), chapter(CH2, "")));

        AjaxResult r = controller.scanQrCode("activity", CODE);

        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) data.get("chapters");
        assertEquals("第二章 · 外滩夜行", chapters.get(0).get("name"));
        assertEquals("章节 " + CH2, chapters.get(1).get("name"));
    }

    /** 章节名一次按 topicId 查完,**不按 id 逐个查**(N+1 会随「一商家多章」长大)。 */
    @Test
    void chapterNames_resolvedInOneQuery_notNPlusOne() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1, CH2));
        when(chapterSvc.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(chapter(CH1, "A"), chapter(CH2, "B")));

        controller.scanQrCode("activity", CODE);

        verify(chapterSvc, times(1)).selectCmsTopicChapterList(any());
        verify(chapterSvc, never()).selectCmsTopicChapterById(anyLong());
    }

    /** 单章直接核销 ⇒ **零额外查询**:不需要选章就不该为了名字去查库。 */
    @Test
    void singleCandidate_doesNotQueryChapterNames() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 0));

        controller.scanQrCode("activity", CODE);

        verify(chapterSvc, never()).selectCmsTopicChapterList(any());
    }

    /** 交集 0 ⇒ 明确报错,不核销。 */
    @Test
    void noCandidates_rejectsWithoutRedeem() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(Collections.<Long, Long>emptyMap());

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals(500, r.get("code"));
        verify(regSvc, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------- ①/② 不误伤

    /**
     * ★ ① 的活动单必须继续走 verifyAndSettle 原路,绝不能被 ③ 分流劫走。
     * 把分流判据写错(如无条件走 ③)时只有这条红。
     */
    @Test
    void guidedTicket_stillGoesToVerifyAndSettle() {
        CmsRegistration guided = new CmsRegistration();
        guided.setId(REG);
        guided.setOwnerType(2L);
        guided.setOwnerId(ACTIVITY);
        guided.setMemberId(PLAYER);
        guided.setPaymentStatus(2);
        guided.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(guided);
        // ① 由主办方扫码
        doReturn(999L).when(controller).getAppUserId();
        when(regSvc.verifyAndSettle(any(), anyLong(), anyLong(), any())).thenReturn(true);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertEquals("核销成功", r.get("msg"));
        verify(regSvc).verifyAndSettle(any(), eq(999L), eq(999L), any());
        verify(regSvc, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * purchase_kind 为 NULL ⇒ 回落查 **activity.topicId** 对应主题的 product_type。
     * ⚠️ 不是查 registration.getOwnerId()(那是 activityId)。
     */
    @Test
    void nullPurchaseKind_fallsBackToTopicProductTypeViaActivity() {
        CmsRegistration legacy = new CmsRegistration();
        legacy.setId(REG);
        legacy.setOwnerType(2L);
        legacy.setOwnerId(ACTIVITY);
        legacy.setMemberId(PLAYER);
        legacy.setPaymentStatus(2);
        legacy.setPurchaseKind(null);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(legacy);

        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(ProductType.FREE_EXPLORE);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(t);   // ★ 用 TOPIC 不是 ACTIVITY
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(pendingCandidates(CH1));
        when(regSvc.redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new ExploreChapterRedemptionResult(5L, 0));

        controller.scanQrCode("activity", CODE);

        verify(topicSvc).selectCmsTopicById(TOPIC);
        verify(regSvc).redeemExploreChapterAndRecord(
                any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any());
    }

    /** 已退款/未支付单在 ③ 分流**之前**就该被资损闸拒掉。 */
    @Test
    void refundedRegistration_rejectedBeforeDispatch() {
        CmsRegistration refunded = new CmsRegistration();
        refunded.setId(REG);
        refunded.setOwnerType(2L);
        refunded.setOwnerId(ACTIVITY);
        refunded.setPaymentStatus(4);   // 已退款
        refunded.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(refunded);

        AjaxResult r = controller.scanQrCode("activity", CODE);

        assertTrue(String.valueOf(r.get("msg")).contains("退款"));
        verify(regSvc, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }
    @Test
    void pendingPlusHistoricalReceiptRequiresExplicitChoiceInsteadOfConsumingNextChapter() {
        java.util.Map<Long, Long> mixed = pendingCandidates(CH1, CH2);
        mixed.put(CH1, 66L);
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(mixed);
        AjaxResult result = controller.scanQrCode("activity", CODE);
        assertEquals(500, result.get("code"));
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.get("data");
        assertEquals(Boolean.TRUE, data.get("needChapterChoice"));
        assertEquals(Arrays.asList(CH1, CH2), data.get("chapterIds"));
        java.util.List<?> chapters = (java.util.List<?>) data.get("chapters");
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) chapters.get(0)).get("replayed"));
        assertEquals(66L, ((java.util.Map<?, ?>) chapters.get(0)).get("redemptionId"));
        verify(regSvc, never()).redeemExploreChapterAndRecord(any(), any(), any(), any(), any(), any());
    }

    @Test
    void singleHistoricalReceiptReportsAlreadyUsedAndSkipsControllerAwardsAndCollectibles() {
        java.util.Map<Long, Long> historical = new java.util.TreeMap<>();
        historical.put(CH1, 66L);
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(historical);
        when(regSvc.redeemExploreChapterAndRecord(any(), eq(CH1), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 0, true));
        AjaxResult result = controller.scanQrCode("activity", CODE);
        assertEquals(200, result.get("code"));
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.get("data");
        assertEquals(66L, data.get("redemptionId"));
        assertEquals(CH1, data.get("chapterId"));
        assertEquals(Boolean.TRUE, data.get("replayed"));
        assertFalse(data.containsKey("completionEarnedXp"));
        assertTrue(String.valueOf(result.get("msg")).contains("请勿重复提供权益"));
        org.mockito.Mockito.verifyNoInteractions(pointsSvc);
        org.mockito.Mockito.verifyNoInteractions(ReflectionTestUtils.getField(RegistrationRedemptionTestFixtures.application(controller), "growthService"));
    }

}
