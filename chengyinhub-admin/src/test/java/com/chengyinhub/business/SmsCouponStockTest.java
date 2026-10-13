package com.chengyinhub.business;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl.hasStock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * C1 券超发防护 · 纯逻辑:发行上限(publish_count)判定。
 * 约定:publish_count 为 null 或 <=0 表示不限量;>0 时 receive_count 必须 < publish_count 才可再发。
 * (并发原子性由 SmsCouponMapper.tryIncrementReceiveCount 的条件 UPDATE 保证,此处仅覆盖判定语义。)
 */
class SmsCouponStockTest {

    @Test
    void unlimited_whenPublishCountNullOrNonPositive() {
        assertTrue(hasStock(0L, null), "publish_count=null → 不限量");
        assertTrue(hasStock(9999L, null), "publish_count=null → 不限量(无论已领多少)");
        assertTrue(hasStock(0L, 0L), "publish_count=0 → 不限量");
        assertTrue(hasStock(0L, -1L), "publish_count<0 → 不限量");
    }

    @Test
    void limited_hasStockWhenReceiveBelowPublish() {
        assertTrue(hasStock(0L, 100L), "0/100 有量");
        assertTrue(hasStock(99L, 100L), "99/100 最后一张仍可发");
        assertTrue(hasStock(null, 100L), "null 视为 0,0/100 有量");
    }

    @Test
    void limited_exhaustedWhenReceiveReachesPublish() {
        assertFalse(hasStock(100L, 100L), "100/100 已抢光,拒发");
        assertFalse(hasStock(150L, 100L), "150/100 脏数据超发,拒发(防御)");
    }
}
