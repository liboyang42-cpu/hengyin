package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Original debit and transfer parameters; only submission observations may be added later. */
public record CashWithdrawTransferIntent(Long withdrawId, Long memberId, String mchid, String appid,
        String outBatchNo, String outDetailNo, long amountFen, String openid, String remark,
        int requestVersion, String requestHash, BigDecimal balanceBefore, BigDecimal balanceAfter,
        boolean submitted, boolean locallyRejected) {

    public static CashWithdrawTransferIntent create(MemberCashWithdraw w, String mchid, String appid,
            String remark, BigDecimal before, BigDecimal after) {
        String effectiveRemark = remark == null || remark.isEmpty() ? "分润提现"
                : remark.substring(0, Math.min(32, remark.length()));
        CashWithdrawTransferIntent unsigned = new CashWithdrawTransferIntent(w.getId(), w.getMemberId(),
                mchid, appid, w.getOutBatchNo(), w.getOutDetailNo(), cents(w.getAmount()), w.getOpenid(),
                effectiveRemark, 1, null, before, after, false, false);
        unsigned.requireParameters();
        return new CashWithdrawTransferIntent(unsigned.withdrawId, unsigned.memberId, mchid, appid,
                unsigned.outBatchNo, unsigned.outDetailNo, unsigned.amountFen, unsigned.openid,
                effectiveRemark, 1, unsigned.digest(), before, after, false, false);
    }

    public void requireValid() {
        requireParameters();
        if (!digest().equals(requestHash) || locallyRejected && !submitted)
            throw new IllegalStateException("提现原始证据校验失败，须按原号核查");
    }

    public void requireMatches(MemberCashWithdraw w) {
        requireValid();
        if (w == null || !withdrawId.equals(w.getId()) || !memberId.equals(w.getMemberId())
                || !openid.equals(w.getOpenid()) || !outBatchNo.equals(w.getOutBatchNo())
                || !outDetailNo.equals(w.getOutDetailNo()) || amountFen != cents(w.getAmount()))
            throw new IllegalStateException("提现原始单据与扣款证据不一致");
    }

    public boolean matchesObservation(Map<String, String> facts) {
        requireValid();
        if (facts == null) return false;
        try {
            return facts.get("batchId") != null && !facts.get("batchId").isBlank()
                    && facts.get("detailId") != null && !facts.get("detailId").isBlank()
                    && mchid.equals(facts.get("mchid")) && appid.equals(facts.get("appid"))
                    && outBatchNo.equals(facts.get("outBatchNo"))
                    && outDetailNo.equals(facts.get("outDetailNo")) && openid.equals(facts.get("openid"))
                    && amountFen == Long.parseLong(facts.get("transferAmount"))
                    && "FINISHED".equals(facts.get("batchStatus"))
                    && ("SUCCESS".equals(facts.get("detailStatus")) || "FAIL".equals(facts.get("detailStatus")))
                    && facts.get("detailStatus").equals(facts.get("result"));
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    public BigDecimal amountYuan() { return BigDecimal.valueOf(amountFen, 2); }

    private void requireParameters() {
        if (withdrawId == null || withdrawId <= 0 || memberId == null || memberId <= 0
                || mchid == null || mchid.isBlank() || appid == null || appid.isBlank()
                || outBatchNo == null || !outBatchNo.matches("[A-Za-z0-9]{1,32}")
                || outDetailNo == null || !outDetailNo.matches("[A-Za-z0-9]{1,32}")
                || amountFen <= 0 || openid == null || openid.isBlank()
                || remark == null || remark.isEmpty() || remark.length() > 32 || requestVersion != 1
                || balanceBefore == null || balanceAfter == null || balanceAfter.signum() < 0
                || balanceBefore.subtract(balanceAfter).compareTo(amountYuan()) != 0)
            throw new IllegalStateException("提现原始扣款或转账参数缺失/不一致");
    }

    private String digest() {
        StringBuilder canonical = new StringBuilder("CASH_WITHDRAW_TRANSFER_V1");
        for (String value : new String[]{withdrawId.toString(), memberId.toString(), mchid, appid,
                outBatchNo, outDetailNo, Long.toString(amountFen), openid, remark,
                Integer.toString(requestVersion), balanceBefore.setScale(2).toPlainString(),
                balanceAfter.setScale(2).toPlainString()}) {
            canonical.append(value.length()).append(':').append(value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long cents(BigDecimal amount) {
        if (amount == null) throw new IllegalStateException("提现原始金额缺失");
        return amount.movePointRight(2).longValueExact();
    }
}
