package com.chengyinhub.business.platformpass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 自玩票虚拟支付开关默认关闭；价位→道具映射只认格式正确的项。 */
class SelfPlayXpayPropertiesTest {
    @Test
    void defaultsOffWithNoGoods() {
        SelfPlayXpayProperties p = new SelfPlayXpayProperties();
        assertFalse(p.isEnabled());
        assertNull(p.goodsIdFor(3990));
    }

    @Test
    void parsesPerPriceGoodsAndIgnoresMalformedEntries() {
        SelfPlayXpayProperties p = new SelfPlayXpayProperties();
        p.setGoods(" 3990=goods_a, 4990 = goods_b ,bad,0=zero,-5=neg,1990=,2990=has space,abc=x ");
        assertEquals("goods_a", p.goodsIdFor(3990));
        assertEquals("goods_b", p.goodsIdFor(4990));
        assertNull(p.goodsIdFor(1990));
        assertNull(p.goodsIdFor(2990));
        assertEquals(2, p.goodsByFen().size());
    }
}
