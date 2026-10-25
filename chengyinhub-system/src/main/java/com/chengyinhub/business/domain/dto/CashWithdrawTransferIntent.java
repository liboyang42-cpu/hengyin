package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Preserve both published V1 identities: ledger wire hash and master immutable debit-snapshot hash. */
public record CashWithdrawTransferIntent(Long withdrawalId, Long submissionIntentId, String mchid,
        String appid, String outBatchNo, String outDetailNo, long amountFen, String openid,
        String effectiveRemark, int requestVersion, String requestHash, Long memberId,
        BigDecimal balanceBefore, BigDecimal balanceAfter, boolean submitted, boolean locallyRejected,
        OriginalSource originalSource) {
    public enum OriginalSource { FUND_FACT, TRANSFER_INTENT }
    public static final int REQUEST_VERSION = 1;
    public static final String BATCH_NAME = "城瘾分润提现";
    public static final int TOTAL_NUM = 1;

    /** Original ledger constructor; a non-null immutable SUBMIT_INTENT id is the dispatch claim. */
    public CashWithdrawTransferIntent(Long withdrawalId, Long submissionIntentId, String mchid,
            String appid, String outBatchNo, String outDetailNo, long amountFen, String openid,
            String effectiveRemark, int requestVersion, String requestHash) {
        this(withdrawalId, submissionIntentId, mchid, appid, outBatchNo, outDetailNo, amountFen,
                openid, effectiveRemark, requestVersion, requestHash, null, null, null,
                submissionIntentId != null, false, OriginalSource.FUND_FACT);
    }

    /** Existing master mapper constructor: retain the original debit-snapshot V1 fingerprint. */
    public CashWithdrawTransferIntent(Long withdrawId, Long memberId, String mchid, String appid,
            String outBatchNo, String outDetailNo, long amountFen, String openid, String remark,
            int requestVersion, String requestHash, BigDecimal balanceBefore, BigDecimal balanceAfter,
            boolean submitted, boolean locallyRejected) {
        this(withdrawId, null, mchid, appid, outBatchNo, outDetailNo, amountFen, openid, remark,
                requestVersion, requestHash, memberId, balanceBefore, balanceAfter, submitted, locallyRejected,
                OriginalSource.TRANSFER_INTENT);
    }

    public Long withdrawId() { return withdrawalId; }
    public String remark() { return effectiveRemark; }

    /** Explicit representation check, not a missing-field fallback or a hash-version conversion. */
    public boolean hasDebitSnapshot() {
        return originalSource == OriginalSource.TRANSFER_INTENT;
    }

    public static CashWithdrawTransferIntent create(MemberCashWithdraw w, String mchid, String appid,
            String remark, BigDecimal before, BigDecimal after) {
        String effective = effectiveRemark(remark);
        CashWithdrawTransferIntent unsigned = new CashWithdrawTransferIntent(w.getId(), w.getMemberId(),
                mchid, appid, w.getOutBatchNo(), w.getOutDetailNo(), cents(w.getAmount()), w.getOpenid(),
                effective, REQUEST_VERSION, null, before, after, false, false);
        unsigned.requireDebitSnapshotParameters();
        return new CashWithdrawTransferIntent(w.getId(), w.getMemberId(), mchid, appid,
                w.getOutBatchNo(), w.getOutDetailNo(), unsigned.amountFen(), w.getOpenid(), effective,
                REQUEST_VERSION, unsigned.debitSnapshotHash(), before, after, false, false);
    }

    public static CashWithdrawTransferIntent fromRequest(Long withdrawalId, String mchid, String appid,
            String outBatchNo, String outDetailNo, BigDecimal amountYuan, String openid, String remark) {
        if (amountYuan == null) throw new IllegalArgumentException("缺少原转账金额");
        long fen = amountYuan.movePointRight(2).longValueExact();
        String effective = effectiveRemark(remark);
        return new CashWithdrawTransferIntent(withdrawalId, null, mchid, appid, outBatchNo, outDetailNo,
                fen, openid, effective, REQUEST_VERSION,
                hash(mchid, appid, outBatchNo, outDetailNo, fen, openid, effective, REQUEST_VERSION));
    }

    /** Missing legacy fields must not be reconstructed from current configuration or the member profile. */
    public static CashWithdrawTransferIntent restore(Long withdrawalId, Long submissionIntentId, String mchid,
            String appid, String outBatchNo, String outDetailNo, Long amountFen, String openid,
            String effectiveRemark, Integer requestVersion, String requestHash) {
        if (amountFen == null || requestVersion == null)
            throw new IllegalArgumentException("缺少原转账参数持久证据");
        CashWithdrawTransferIntent restored = new CashWithdrawTransferIntent(withdrawalId, submissionIntentId, mchid, appid, outBatchNo,
                outDetailNo, amountFen, openid, effectiveRemark, requestVersion, requestHash);
        restored.requireValid(); return restored;
    }

    public CashWithdrawTransferIntent withSubmissionIntentId(Long id) {
        if (hasDebitSnapshot()) throw new IllegalArgumentException("原余额快照身份不能转换为另一套请求指纹");
        return restore(withdrawalId, id, mchid, appid, outBatchNo, outDetailNo, amountFen, openid,
                effectiveRemark, requestVersion, requestHash);
    }

    public void requireValid() {
        if (originalSource == null) throw new IllegalArgumentException("原提现证据来源缺失");
        if (hasDebitSnapshot()) {
            requireDebitSnapshotParameters();
            if (!debitSnapshotHash().equals(requestHash) || locallyRejected && !submitted)
                throw new IllegalStateException("提现原始证据校验失败，须按原号核查");
            return;
        }
        if (memberId != null || balanceBefore != null || balanceAfter != null || !submitted || locallyRejected
                || withdrawalId == null || withdrawalId <= 0
                || submissionIntentId == null || submissionIntentId <= 0
                || requestHash == null || !requestHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原转账意图关联或指纹不完整");
        String expected = canonicalRequestHash();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), requestHash.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("原转账请求指纹不一致");
    }

    public String canonicalRequestHash() {
        if (originalSource == null) throw new IllegalArgumentException("原提现证据来源缺失");
        if (hasDebitSnapshot()) {
            requireDebitSnapshotParameters();
            return debitSnapshotHash();
        }
        if (memberId != null || balanceBefore != null || balanceAfter != null)
            throw new IllegalArgumentException("原提现两种证据结构不能混用");
        return hash(mchid, appid, outBatchNo, outDetailNo, amountFen, openid, effectiveRemark, requestVersion);
    }

    public void requireMatches(MemberCashWithdraw w) {
        requireValid();
        if (w == null || !withdrawalId.equals(w.getId()) || (memberId != null && !memberId.equals(w.getMemberId()))
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

    private void requireDebitSnapshotParameters() {
        if (submissionIntentId != null || withdrawalId == null || withdrawalId <= 0 || memberId == null || memberId <= 0
                || mchid == null || mchid.isBlank() || appid == null || appid.isBlank()
                || outBatchNo == null || !outBatchNo.matches("[A-Za-z0-9]{1,32}")
                || outDetailNo == null || !outDetailNo.matches("[A-Za-z0-9]{1,32}")
                || amountFen <= 0 || openid == null || openid.isBlank()
                || effectiveRemark == null || effectiveRemark.isEmpty() || effectiveRemark.length() > 32 || requestVersion != 1
                || balanceBefore == null || balanceAfter == null || balanceAfter.signum() < 0
                || balanceBefore.subtract(balanceAfter).compareTo(amountYuan()) != 0)
            throw new IllegalStateException("提现原始扣款或转账参数缺失/不一致");
    }

    private String debitSnapshotHash() {
        StringBuilder canonical = new StringBuilder("CASH_WITHDRAW_TRANSFER_V1");
        for (String value : new String[]{withdrawalId.toString(), memberId.toString(), mchid, appid,
                outBatchNo, outDetailNo, Long.toString(amountFen), openid, effectiveRemark,
                Integer.toString(requestVersion), balanceBefore.setScale(2).toPlainString(),
                balanceAfter.setScale(2).toPlainString()}) {
            canonical.append(value.length()).append(':').append(value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long cents(BigDecimal amount) {
        if (amount == null) throw new IllegalStateException("提现原始金额缺失");
        return amount.movePointRight(2).longValueExact();
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String effectiveRemark(String remark) {
        return remark == null || remark.isEmpty() ? "分润提现" : remark.length() > 32 ? remark.substring(0, 32) : remark;
    }

    private static String hash(String mchid, String appid, String batch, String detail, long fen,
            String openid, String remark, int version) {
        if (blank(mchid) || mchid.length() > 64 || blank(appid) || appid.length() > 64
                || batch == null || !batch.matches("[A-Za-z0-9]{1,32}")
                || detail == null || !detail.matches("[A-Za-z0-9]{1,32}")
                || fen <= 0 || blank(openid) || openid.length() > 128
                || remark == null || remark.isEmpty() || remark.length() > 32 || version != REQUEST_VERSION)
            throw new IllegalArgumentException("原转账参数证据不完整");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream wire = new DataOutputStream(bytes);
            // Length-prefixed UTF-16 code units preserve Java substring semantics even at a surrogate boundary.
            // Version/order are fixed. Both batch and detail amounts/remarks and the fixed single-detail semantics bind.
            wire.writeInt(version); field(wire, "WECHAT_BATCH_TRANSFER"); field(wire, mchid); field(wire, appid);
            field(wire, batch); field(wire, BATCH_NAME); field(wire, remark); wire.writeLong(fen); wire.writeInt(TOTAL_NUM);
            field(wire, detail); wire.writeLong(fen); field(wire, openid); field(wire, remark);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception invalid) { throw new IllegalArgumentException("原转账参数无法生成指纹", invalid); }
    }

    private static void field(DataOutputStream wire, String value) throws java.io.IOException {
        if (value == null) throw new IllegalArgumentException("缺少原转账字段");
        wire.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) wire.writeChar(value.charAt(index));
    }

    public BigDecimal amountYuan() { return BigDecimal.valueOf(amountFen, 2); }

    @Override public String toString() {
        return "CashWithdrawTransferIntent[withdrawalId=" + withdrawalId + ", submissionIntentId="
                + submissionIntentId + ", requestVersion=" + requestVersion + "]";
    }
}
