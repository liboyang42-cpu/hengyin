package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结算按受款方去重(资损·重复计数)。
 *
 * 病灶:分账循环按**每张 coop_order** 走,而商家基数
 * `selectVerifiedHeadsByTopicAndMerchant` / `merchantVerifiedSales` 只按 (topic, merchant) 聚合。
 * 同一商家若有多张生效合作单(多节点各发一次邀约即可产生;DDL 唯一键只覆盖 reg_id 非空的行,
 * 邀约单 reg_id=NULL 不入键),**同一份销售额会被算 N 遍**,各生成一条 MERCHANT_VERIFY。
 *
 * 与核销双付是同一类病:「一个受款方在一个主题里有多个收款依据」。
 *
 * 这里钉的是去重键的语义 —— 真正的循环行为由 CmsTopicServiceImpl 内联的
 * settledPayees 保证,那段无法在不起 Spring 的前提下单独调用,故在此固化判据:
 * **键必须同时含 toType 与 toId**。只用 toId 会让「俱乐部 5 号」和「商家 5 号」互相顶掉。
 */
class CoopSettlementPayeeDedupTest {

    /** 复刻 CmsTopicServiceImpl 里的去重键构造,判据必须一致。 */
    private String payeeKey(CoopOrder o) {
        return o.getToType() + "#" + o.getToId();
    }

    private CoopOrder order(String toType, Long toId) {
        CoopOrder o = new CoopOrder();
        o.setToType(toType);
        o.setToId(toId);
        return o;
    }

    private List<CoopOrder> settled(List<CoopOrder> orders) {
        Set<String> seen = new HashSet<>();
        List<CoopOrder> out = new ArrayList<>();
        for (CoopOrder o : orders) {
            if (seen.add(payeeKey(o))) out.add(o);
        }
        return out;
    }

    /** ★同一商家两张生效单 ⇒ 只结一次,否则同一份销售额算两遍。 */
    @Test
    @Timeout(5)
    void sameMerchantTwoOrders_settledOnce() {
        List<CoopOrder> out = settled(List.of(
                order("merchant", 88L), order("merchant", 88L)));
        assertEquals(1, out.size(), "同一商家多张合作单必须只结一次 —— 基数按 (topic,payee) 聚合,重复结就是多付");
    }

    /** 不同商家各结各的,别把去重做成「一个主题只结一个商家」。 */
    @Test
    @Timeout(5)
    void differentMerchants_bothSettled() {
        assertEquals(2, settled(List.of(order("merchant", 88L), order("merchant", 99L))).size());
    }

    /**
     * ★键必须含 toType:俱乐部和商家的 id 是两套独立空间,
     * 只用 toId 会让「俱乐部 5 号」把「商家 5 号」顶掉 —— 那是**少付**,同样是资损。
     */
    @Test
    @Timeout(5)
    void clubAndMerchantWithSameId_bothSettled() {
        List<CoopOrder> out = settled(List.of(order("club", 5L), order("merchant", 5L)));
        assertEquals(2, out.size(), "id 空间不同,只用 toId 去重会把其中一方的钱吞掉");
    }

    /** 同一俱乐部多张单同样只结一次(LEAD_FEE 走全主题口径,重复结更狠)。 */
    @Test
    @Timeout(5)
    void sameClubTwoOrders_settledOnce() {
        assertEquals(1, settled(List.of(order("club", 7L), order("club", 7L))).size());
    }
}
