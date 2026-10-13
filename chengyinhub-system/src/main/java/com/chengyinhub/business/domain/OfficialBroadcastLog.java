package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 官方通知分发日志(uk_bc_member_channel 防重发 + 触达/点击统计)。
 *
 * @author cy
 * @date 2026-07-08
 */
public class OfficialBroadcastLog
{
    private Long id;
    private Long broadcastId;
    private Long memberId;
    private String role;       // player/club/merchant
    private String channel;    // inapp/subscribe
    private Integer reached;
    private Integer clicked;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBroadcastId() { return broadcastId; }
    public void setBroadcastId(Long broadcastId) { this.broadcastId = broadcastId; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Integer getReached() { return reached; }
    public void setReached(Integer reached) { this.reached = reached; }

    public Integer getClicked() { return clicked; }
    public void setClicked(Integer clicked) { this.clicked = clicked; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
