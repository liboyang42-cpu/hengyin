package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D22:① 的供给槽分账基数按【组织槽有没有人】分流。
 *
 * <p>① 是有领队产品 —— 团整体行动、整趟走完 ⇒ 每个节点商家接待的是**整个团**,不是零散来客。
 * 原口径「商家自己扫过的玩家」隐含了「玩家散着来」,那是 ② 自玩的形态。所以:
 *  · 组织槽为空 ⇒ 商家自己扫玩家票,基数 = 他扫过的玩家(#58 原样保留,由 CoopSettlementPerMerchantTest 守);
 *  · 组织槽有人 ⇒ 俱乐部扫玩家、商家扫团码,基数 = 他接待过的那些团各自核销了多少人。
 *
 * <p>⚠️ 资损⑨ 的防线没取消,只是换人:从「扫过每个玩家」换成「扫过团码」。
 * 没扫团码 = 没接待 = 拿 0 —— 这条是本类最重要的断言(它保证「有合作单就自动拿钱」不成立,
 * 而那正是定稿 §4.2:247「钱不绑接待动作,体验烂在店里」反对的东西)。
 *
 * ⚠️ 负控已实测(见 D22 提交说明):
 *  - hasOrganizer 恒 false                    ⇒ 本类三条团口径用例红
 *  - leaders 为空时不返回 0 而照查 ByMerchant ⇒ merchantWhoNeverScannedGroupCode_getsZero 红
 *  - 团口径误用 ByMerchant 而非 ByVerifiers   ⇒ merchantWhoServedGroup_paidByGroupHeads 红
 *  - 组织槽为空时改走团口径                    ⇒ CoopSettlementPerMerchantTest 5 条红(T1 回归)
 * WP04：显式计划替身下的五分项组合测试；不证明模拟的 CLUB/团路径已开放现金结算。
 */
class CoopSettlementGroupBasisTest {

    private static final Long TOPIC = 700L;
    private static final Long PUBLISHER = 900001L;
    private static final Long MERCHANT_A = 900002L;
    private static final Long MERCHANT_B = 900003L;
    private static final Long CLUB = 55L;
    private static final Long LEADER = 900009L;      // 主理人(club.member_id)

    private CmsRegistrationMapper cmsRegistrationMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private CoopGroupVerifyMapper coopGroupVerifyMapper;
    private CmsTopicServiceImpl svc;

    @BeforeEach
    void setUp() {
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        when(coopSettlementMapper.insertCoopSettlement(any())).thenReturn(1);
        coopGroupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        IClubService clubService = mock(IClubService.class);
        CoopComplaintMapper coopComplaintMapper = mock(CoopComplaintMapper.class);
        CoopInviteMapper coopInviteMapper = mock(CoopInviteMapper.class);
        when(coopComplaintMapper.countOpenByTopicId(any())).thenReturn(0);
        when(coopSettlementMapper.countByOrderId(any())).thenReturn(0);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEADER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementMapper", coopSettlementMapper);
        PreparedCoopPlanFixture.install(svc, cmsRegistrationMapper, coopOrderMapper, coopGroupVerifyMapper, coopSettlementMapper);
        ReflectionTestUtils.setField(svc, "coopGroupVerifyMapper", coopGroupVerifyMapper);
        ReflectionTestUtils.setField(svc, "pricingService", new PricingServiceImpl());
        ReflectionTestUtils.setField(svc, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailService", mock(IUmsMemberBalanceDetailService.class));
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "coopComplaintMapper", coopComplaintMapper);
        ReflectionTestUtils.setField(svc, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(svc, "coopInviteService", mock(ICoopInviteService.class));

        // 全主题:100 人核销、销售额 5000(俱乐部带队一趟带完)
        when(cmsRegistrationMapper.selectVerifiedHeadsByTopicId(TOPIC)).thenReturn(100);
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("5000"));
        when(cmsRegistrationMapper.selectVerifiedRegistrationIdsByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(1L));
        // 该主题有承接俱乐部 ⇒ 组织槽有人
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                clubOrder(),
                merchantOrder(2L, MERCHANT_A, 2, null, "20"),
                merchantOrder(3L, MERCHANT_B, 2, null, "20")));
        // 商家 A 接待的场次实际核销了 100 人 / 5000；B 没接待任何场次。
        when(coopGroupVerifyMapper.selectSettlementBasisByTopicAndMerchant(TOPIC, MERCHANT_A))
                .thenReturn(groupBasis(100, "5000"));
        when(coopGroupVerifyMapper.selectSettlementBasisByTopicAndMerchant(TOPIC, MERCHANT_B))
                .thenReturn(groupBasis(0, "0"));
    }

    @Test
    void compositionScopeKeepsClubIdentityWithoutEnablingItsCashPath() {
        PreparedCoopPlanFixture.topic(TOPIC);
        var claims = (com.chengyinhub.business.service.ICoopSettlementClaimService)
                ReflectionTestUtils.getField(svc, "coopSettlementClaimService");
        var plans = coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC).stream()
                .map(order -> claims.prepare(order, new java.util.Date())).toList();
        com.chengyinhub.business.service.support.CoopSettlementContract.requireIndependentOrderScopes(plans);
        var club = plans.get(0).getSnapshot();
        assertEquals("CLUB", club.getBeneficiaryRole()); assertEquals(CLUB, club.getBeneficiaryId());
        assertEquals("ORGANIZER_LEAD", club.getServiceUnit());
        assertEquals(TOPIC, club.getCommercialContextId());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.CoopSettlementContract.requireSupportedCashPath(club));
        assertEquals(0, plans.get(2).getVerifiedHeads());
        assertEquals(0, BigDecimal.ZERO.compareTo(plans.get(2).getOriginAmount()));
        for (var plan : plans) for (var line : plan.getOriginLines())
            com.chengyinhub.business.service.support.CoopSettlementContract.requireHeaderEquality(plan.getSnapshot(), line);
        verify(coopSettlementMapper, never()).insertCoopSettlement(any());
    }

    /**
     * ★ 主路径:A 扫过团码(接待了这个团)⇒ 按团的核销数拿钱。
     * 注意 A 自己**一个玩家的票都没扫过**(有领队时那是俱乐部干的)——
     * 旧口径下他会恒得 0,那正是 D22 要修的东西。
     */
    @Test
    void merchantWhoServedGroup_paidByGroupHeads() {
        PreparedCoopPlanFixture.topic(TOPIC);
        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("2000.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A), "A 接待了 100 人的团 → 20×100");
        // ★ 商家一次玩家票都没扫过,却拿到了钱 —— 这正是 D22 之前不可能发生的事
        verify(cmsRegistrationMapper, never()).selectVerifiedHeadsByTopicAndMerchant(eq(TOPIC), eq(MERCHANT_A));
    }

    /** ★★ 没扫过团码 = 没接待 = 拿 0。「有合作单就自动拿钱」必须不成立(否则钱与接待动作解绑)。 */
    @Test
    void merchantWhoNeverScannedGroupCode_getsZero() {
        PreparedCoopPlanFixture.topic(TOPIC);
        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("0.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B),
                "B 有合作单但没扫过团码 ⇒ 没接待过 ⇒ 0。资损⑨ 的防线在这里换成了团码");
        // 场次口径不再拼空 IN；B 的空基数直接是 0。
    }

    /** 分成型同理:按团的核销销售额 × 自己的费率。 */
    @Test
    void groupBasis_shareRateMode() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                clubOrder(),
                merchantOrder(2L, MERCHANT_A, 1, "30", null)));

        PreparedCoopPlanFixture.topic(TOPIC);

        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("1500.00", lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A), "团的 5000×30%");
    }

    /** 俱乐部自己(LEAD_FEE)照旧全主题口径 —— 它带的是整趟,D22 没动它。 */
    @Test
    void clubLeadFee_unchangedByD22() {
        PreparedCoopPlanFixture.topic(TOPIC);
        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertAmount("1000.00", lineOf(lines, "LEAD_FEE", LEADER), "俱乐部按全主题 100 人头 × ¥10");
        verify(coopGroupVerifyMapper, never()).selectSettlementBasisByTopicAndMerchant(eq(TOPIC), eq(CLUB));
    }

    /** 结算行快照与金额同口径(对账时「记着团 100 人却拿 0」会对不上)。 */
    @Test
    void groupBasis_snapshotMatchesAmount() {
        PreparedCoopPlanFixture.topic(TOPIC);
        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        assertEquals(Integer.valueOf(100), lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A).getVerifiedHeads(),
                "A 的快照 = 他接待的团的人头");
        assertEquals(Integer.valueOf(0), lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B).getVerifiedHeads(),
                "B 没接待 ⇒ 快照也是 0,不是全主题 100");
    }

    /** 五分项守恒仍然成立(动了基数就必须重验守恒,否则残差被静默吃掉)。 */
    @Test
    void conservationStillHolds() {
        PreparedCoopPlanFixture.topic(TOPIC);
        PreparedCoopPlanFixture.settle(svc, topic(), new BigDecimal("5000"));

        Map<String, List<CoopSettlement>> lines = capturedLines();
        BigDecimal sum = lines.get("PLATFORM").get(0).getAmount()
                .add(lineOf(lines, "INITIATOR_GUARANTEE", PUBLISHER).getAmount())
                .add(lineOf(lines, "INITIATOR_MARGIN", PUBLISHER).getAmount())
                .add(lineOf(lines, "LEAD_FEE", LEADER).getAmount())
                .add(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_A).getAmount())
                .add(lineOf(lines, "MERCHANT_VERIFY", MERCHANT_B).getAmount());
        assertEquals(0, new BigDecimal("5000").compareTo(sum), "五分项守恒 == 总销售额");
    }

    // ------------------------------------------------------------------ 夹具

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(PUBLISHER);
        return t;
    }

    private static CoopOrder clubOrder() {
        CoopOrder o = new CoopOrder();
        o.setId(1L);
        o.setToType("club");
        o.setToId(CLUB);
        o.setShareMode(2);
        o.setFixedFee(new BigDecimal("10"));
        return o;
    }

    private static CoopOrder merchantOrder(Long id, Long merchantId, int shareMode, String rate, String fee) {
        CoopOrder o = new CoopOrder();
        o.setId(id);
        o.setToType("merchant");
        o.setToId(merchantId);
        o.setShareMode(shareMode);
        o.setShareRate(rate == null ? null : new BigDecimal(rate));
        o.setFixedFee(fee == null ? null : new BigDecimal(fee));
        return o;
    }

    private static CoopGroupSettlementBasis groupBasis(int heads, String sales) {
        CoopGroupSettlementBasis basis = new CoopGroupSettlementBasis();
        basis.setVerifiedHeads(heads);
        basis.setVerifiedSales(new BigDecimal(sales));
        return basis;
    }

    private Map<String, List<CoopSettlement>> capturedLines() {
        ArgumentCaptor<CoopSettlement> cap = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(coopSettlementMapper, atLeast(1)).insertCoopSettlement(cap.capture());
        Map<String, List<CoopSettlement>> m = new HashMap<>();
        for (CoopSettlement s : cap.getAllValues()) {
            m.computeIfAbsent(s.getLineType(), k -> new ArrayList<>()).add(s);
        }
        return m;
    }

    private CoopSettlement lineOf(Map<String, List<CoopSettlement>> lines, String type, Long payee) {
        for (CoopSettlement s : lines.getOrDefault(type, Collections.emptyList())) {
            if (payee.equals(s.getMerchantId())) return s;
        }
        throw new AssertionError("没有 " + type + " 行给 " + payee + ",实际:" + lines);
    }

    private void assertAmount(String expected, CoopSettlement s, String msg) {
        assertEquals(0, new BigDecimal(expected).compareTo(s.getAmount()), msg + "(实际 " + s.getAmount() + ")");
    }
}
