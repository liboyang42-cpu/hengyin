package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 商家店铺参谋:访客事件行(全历史,每 (member, entitlement, chapter) 一行)。
 * 与 MerchantInsightVisitFact 的区别:带展示字段(nickname/avatar/topicName),
 * 用于「最近到店玩家」单客行、来源主题列表、环比与新老客拆分 —— 只做页面展示与 Java 聚合,
 * **绝不进 AI 事实包**(prompt PII 白名单由 MerchantInsightServiceImplTest 锁)。
 *
 * @author chengyinhub
 */
public class MerchantInsightVisitorEvent
{
    private Long memberId;
    private String nickname;
    private String avatar;
    private String topicName;
    private Date checkinAt;
    private Date redeemedAt;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Date getCheckinAt() { return checkinAt; }
    public void setCheckinAt(Date checkinAt) { this.checkinAt = checkinAt; }
    public Date getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Date redeemedAt) { this.redeemedAt = redeemedAt; }
}
