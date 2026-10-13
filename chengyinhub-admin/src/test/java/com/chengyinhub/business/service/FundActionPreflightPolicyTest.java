package com.chengyinhub.business.service;

import com.chengyinhub.business.service.impl.FundActionPreflightPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FundActionPreflightPolicyTest {

    @Test
    void bankWithdrawalFingerprintMatchesTheFundWriterContract() {
        assertEquals("838d58c70b28839f927ec98e2350be359872ace23b945328a0a8e641d7e51fda",
                FundActionPreflightPolicy.bankWithdrawalFingerprint(9201L, new BigDecimal("25.00"),
                        "张三", "城瘾银行", "6222020000001234", "18800000001"));
    }

    @Test
    void structuralSignalsWarnWithoutPretendingToBeARiskScore() {
        FundActionPreflightPolicy.RiskResult result = FundActionPreflightPolicy.evaluate(
                true, new BigDecimal("500.00"), new BigDecimal("500.00"), null);

        assertEquals(FundActionPreflightPolicy.RiskLevel.WARNING, result.getLevel());
        assertEquals(Arrays.asList("FIRST_DESTINATION", "HIGH_AMOUNT"), result.getReasonCodes());
        assertEquals(true, result.isCanProceed());
    }

    @Test
    void onlyAServerHardBlockCanDisableProceeding() {
        FundActionPreflightPolicy.RiskResult result = FundActionPreflightPolicy.evaluate(
                false, new BigDecimal("20.00"), new BigDecimal("500.00"), "WITHDRAWAL_NOT_ALLOWED");

        assertEquals(FundActionPreflightPolicy.RiskLevel.BLOCKED, result.getLevel());
        assertEquals(Arrays.asList("WITHDRAWAL_NOT_ALLOWED"), result.getReasonCodes());
        assertEquals(false, result.isCanProceed());
    }
}
