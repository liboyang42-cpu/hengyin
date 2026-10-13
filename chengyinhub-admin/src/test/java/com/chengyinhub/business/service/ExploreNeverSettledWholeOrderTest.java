package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★★★ [资损·最深一道闸] ③ 探索票**绝不能整单入账**。
 *
 * <p>{@code recordPaidRegistration} 的语义是「整单票款按 settlement_rule 拆给**单一** merchantId」。
 * ③ 一张票跨 N 家商家逐章核销 ⇒ 整单给谁都是错的 ⇒ <b>谁调它谁就制造资损</b>。
 * 「钱」只有这一个出口,所以闸下沉到这里 —— 现在的两个调用方
 * ({@code verifyAndSettle} / {@code recordOnPayment})+ 将来任何新入口一次性全保住。
 *
 * <p><b>为什么非要沉到这一层</b>:本轮同一个病犯了四次,每次都是「我以为我普查完了调用方」:
 * <ol>
 *   <li>③ 分流放错分支(写进 type="topic",而 ③ 只走 type="activity");</li>
 *   <li>拿「③ 到不了 topic 分支」这个**假不变量**拆掉守卫 —— 实际走哪个分支由**客户端传的 type** 决定;</li>
 *   <li>漏掉 {@code ApiClubLeadController} 领队全团结算 —— 探针无 purchase_kind 过滤,
 *       ③ 整单结给俱乐部主理人;</li>
 *   <li>★ 漏掉 {@code recordOnPayment} —— 它在**支付回调**里,只跳过 ownerType==1,
 *       而 ③ 是 owner_type=**2** ⇒ <b>玩家一支付,整单票款就入账给活动创建者,比核销还早</b>。</li>
 * </ol>
 *
 * <p>⚠️ 负控已实测:
 * <ul>
 *   <li>删掉 recordPaidRegistration 的 EXPLORE_PASS 闸 ⇒ explorePass_neverEntersSplitLedger 红</li>
 *   <li>删掉 recordOnPayment 的 EXPLORE_PASS 跳过 ⇒ explorePass_paymentCallback_recordsNothing 红</li>
 * </ul>
 */
class ExploreNeverSettledWholeOrderTest {

    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long ORGANIZER = 999L;

    private SettlementDetailMapper detailMapper;
    private CmsActivityMapper activityMapper;
    private SettlementEntryServiceImpl service;

    @BeforeEach
    void setUp() {
        detailMapper = mock(SettlementDetailMapper.class);
        activityMapper = mock(CmsActivityMapper.class);

        service = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(service, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        rule.setMerchantRate(new BigDecimal("0.9000"));
        rule.setCreatorRate(BigDecimal.ZERO);
        when(ruleService.getActiveRule(0L, "activity")).thenReturn(rule);
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
        ISettlementClaimService claimService = mock(ISettlementClaimService.class);
        when(claimService.claim(any(SettlementClaim.class))).thenAnswer(invocation -> {
            SettlementClaim claim = invocation.getArgument(0);
            claim.setId(42L);
            return claim;
        });
        ReflectionTestUtils.setField(service, "settlementClaimService", claimService);

        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setMemberId(ORGANIZER);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(act);
        when(detailMapper.existsAdjustment(anyString(), anyLong())).thenReturn(0);
        when(detailMapper.insertAdjustment(any())).thenReturn(1);
    }

    private CmsRegistration reg(Long ownerType, Integer purchaseKind) {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(ownerType);
        r.setOwnerId(ACTIVITY);
        r.setPaymentStatus(2);
        r.setPayableAmount(new BigDecimal("100.00"));
        r.setFeePlatformRate(new BigDecimal("0.1000"));
        r.setPurchaseKind(purchaseKind);
        return r;
    }

    /** ★ 直接调整单入账口 ⇒ 抛,且一行明细都不插。任何新入口漏了 ③ 分流,这条红。 */
    @Test
    void explorePass_neverEntersSplitLedger() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.recordPaidRegistration(reg(2L, PurchaseKind.EXPLORE_PASS), ORGANIZER));

        assertTrue(e.getMessage().contains("整单入账"), "实际:" + e.getMessage());
        verify(detailMapper, never()).insertAdjustment(any());
    }

    /**
     * ★★ 支付回调:③ 一支付**什么都不入账**(返 0),而不是抛。
     *
     * <p>抛会让整个支付回调事务回滚 = 玩家付了钱订单却没成 ⇒ 必须在 recordOnPayment 里
     * 显式跳过。被调方那道抛是给「漏了过滤的新入口」兜底的,不是给这条路用的。
     */
    @Test
    void explorePass_paymentCallback_recordsNothing() {
        assertEquals(0, service.recordOnPayment(reg(2L, PurchaseKind.EXPLORE_PASS)));

        verify(detailMapper, never()).insertAdjustment(any());
        // 连活动都不该查 —— 跳过发生在 resolveActivityMerchant 之前
        verify(activityMapper, never()).selectCmsActivityById(anyLong());
    }

    /**
     * ★ ① 的活动票逐字不变:支付即入账给活动创建者。
     * 闸误伤 ① 的话这条红 —— 生产 12 笔付费单走的正是这条。
     */
    @Test
    void guidedActivityTicket_paymentCallback_stillRecords() {
        service.recordOnPayment(reg(2L, PurchaseKind.GUIDED_TICKET));

        verify(activityMapper).selectCmsActivityById(ACTIVITY);
        verify(detailMapper, org.mockito.Mockito.atLeastOnce()).insertAdjustment(any());
    }

    /** ownerType=1(主题单)照旧支付阶段不入账 —— 既有行为,别顺手改。 */
    @Test
    void topicOrder_paymentCallback_stillDefersToVerification() {
        assertEquals(0, service.recordOnPayment(reg(1L, PurchaseKind.GUIDED_TICKET)));

        verify(detailMapper, never()).insertAdjustment(any());
    }

    /**
     * purchase_kind 为 NULL(存量未回填 / 后台手工建单)⇒ 放行,逐字走老路。
     * 若对 NULL 也拒,存量 ① 单的支付入账会被全打死 = 打挂现网。
     */
    @Test
    void nullPurchaseKind_paymentCallback_stillRecords() {
        service.recordOnPayment(reg(2L, null));

        verify(detailMapper, org.mockito.Mockito.atLeastOnce()).insertAdjustment(any());
    }
}
