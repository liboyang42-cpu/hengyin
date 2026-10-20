package com.chengyinhub.business.domain.dto;

/**
 * UGC 安全门禁裁决结果(合规实施方案 §3.2)。
 * 状态由门禁统一裁决,禁止各控制器自行拼状态。
 */
public class SafetyDecision {
    public static final String PASS = "PASS";   // 保存并公开展示
    public static final String HOLD = "HOLD";   // 保存为待审,仅作者可见,已建审核单
    public static final String BLOCK = "BLOCK"; // 不保存公开内容,返回可理解拒绝原因

    private String decision;
    private String reasonCode;
    private Long auditTaskId;
    private boolean visible;
    /** 先发后审时「审不了」的内容照常公开,但打标留待抽检 */
    private boolean flagged;
    private String flagReason;

    public static SafetyDecision pass() {
        SafetyDecision d = new SafetyDecision();
        d.decision = PASS; d.visible = true; d.reasonCode = "OK";
        return d;
    }

    /** 先发后审:公开可见,但自动检测没给出确定结论,打标供事后抽检 */
    public static SafetyDecision passFlagged(String reasonCode) {
        SafetyDecision d = pass();
        d.reasonCode = reasonCode; d.flagged = true; d.flagReason = reasonCode;
        return d;
    }

    public static SafetyDecision hold(String reasonCode, Long auditTaskId) {
        SafetyDecision d = new SafetyDecision();
        d.decision = HOLD; d.visible = false; d.reasonCode = reasonCode; d.auditTaskId = auditTaskId;
        return d;
    }

    public static SafetyDecision block(String reasonCode) {
        SafetyDecision d = new SafetyDecision();
        d.decision = BLOCK; d.visible = false; d.reasonCode = reasonCode;
        return d;
    }

    public boolean isPass() { return PASS.equals(decision); }
    public boolean isHold() { return HOLD.equals(decision); }
    public boolean isBlock() { return BLOCK.equals(decision); }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Long getAuditTaskId() { return auditTaskId; }
    public void setAuditTaskId(Long auditTaskId) { this.auditTaskId = auditTaskId; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean isFlagged() { return flagged; }
    public String getFlagReason() { return flagReason; }
}
