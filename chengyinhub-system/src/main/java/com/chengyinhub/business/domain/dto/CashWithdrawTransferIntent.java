package com.chengyinhub.business.domain.dto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable original wire parameters. Database association IDs are deliberately outside the wire hash. */
public record CashWithdrawTransferIntent(Long withdrawalId, Long submissionIntentId, String mchid,
        String appid, String outBatchNo, String outDetailNo, long amountFen, String openid,
        String effectiveRemark, int requestVersion, String requestHash) {
    public static final int REQUEST_VERSION = 1;
    public static final String BATCH_NAME = "城瘾分润提现";
    public static final int TOTAL_NUM = 1;

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
        return restore(withdrawalId, id, mchid, appid, outBatchNo, outDetailNo, amountFen, openid,
                effectiveRemark, requestVersion, requestHash);
    }

    public void requireValid() {
        if (withdrawalId == null || withdrawalId <= 0 || submissionIntentId == null || submissionIntentId <= 0
                || requestHash == null || !requestHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原转账意图关联或指纹不完整");
        String expected = canonicalRequestHash();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), requestHash.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("原转账请求指纹不一致");
    }

    public String canonicalRequestHash() {
        return hash(mchid, appid, outBatchNo, outDetailNo, amountFen, openid, effectiveRemark, requestVersion);
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
