package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import java.util.Date;

/** Append-only facts for one original cash withdrawal; never inferred from its workflow status. */
public class CashWithdrawFundFact {
    public static final String DEBIT = "DEBIT";
    public static final String SUBMIT_INTENT = "SUBMIT_INTENT";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_ACCEPTED = "NOT_ACCEPTED";
    public static final String FINAL_FAIL = "FINAL_FAIL";
    public static final String SUCCESS = "SUCCESS";
    public static final String REFUND = "REFUND";

    private String requestMchid;
    private String requestAppid;
    private Long requestAmountFen;
    private String requestOpenid;
    private String requestRemark;
    private Integer requestVersion;
    private String requestHash;
    private String channelBatchId;

    private Long id;
    private Long withdrawalId;
    private Long memberId;
    private BigDecimal amount;
    private String outBatchNo;
    private String outDetailNo;
    private String kind;
    private Long submissionIntentId;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private Date createTime;
    private String channelBatchStatus;
    private String channelDetailStatus;
    private String channelDetailId;
    private String channelErrorCode;

    public static CashWithdrawFundFact of(MemberCashWithdraw w, String kind) {
        CashWithdrawFundFact f = new CashWithdrawFundFact();
        f.withdrawalId = w.getId(); f.memberId = w.getMemberId(); f.amount = w.getAmount();
        f.outBatchNo = w.getOutBatchNo(); f.outDetailNo = w.getOutDetailNo(); f.kind = kind;
        f.createTime = new Date();
        return f;
    }

    public boolean matches(MemberCashWithdraw w) {
        return w != null && withdrawalId != null && withdrawalId.equals(w.getId())
                && memberId != null && memberId.equals(w.getMemberId())
                && amount != null && w.getAmount() != null && amount.compareTo(w.getAmount()) == 0
                && outBatchNo != null && outBatchNo.equals(w.getOutBatchNo())
                && outDetailNo != null && outDetailNo.equals(w.getOutDetailNo());
    }

    public void bindTransferIntent(CashWithdrawTransferIntent intent) {
        requestMchid = intent.mchid(); requestAppid = intent.appid(); requestAmountFen = intent.amountFen();
        requestOpenid = intent.openid(); requestRemark = intent.effectiveRemark();
        requestVersion = intent.requestVersion(); requestHash = intent.requestHash();
    }

    public CashWithdrawTransferIntent transferIntent() {
        return CashWithdrawTransferIntent.restore(withdrawalId, SUBMIT_INTENT.equals(kind) ? id : submissionIntentId,
                requestMchid, requestAppid, outBatchNo, outDetailNo, requestAmountFen, requestOpenid,
                requestRemark, requestVersion, requestHash);
    }

    public String getRequestMchid() { return requestMchid; }
    public void setRequestMchid(String value) { requestMchid = value; }
    public String getRequestAppid() { return requestAppid; }
    public void setRequestAppid(String value) { requestAppid = value; }
    public Long getRequestAmountFen() { return requestAmountFen; }
    public void setRequestAmountFen(Long value) { requestAmountFen = value; }
    public String getRequestOpenid() { return requestOpenid; }
    public void setRequestOpenid(String value) { requestOpenid = value; }
    public String getRequestRemark() { return requestRemark; }
    public void setRequestRemark(String value) { requestRemark = value; }
    public Integer getRequestVersion() { return requestVersion; }
    public void setRequestVersion(Integer value) { requestVersion = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getChannelBatchId() { return channelBatchId; }
    public void setChannelBatchId(String value) { channelBatchId = value; }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getWithdrawalId() { return withdrawalId; }
    public void setWithdrawalId(Long value) { withdrawalId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getOutBatchNo() { return outBatchNo; }
    public void setOutBatchNo(String value) { outBatchNo = value; }
    public String getOutDetailNo() { return outDetailNo; }
    public void setOutDetailNo(String value) { outDetailNo = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public Long getSubmissionIntentId() { return submissionIntentId; }
    public void setSubmissionIntentId(Long value) { submissionIntentId = value; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal value) { balanceBefore = value; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal value) { balanceAfter = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public String getChannelBatchStatus() { return channelBatchStatus; }
    public void setChannelBatchStatus(String value) { channelBatchStatus = value; }
    public String getChannelDetailStatus() { return channelDetailStatus; }
    public void setChannelDetailStatus(String value) { channelDetailStatus = value; }
    public String getChannelDetailId() { return channelDetailId; }
    public void setChannelDetailId(String value) { channelDetailId = value; }
    public String getChannelErrorCode() { return channelErrorCode; }
    public void setChannelErrorCode(String value) { channelErrorCode = value; }
}
