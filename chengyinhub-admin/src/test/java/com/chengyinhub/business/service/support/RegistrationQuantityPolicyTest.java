package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报价/建单共用的数量判定。
 *
 * <p>守的是「报价页不能报出买不了的量」:同一个 qty 在报价侧被放过、建单侧才被拒,
 * 用户会走到支付前一步才失败;反过来报价侧多拒 = 少卖。
 *
 * <p>成团人数(min_people/max_people)与建议组队人数(team_size)不在本策略里 —— 它们是
 * 「多少人成团」,不是「一次买几张」。把它们塞进购买上限会让"一个人帮全队买 5 张"被误拒。
 */
class RegistrationQuantityPolicyTest {

    @Test
    void inventoryExactlyCoversQuantity() {
        assertDoesNotThrow(() -> RegistrationQuantityPolicy.assertPurchasable(5L, 5));
    }

    @Test
    void quantityAboveInventoryIsRejectedWithCreateSideWording() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RegistrationQuantityPolicy.assertPurchasable(2L, 3));
        // 与建单侧原本内联那句逐字相同,前端只需要一套文案
        assertEquals("库存不足,仅剩 2 张", ex.getMessage());
    }

    @Test
    void unknownInventoryMeansSoldOut() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RegistrationQuantityPolicy.assertPurchasable(null, 1));
        assertTrue(ex.getMessage().contains("仅剩 0 张"), "缺库存事实按 0 张从严处理,实际:" + ex.getMessage());
    }

    @Test
    void nonPositiveQuantityIsTreatedAsOneTicket() {
        assertDoesNotThrow(() -> RegistrationQuantityPolicy.assertPurchasable(1L, 0));
        assertDoesNotThrow(() -> RegistrationQuantityPolicy.assertPurchasable(1L, -3));
        assertEquals(1, RegistrationQuantityPolicy.normalizedQuantity(null));
        assertEquals(1, RegistrationQuantityPolicy.normalizedQuantity(0));
        assertEquals(1, RegistrationQuantityPolicy.normalizedQuantity(-3));
        assertEquals(4, RegistrationQuantityPolicy.normalizedQuantity(4));
    }

    @Test
    void zeroRemainingRejectsAnyPurchase() {
        assertThrows(RuntimeException.class, () -> RegistrationQuantityPolicy.assertPurchasable(0L, 1));
    }
}
