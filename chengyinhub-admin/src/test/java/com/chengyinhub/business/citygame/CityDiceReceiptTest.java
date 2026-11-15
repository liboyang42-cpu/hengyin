package com.chengyinhub.business.citygame;

import com.chengyinhub.business.service.impl.GameRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** HIGH-S5：城市骰子凭据绑定棋盘与成员，不超过 64 字符，挑战攻守两骰互不相同。 */
class CityDiceReceiptTest {

    private static final String LONG_BOARD = "b".repeat(96);

    @Test
    void receiptFitsColumnEvenForMaximalIdsAndKeepsTail() {
        String requestA = "r".repeat(90) + "A";
        String requestB = "r".repeat(90) + "B";
        for (String tag : new String[]{"MOVE", "UNIFIED_ROLL", "BUDGET", "CHALLENGE_ATK", "CHALLENGE_DEF"}) {
            String a = CityGameActionService.cityDiceReceipt(LONG_BOARD, Long.MAX_VALUE, requestA, tag);
            String b = CityGameActionService.cityDiceReceipt(LONG_BOARD, Long.MAX_VALUE, requestB, tag);
            // scoped 适配器可能追加 ":N"，留 2 个字符
            assertTrue(a.length() + 2 <= GameRandom.MAX_RECEIPT_LENGTH, a);
            assertNotEquals(a, b, "requestId tail must change the receipt");
            assertTrue(a.endsWith(":" + tag));
        }
    }

    @Test
    void receiptIsBoundToBoardAndMember() {
        String base = CityGameActionService.cityDiceReceipt("board1", 7L, "req-1", "MOVE");
        assertNotEquals(base, CityGameActionService.cityDiceReceipt("board2", 7L, "req-1", "MOVE"));
        assertNotEquals(base, CityGameActionService.cityDiceReceipt("board1", 8L, "req-1", "MOVE"));
        assertTrue(base.startsWith("city:7:"));
    }

    @Test
    void challengeAttackAndDefenceUseDistinctReceipts() {
        assertNotEquals(
                CityGameActionService.cityDiceReceipt("board1", 7L, "req-1", "CHALLENGE_ATK"),
                CityGameActionService.cityDiceReceipt("board1", 7L, "req-1", "CHALLENGE_DEF"));
    }
}
