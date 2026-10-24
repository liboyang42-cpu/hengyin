package com.chengyinhub.business.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable original wire parameters. Database association IDs are deliberately outside the wire hash. */
public final class CashWithdrawTransferIntent {
    public static final int REQUEST_VERSION = 1;
    public static final String BATCH_NAME = "城瘾分润提现";
    public static final int TOTAL_NUM = 1;
    private final Long withdrawalId;
    private final Long submissionIntentId;
    private final String mchid, appid, outBatchNo, outDetailNo, openid, effectiveRemark, requestHash;
    private final long amountFen;
    private final int requestVersion;

    private CashWithdrawTransferIntent(Long withdrawalId, Long submissionIntentId, String mchid, String appid,
            String outBatchNo, String outDetailNo, long amountFen, String openid, String effectiveRemark,
            int requestVersion, String requestHash) {
        this.withdrawalId = withdrawalId; this.submissionIntentId = submissionIntentId;
        this.mchid = mchid; this.appid = appid; this.outBatchNo = outBatchNo; this.outDetailNo = outDetailNo;
        this.amountFen = amountFen; this.openid = openid; this.effectiveRemark = effectiveRemark;
        this.requestVersion = requestVersion; this.requestHash = requestHash;
        validate();
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
        return new CashWithdrawTransferIntent(withdrawalId, submissionIntentId, mchid, appid, outBatchNo,
                outDetailNo, amountFen, openid, effectiveRemark, requestVersion, requestHash);
    }

    public CashWithdrawTransferIntent withSubmissionIntentId(Long id) {
        return restore(withdrawalId, id, mchid, appid, outBatchNo, outDetailNo, amountFen, openid,
                effectiveRemark, requestVersion, requestHash);
    }

    public void validate() {
        if (withdrawalId == null || withdrawalId <= 0 || (submissionIntentId != null && submissionIntentId <= 0)
                || blank(mchid) || mchid.length() > 64 || blank(appid) || appid.length() > 64
                || outBatchNo == null || !outBatchNo.matches("[A-Za-z0-9]{1,32}")
                || outDetailNo == null || !outDetailNo.matches("[A-Za-z0-9]{1,32}")
                || amountFen <= 0 || blank(openid) || openid.length() > 128
                || effectiveRemark == null || effectiveRemark.isEmpty() || effectiveRemark.length() > 32
                || requestVersion != REQUEST_VERSION || requestHash == null || !requestHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原转账意图证据不完整");
        String expected = hash(mchid, appid, outBatchNo, outDetailNo, amountFen, openid, effectiveRemark, requestVersion);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), requestHash.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("原转账请求指纹不一致");
    }

    /** Gateway entrypoints require the association assigned by the committed SUBMIT_INTENT. */
    public void requirePersisted() {
        validate();
        if (submissionIntentId == null) throw new IllegalArgumentException("缺少已提交的原转账意图关联");
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String effectiveRemark(String remark) {
        return remark == null || remark.isEmpty() ? "分润提现" : remark.length() > 32 ? remark.substring(0, 32) : remark;
    }

    private static String hash(String mchid, String appid, String batch, String detail, long fen,
            String openid, String remark, int version) {
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

    public Long getWithdrawalId() { return withdrawalId; }
    public Long getSubmissionIntentId() { return submissionIntentId; }
    public String getMchid() { return mchid; }
    public String getAppid() { return appid; }
    public String getOutBatchNo() { return outBatchNo; }
    public String getOutDetailNo() { return outDetailNo; }
    public long getAmountFen() { return amountFen; }
    public BigDecimal getAmountYuan() { return BigDecimal.valueOf(amountFen, 2); }
    public String getOpenid() { return openid; }
    public String getEffectiveRemark() { return effectiveRemark; }
    public int getRequestVersion() { return requestVersion; }
    public String getRequestHash() { return requestHash; }
}
