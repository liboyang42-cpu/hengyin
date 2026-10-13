package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 协作信用记录 coop_credit_log(报告§二漏洞3 · D-5)。
 *
 * 记录履约/超时/取消违约事件,主页展示近90天履约率,P3 与可用槽位配额挂钩。
 */
public class CoopCreditLog extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 被记账方memberId */
    private Long memberId;

    /** 事件 0履约 1超时未配置 2保护期外取消 3保护期内违约 */
    private Integer eventType;

    /** 关联邀约 */
    private Long inviteId;

    /** 理由/备注 */
    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getEventType() { return eventType; }
    public void setEventType(Integer eventType) { this.eventType = eventType; }
    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long inviteId) { this.inviteId = inviteId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
