package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 微信零钱提现单 member_cash_withdraw(T5 真实出金)。
 * 商家/主理人一键把可用余额提现到微信零钱:扣余额→建单→微信商家转账→成功/失败(失败退余额)。
 */
public class MemberCashWithdraw extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 处理中:已提交微信,最终结果待定时查询确认(旧「0处理中」兼容) */
    public static final int STATUS_PROCESSING = 0;
    /** 成功:定时查询确认微信已完成转账到账(旧「1成功」兼容) */
    public static final int STATUS_SUCCESS = 1;
    /** 失败已退款:渠道失败且余额已原路回补(旧「2失败」兼容) */
    public static final int STATUS_FAILED_REFUNDED = 2;
    /** 失败待人工:渠道失败但回补失败/状态不明/超时,需人工核查 */
    public static final int STATUS_FAILED_MANUAL = 3;
    /** 人工处理人已登记回补，等待第二个财务账号复核 */
    public static final int STATUS_REFUND_PENDING_REVIEW = 4;
    /** 未实际扣款(终态):原子 UPDATE 影响 0 行,确定余额未扣,不进入人工回补流程 */
    public static final int STATUS_FAILED_NO_DEBIT = 5;

    private Long id;
    private Long memberId;
    private String openid;
    private BigDecimal amount;
    /** 商户批次单号(确定性幂等键=CW+id) */
    private String outBatchNo;
    private String outDetailNo;
    /** 微信批次单号(成功回写,对账) */
    private String transferBatchId;
    /** 微信明细号(渠道侧detail_id,对账用;P1-5新增,捕获接线留给L块) */
    private String wechatDetailId;
    /** 0处理中 1成功 2失败已退款 3失败待人工 4待复核 5未扣款(终态) */
    private Integer status;
    private String failReason;
    /** 渠道错误码(微信FailReasonType枚举/ServiceException错误码,机器可读;P1-5新增,捕获接线留给L块) */
    private String channelErrorCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date finishTime;
    private Integer delFlag;

    /** 微信批次状态 ACCEPTED/PROCESSING/FINISHED/CLOSED */
    private String wechatBatchStatus;
    /** 提交微信时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date submitTime;
    /** 最后查询微信时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastQueryTime;
    /** 余额扣减流水ID */
    private Long balanceDeductId;
    /** 余额回补流水ID */
    private Long balanceRefundId;
    /** 人工处理人 */
    private String manualOperator;
    /** 人工回补第二复核人，须与 manualOperator 不同 */
    private String manualVerifier;
    /** 人工处理说明 */
    private String manualNote;
    /** 审计 trace id */
    private String traceId;
    /** 审计操作人ID(自助提现=memberId,人工补单=operatorId,自动退款=0) */
    private Long auditBy;
    /** 审计操作时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date auditTime;

    /** P块冻结:1=冻结中(暂停自动化处理,不动余额),0/NULL=未冻结 */
    private Integer frozen;
    /** 冻结原因(运营填写) */
    private String frozenReason;
    /** 冻结操作人 */
    private String frozenBy;
    /** 冻结时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date frozenAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getOutBatchNo() { return outBatchNo; }
    public void setOutBatchNo(String outBatchNo) { this.outBatchNo = outBatchNo; }
    public String getOutDetailNo() { return outDetailNo; }
    public void setOutDetailNo(String outDetailNo) { this.outDetailNo = outDetailNo; }
    public String getTransferBatchId() { return transferBatchId; }
    public void setTransferBatchId(String transferBatchId) { this.transferBatchId = transferBatchId; }
    public String getWechatDetailId() { return wechatDetailId; }
    public void setWechatDetailId(String wechatDetailId) { this.wechatDetailId = wechatDetailId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getChannelErrorCode() { return channelErrorCode; }
    public void setChannelErrorCode(String channelErrorCode) { this.channelErrorCode = channelErrorCode; }
    public Date getFinishTime() { return finishTime; }
    public void setFinishTime(Date finishTime) { this.finishTime = finishTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getWechatBatchStatus() { return wechatBatchStatus; }
    public void setWechatBatchStatus(String wechatBatchStatus) { this.wechatBatchStatus = wechatBatchStatus; }
    public Date getSubmitTime() { return submitTime; }
    public void setSubmitTime(Date submitTime) { this.submitTime = submitTime; }
    public Date getLastQueryTime() { return lastQueryTime; }
    public void setLastQueryTime(Date lastQueryTime) { this.lastQueryTime = lastQueryTime; }
    public Long getBalanceDeductId() { return balanceDeductId; }
    public void setBalanceDeductId(Long balanceDeductId) { this.balanceDeductId = balanceDeductId; }
    public Long getBalanceRefundId() { return balanceRefundId; }
    public void setBalanceRefundId(Long balanceRefundId) { this.balanceRefundId = balanceRefundId; }
    public String getManualOperator() { return manualOperator; }
    public void setManualOperator(String manualOperator) { this.manualOperator = manualOperator; }
    public String getManualVerifier() { return manualVerifier; }
    public void setManualVerifier(String manualVerifier) { this.manualVerifier = manualVerifier; }
    public String getManualNote() { return manualNote; }
    public void setManualNote(String manualNote) { this.manualNote = manualNote; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getAuditBy() { return auditBy; }
    public void setAuditBy(Long auditBy) { this.auditBy = auditBy; }
    public Date getAuditTime() { return auditTime; }
    public void setAuditTime(Date auditTime) { this.auditTime = auditTime; }
    public Integer getFrozen() { return frozen; }
    public void setFrozen(Integer frozen) { this.frozen = frozen; }
    public String getFrozenReason() { return frozenReason; }
    public void setFrozenReason(String frozenReason) { this.frozenReason = frozenReason; }
    public String getFrozenBy() { return frozenBy; }
    public void setFrozenBy(String frozenBy) { this.frozenBy = frozenBy; }
    public Date getFrozenAt() { return frozenAt; }
    public void setFrozenAt(Date frozenAt) { this.frozenAt = frozenAt; }
}
