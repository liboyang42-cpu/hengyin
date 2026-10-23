package com.chengyinhub.common.observability;

/**
 * O-11 关键路径失败事件码。码值即枚举名，一经发布不得改名或复用（告警规则按它匹配），
 * 新增码须同步 docs/delivery/doc2-o11-o17.md 的事件表（CriticalPathCodeContractTest 校验）。
 */
public enum CriticalPathCode
{
    /** 微信支付回调 inbox 事务失败（已按 owner/fence 释放，微信会重投） */
    CP_PAYMENT_INBOX_FAILED,
    /** 核销（券 / 章节权益）失败 */
    CP_REDEMPTION_FAILED,
    /** 退款审核、出款、出款结果回写、退款回调失败 */
    CP_REFUND_FAILED,
    /** 结算分账入账（支付入账 / 券核销入账）失败 */
    CP_SETTLEMENT_ACCRUAL_FAILED,
    /** 同意记录（协议 / 授权）写入失败 */
    CP_CONSENT_WRITE_FAILED,
    /** 审核决定（通过 / 驳回 / 撤回）失败 */
    CP_AUDIT_DECISION_FAILED,
    /** 账号注销申请 / 撤销 / 到期执行失败 */
    CP_DEREGISTRATION_FAILED
}
