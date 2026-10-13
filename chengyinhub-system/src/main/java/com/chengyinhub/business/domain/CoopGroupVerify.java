package com.chengyinhub.business.domain;

import java.util.Date;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 团核销台账 coop_group_verify(D22 · 设计文档 §6.3)。
 *
 * <p>「哪个商家接待了哪个领队带的团」。① 有领队产品里团整体走完全程,每个节点商家接待的是**整个团**,
 * 不是零散来客 —— 所以组织槽有人时,商家的分账基数不是「他自己扫过的玩家」而是「他接待过的团」。
 *
 * <p>本表只记**存在性**,不冻结 heads/sales:商家扫团码的时刻俱乐部可能还没核销完全部玩家,
 * 此刻冻结必漏。基数在结算时按 activity_id 关联玩家场次核销事实现查。
 */
public class CoopGroupVerify extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long topicId;
    /** 签发团码时绑定的带队场次(cms_activity.id)，是俱乐部履约归属的不可变证据。 */
    private Long activityId;
    /** 该商家的生效合作单 —— 结算按它取分账条款(定稿§6.5-C:结算只认合作单) */
    private Long coopOrderId;
    /** 接待该团的商家 memberId(= coop_order.to_id,to_type=merchant) */
    private Long merchantMemberId;
    /** 出示团码的主理人/管理员 memberId，仅作接待证据审计；结算不再用它反推场次。 */
    private Long leaderMemberId;
    private Date verifyTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getCoopOrderId() { return coopOrderId; }
    public void setCoopOrderId(Long coopOrderId) { this.coopOrderId = coopOrderId; }

    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }

    public Long getLeaderMemberId() { return leaderMemberId; }
    public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }

    public Date getVerifyTime() { return verifyTime; }
    public void setVerifyTime(Date verifyTime) { this.verifyTime = verifyTime; }
}
