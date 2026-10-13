package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.9 场次票退款**文案**(产品拍板 2026-09-15):「集合前 24 小时可全额退;之后或未到场不退」。
 *
 * <p>文案只有两处来源,且都从 {@link RefundPolicy#SESSION_REFUND_RULE_TEXT} 出:
 * <ul>
 *   <li>购买页票种行:{@link PublicTicketVO#refundRule}(带队场次票覆写,其他票种原样);</li>
 *   <li>订单页退款卡:{@link RegistrationDetailDisplay#refundDeadlineDisplay}(判决 + 该句规则)。</li>
 * </ul>
 * 自玩通行证(ownerType=1)与自由探索票(mode=2,快照钟)不得被改。
 */
class SessionRefundCopyTest {

    private static final long H = 3600_000L;
    private final Date now = new Date(1_700_000_000_000L);

    private OmsTicket ticket(long ownerType, Integer mode, String publisherRule) {
        OmsTicket t = new OmsTicket();
        t.setId(1L);
        t.setOwnerType(ownerType);
        t.setMode(mode);
        t.setName("城市定向票");
        t.setRefundRule(publisherRule);
        return t;
    }

    /** 购买页:带队场次票(ownerType=2 且非自由探索)统一出平台规则,压掉发布者的自由文本。 */
    @Test
    void guidedActivityTicketGetsPlatformRuleText() {
        PublicTicketVO vo = PublicTicketVO.from(ticket(2L, 1, "老旧自由文本"), null);
        assertEquals(RefundPolicy.SESSION_REFUND_RULE_TEXT, vo.getRefundRule());
    }

    /** mode 缺省(存量派生列未落)按带队处理 —— 退款钟对 ownerType=2 一律 24h,文案不能反着来。 */
    @Test
    void activityTicketWithNullModeAlsoGetsPlatformRuleText() {
        PublicTicketVO vo = PublicTicketVO.from(ticket(2L, null, null), null);
        assertEquals(RefundPolicy.SESSION_REFUND_RULE_TEXT, vo.getRefundRule());
    }

    /** 自由探索票(mode=2)走的是快照钟(票有效期),文案不得覆写成 24h 规则。 */
    @Test
    void freeExploreActivityTicketKeepsPublisherRule() {
        PublicTicketVO vo = PublicTicketVO.from(ticket(2L, 2, "以票面有效期为准"), null);
        assertEquals("以票面有效期为准", vo.getRefundRule());
    }

    /** 主题票(ownerType=1)不在本次范围 —— 自玩/经典定向的口径一个都不动。 */
    @Test
    void topicTicketKeepsPublisherRule() {
        PublicTicketVO vo = PublicTicketVO.from(ticket(1L, 1, "主题自有规则"), null);
        assertEquals("主题自有规则", vo.getRefundRule());
    }

    private String display(long ownerType, Integer purchaseKind) {
        CmsRegistration r = new CmsRegistration();
        r.setOwnerType(ownerType);
        r.setPurchaseKind(purchaseKind);
        r.setPaymentStatus(2);
        RefundPolicy.Verdict v = new RefundPolicy.Verdict(true, "可全额退款,原路退回(预计1-3个工作日)",
                new Date(now.getTime() + 3 * H));
        return RegistrationDetailDisplay.refundDeadlineDisplay(v, r);
    }

    /** 订单页:带队场次单的可退展示 = 规则句 + 本单截止点。 */
    @Test
    void guidedSessionOrderDisplayCarriesRuleTextAndDeadline() {
        String text = display(2L, PurchaseKind.GUIDED_TICKET);
        assertTrue(text.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + text);
        assertTrue(text.contains("可免费取消至"), "实际:" + text);
    }

    /** 存量单购买类型缺失 ⇒ 仍按场次票(ownerType=2)展示,别把老单的承诺吞掉。 */
    @Test
    void legacyActivityOrderAlsoCarriesRuleText() {
        String text = display(2L, null);
        assertTrue(text.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + text);
    }

    /** ③ 自由探索票(快照钟)的展示保持原样,不加 24h 规则句。 */
    @Test
    void explorePassOrderDisplayStaysDeadlineOnly() {
        String text = display(2L, PurchaseKind.EXPLORE_PASS);
        assertFalse(text.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + text);
        assertTrue(text.contains("可免费取消至"), "实际:" + text);
    }

    /** 主题单(ownerType=1)的展示保持原样。 */
    @Test
    void topicOrderDisplayStaysDeadlineOnly() {
        String text = display(1L, PurchaseKind.GUIDED_TICKET);
        assertFalse(text.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + text);
    }

    /** 错过窗口的拒绝理由必须换成 24h 口径 —— 展示与执行说同一句话。 */
    @Test
    void missedSessionRefusalReasonUses24hCopy() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null,
                new Date(now.getTime() - H), now);
        assertFalse(v.refundable);
        assertTrue(v.reason.contains("集合前24小时") || v.reason.contains("集合前 24 小时"),
                "实际:" + v.reason);
    }

    /** 快照单(③)错过时给通用截止口径,不再说「活动开始」—— 它的钟是票面有效期。 */
    @Test
    void missedSnapshotOrderUsesGenericDeadlineReason() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, null, null,
                PurchaseKind.EXPLORE_PASS, new Date(now.getTime() - H), now);
        assertFalse(v.refundable);
        assertFalse(v.reason.contains("活动开始"), "实际:" + v.reason);
        assertTrue(v.reason.contains("退款截止"), "实际:" + v.reason);
    }
}
