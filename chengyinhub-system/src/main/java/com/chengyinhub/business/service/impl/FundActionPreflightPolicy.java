package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FundActionPreflightPolicy {
    private FundActionPreflightPolicy() { }

    public enum RiskLevel { STATIC, WARNING, BLOCKED }

    public static String bankWithdrawalFingerprint(Long memberId, BigDecimal amount, String realname,
                                                    String bankName, String bankAccount, String mobilephone) {
        String canonical = part(memberId) + part(normalizeMoney(amount)) + part(realname)
                + part(bankName) + part(bankAccount) + part(mobilephone);
        return sha256(canonical);
    }

    public static RiskResult evaluate(boolean firstDestination, BigDecimal amount,
                                      BigDecimal warningAmountThreshold, String serverHardBlockCode) {
        if (serverHardBlockCode != null && !serverHardBlockCode.trim().isEmpty()) {
            return new RiskResult(RiskLevel.BLOCKED,
                    Collections.singletonList(serverHardBlockCode.trim()), false);
        }
        List<String> reasons = new ArrayList<>();
        if (firstDestination) reasons.add("FIRST_DESTINATION");
        if (amount != null && warningAmountThreshold != null
                && amount.compareTo(warningAmountThreshold) >= 0) reasons.add("HIGH_AMOUNT");
        return reasons.isEmpty()
                ? new RiskResult(RiskLevel.STATIC, reasons, true)
                : new RiskResult(RiskLevel.WARNING, reasons, true);
    }

    private static String normalizeMoney(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String part(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        return normalized.length() + ":" + normalized + "|";
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static final class RiskResult {
        private final RiskLevel level;
        private final List<String> reasonCodes;
        private final boolean canProceed;
        RiskResult(RiskLevel level, List<String> reasonCodes, boolean canProceed) {
            this.level = level;
            this.reasonCodes = Collections.unmodifiableList(new ArrayList<>(reasonCodes));
            this.canProceed = canProceed;
        }
        public RiskLevel getLevel() { return level; }
        public List<String> getReasonCodes() { return reasonCodes; }
        public boolean isCanProceed() { return canProceed; }
    }
}
