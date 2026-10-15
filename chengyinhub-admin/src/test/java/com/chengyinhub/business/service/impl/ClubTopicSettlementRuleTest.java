package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.service.ISettlementRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 俱乐部主办 vs 商家主办的分润区分。
 *
 * <p>改造前 {@code executeTopicSettlement} 用 {@code getActiveRule(0L, "topic")} 取一条规则
 * 给整批用,主办方是谁完全不进入计算 —— 「俱乐部分润有差别」始终只是意图,不是实现。
 *
 * <p>这里锁三件事:①俱乐部主题命中 topic_club 规则 ②没配 topic_club 时回落全局(存量零影响)
 * ③俱乐部规则费率非法时**拒结该主题**,而不是悄悄用全局费率把账算成商家的。
 */
class ClubTopicSettlementRuleTest {

    private ISettlementRuleService ruleService;
    private CmsTopicServiceImpl service;
    private SettlementRule global;

    private static SettlementRule rule(String merchant, String creator) {
        SettlementRule r = new SettlementRule();
        r.setMerchantRate(new BigDecimal(merchant));
        r.setCreatorRate(new BigDecimal(creator));
        r.setPlatformCommissionRate(new BigDecimal("0.10"));
        return r;
    }

    /** resolveRuleFor 是私有的实现细节,但它承载的是资金口径,必须被直接钉住。 */
    private SettlementRule resolve(CmsTopic topic) throws Exception {
        Method m = CmsTopicServiceImpl.class.getDeclaredMethod("resolveRuleFor", CmsTopic.class, SettlementRule.class);
        m.setAccessible(true);
        return (SettlementRule) m.invoke(service, topic, global);
    }

    private static CmsTopic topic(Long clubId) {
        CmsTopic t = new CmsTopic();
        t.setId(7L);
        t.setClubId(clubId);
        return t;
    }

    @BeforeEach
    void setUp() throws Exception {
        ruleService = mock(ISettlementRuleService.class);
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        java.lang.reflect.Field f = CmsTopicServiceImpl.class.getDeclaredField("settlementRuleService");
        f.setAccessible(true);
        f.set(service, ruleService);
        global = rule("0.60", "0.20");
    }

    @Test
    @Timeout(5)
    void 商家主办走全局规则() throws Exception {
        assertSame(global, resolve(topic(null)), "没有 clubId 的主题必须逐字维持改造前行为");
        assertSame(global, resolve(topic(0L)), "clubId=0 是哨兵,同样算商家主办");
    }

    /**
     * H8 起 creator_rate 全并 merchant(发布者不再拿现金分成,改由 event15 积分承接),
     * 所以俱乐部规则也必须 creator=0、差额由 merchant 吸收。
     * 区分度靠 merchantRate:全局是 0.60,俱乐部是 0.80 —— 拿到 0.80 才证明命中的是 topic_club。
     */
    @Test
    @Timeout(5)
    void 俱乐部主办命中club规则() throws Exception {
        SettlementRule club = rule("0.80", "0.00");
        when(ruleService.getActiveRule(eq(0L), eq("topic_club"))).thenReturn(club);

        SettlementRule got = resolve(topic(42L));

        assertSame(club, got);
        assertEquals(new BigDecimal("0.80"), got.getMerchantRate(),
                "俱乐部主办的商家份额必须来自 topic_club 规则(0.80),不是全局那条(0.60)");
        assertEquals(new BigDecimal("0.00"), got.getCreatorRate(),
                "H8:发布者现金分成已并入 merchant,俱乐部规则的 creator_rate 必须是 0");
    }

    /** ★存量零影响:没配 topic_club 时行为与改造前完全一致 —— 这条不过就不敢上线。 */
    @Test
    @Timeout(5)
    void 没配club规则时回落全局() throws Exception {
        when(ruleService.getActiveRule(eq(0L), eq("topic_club"))).thenReturn(null);
        assertSame(global, resolve(topic(42L)));
    }

    /**
     * ★负控:俱乐部规则费率非法(merchant+creator > 1.0 会超发)必须返回 null 让调用方跳过,
     * 绝不能回落全局 —— 回落等于「配错了就按商家费率发钱」,错得静悄悄。
     */
    @Test
    @Timeout(5)
    void 俱乐部规则费率非法时拒结而不是回落() throws Exception {
        when(ruleService.getActiveRule(eq(0L), eq("topic_club"))).thenReturn(rule("0.80", "0.50"));
        assertNull(resolve(topic(42L)), "1.30 > 1.0 会超发,必须拒结该主题");
    }
}
