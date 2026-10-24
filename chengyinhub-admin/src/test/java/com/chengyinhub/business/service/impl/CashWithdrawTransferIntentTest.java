package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Pure wire contract tests only; no SDK or storage acceptance claim. */
class CashWithdrawTransferIntentTest {
    private CashWithdrawTransferIntent request(String merchant, String appid, String batch, String detail,
                                               String amount, String openid, String remark) {
        return CashWithdrawTransferIntent.fromRequest(500L, merchant, appid, batch, detail,
                new BigDecimal(amount), openid, remark);
    }
    private CashWithdrawTransferIntent original() {
        return request("merchant", "wx-origin", "CW500", "CD500", "30.00", "openid-origin", "提现");
    }

    @Test void associationIdsDoNotChangeTheActualWireHash() {
        CashWithdrawTransferIntent before = original(), committed = before.withSubmissionIntentId(700L);
        assertEquals("3d2a774a7a3aad5a49c9ef9f9cbefe82e53d1ed465af3a752b5d4b96cd26d18a", before.requestHash());
        assertEquals(before.requestHash(), committed.requestHash());
        assertEquals(700L, committed.submissionIntentId()); committed.requireValid();
        assertThrows(IllegalArgumentException.class, before::requireValid);
        assertEquals(new BigDecimal("30.00"), committed.amountYuan()); assertEquals(3000L, committed.amountFen());
        assertEquals(1, CashWithdrawTransferIntent.TOTAL_NUM); assertEquals("城瘾分润提现", CashWithdrawTransferIntent.BATCH_NAME);
        assertFalse(committed.toString().contains("openid-origin")); assertFalse(committed.toString().contains("wx-origin"));
    }

    @Test void everyActualOriginalParameterParticipatesInTheCanonicalHash() {
        String hash = original().requestHash();
        assertNotEquals(hash, request("other", "wx-origin", "CW500", "CD500", "30", "openid-origin", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "other", "CW500", "CD500", "30", "openid-origin", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "wx-origin", "CW501", "CD500", "30", "openid-origin", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "wx-origin", "CW500", "CD501", "30", "openid-origin", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "wx-origin", "CW500", "CD500", "30.01", "openid-origin", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "wx-origin", "CW500", "CD500", "30", "other", "提现").requestHash());
        assertNotEquals(hash, request("merchant", "wx-origin", "CW500", "CD500", "30", "openid-origin", "其他备注").requestHash());
    }

    @Test void moneyConversionIsExactAndNeverRoundsOrUsesDouble() {
        assertEquals(3000L, request("merchant", "wx-origin", "CW500", "CD500", "30.000", "openid-origin", "提现").amountFen());
        for (String amount : new String[]{"30.001", "0.001", "92233720368547758.08"})
            assertThrows(ArithmeticException.class, () -> request("merchant", "wx-origin", "CW500", "CD500", amount, "openid-origin", "提现"));
        for (String amount : new String[]{"0", "-1"})
            assertThrows(IllegalArgumentException.class, () -> request("merchant", "wx-origin", "CW500", "CD500", amount, "openid-origin", "提现"));
    }

    @Test void effectiveRemarkUsesTheExistingDefaultAndJavaSubstringRuleExactly() {
        assertEquals("分润提现", request("merchant", "wx-origin", "CW500", "CD500", "30", "openid-origin", null).effectiveRemark());
        assertEquals("分润提现", request("merchant", "wx-origin", "CW500", "CD500", "30", "openid-origin", "").effectiveRemark());
        String longRemark = "a".repeat(31) + "\uD83D\uDE00" + "z";
        CashWithdrawTransferIntent truncated = request("merchant", "wx-origin", "CW500", "CD500", "30", "openid-origin", longRemark);
        assertEquals(longRemark.substring(0, 32), truncated.effectiveRemark());
        assertNotEquals(truncated.requestHash(), request("merchant", "wx-origin", "CW500", "CD500", "30", "openid-origin", "a".repeat(31) + "?").requestHash());
    }

    @Test void restoreRejectsMissingLegacyFieldsAndAChangedHashOrOriginalApp() {
        CashWithdrawTransferIntent original = original();
        assertThrows(IllegalArgumentException.class, () -> CashWithdrawTransferIntent.restore(500L, 700L, null,
                original.appid(), "CW500", "CD500", 3000L, "openid-origin", "提现", 1, original.requestHash()));
        assertThrows(IllegalArgumentException.class, () -> CashWithdrawTransferIntent.restore(500L, 700L, "merchant",
                "other", "CW500", "CD500", 3000L, "openid-origin", "提现", 1, original.requestHash()));
        assertThrows(IllegalArgumentException.class, () -> CashWithdrawTransferIntent.restore(500L, 700L, "merchant",
                original.appid(), "CW500", "CD500", 3000L, "openid-origin", "提现", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> CashWithdrawTransferIntent.restore(0L, 700L, "merchant",
                original.appid(), "CW500", "CD500", 3000L, "openid-origin", "提现", 1, original.requestHash()));
        assertThrows(IllegalArgumentException.class, () -> CashWithdrawTransferIntent.restore(500L, 0L, "merchant",
                original.appid(), "CW500", "CD500", 3000L, "openid-origin", "提现", 1, original.requestHash()));
    }
}
