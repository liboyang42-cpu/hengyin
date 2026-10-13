package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 俱乐部应付台账行。F / Q / EXTRA / HEADCOUNT 各自一笔，component 分开记，禁止互相抵扣。
 * 只追加：撤销写反向新行（REVERSAL，金额为负），历史金额永不被 UPDATE 改写。
 *
 * <p>2026-08-25 退役 {@code TICKET_SOURCE}：它是「这张票是谁引流来的」的 10% 渠道奖励，
 * 但 {@code sourceClubId} 只是分享深链上的 query 参数，任何登录玩家都能自填，
 * 全仓没有任何「这一单真的被某俱乐部引流过」的服务端事实 ⇒ 早已 fail-closed 成恒 0。
 * 账面上挂一个永远发 0 元的资金分量，是本仓最典型的「假保证」形态。退役时生产
 * {@code club_payable} 尚为 0 行，没有历史包袱。要恢复必须先定义引流凭据的四段语义
 * （签发 → 持久化 → 消费 → 防重放），届时无论如何都要重写这条链路。
 */
public class ClubPayable {
    public static final String COMPONENT_FIXED = "FIXED";
    public static final String COMPONENT_QUALITY = "QUALITY";
    public static final String COMPONENT_EXTRA = "EXTRA";
    /**
     * 按已赚票数计酬的承接费（2026-08-25 换轨，取代按工时的 EXTRA）。
     *
     * <p>口径是「这一期一共卖出并且没退的有多少张」的执行酬劳，与每张票的引流归属无关
     * —— 后者曾由已退役的 TICKET_SOURCE 承担（见类注释）。
     */
    public static final String COMPONENT_HEADCOUNT = "HEADCOUNT";
    public static final String ENTRY_ACCRUAL = "ACCRUAL";
    public static final String ENTRY_REVERSAL = "REVERSAL";

    private Long id;
    private Long topicId;
    private Long clubId;
    private String component;
    private String entryType;
    private BigDecimal amount;
    private Long batchId;
    private String idempotencyKey;
    private Long reversalOf;
    /**
     * 红冲 / 重计提的理由。★必须<b>落库</b>：本仓 {@code LogAspect} 已被加固过，
     * {@code sys_oper_log} 只记 title/method/url/操作人/耗时，<b>请求体与响应体一律不落</b>
     * （全文 grep {@code setOperParam} / {@code setJsonResult} 为 0）。
     * 「理由随 @Log 落审计」是假的，一条无因由的负向资金行不满足审计 2「必要审计：类别、金额、说明」。
     */
    private String reason;
    private Long createdBy;
    private Date createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public String getComponent() { return component; } public void setComponent(String v) { component = v; }
    public String getEntryType() { return entryType; } public void setEntryType(String v) { entryType = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public Long getBatchId() { return batchId; } public void setBatchId(Long v) { batchId = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public Long getReversalOf() { return reversalOf; } public void setReversalOf(Long v) { reversalOf = v; }
    public String getReason() { return reason; } public void setReason(String v) { reason = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Date getCreatedAt() { return createdAt; } public void setCreatedAt(Date v) { createdAt = v; }
}
