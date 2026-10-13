package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部承接申请 coop_club_apply(P0-4 开放范围)。
 *
 * 「开放报名」承接路径的意向记录:商家把主题开放给俱乐部承接(cms_topic.open_club_pool=1)后,
 * 俱乐部主理人在合作池发起申请。【§3.6 定稿】申请只表意向、不带条款、不生成合作单 ——
 * 商家从候选池选中后,走现成 POST /api/coop/invite 发 type1 带条款邀约,
 * 俱乐部接受(含保证金)才经 acceptInviteWithLock 生成 coop_order。
 *
 * status:0待处理 1已婉拒(商家) 2已撤回(俱乐部) 3已转为邀约(待俱乐部确认)。
 *
 * ★为什么没有「已通过」:主办方同意后**不是直接批准申请**,而是回发一张带 originApplyId 的
 * type=1 邀约(申请不成单,条款随邀约走)。2026-08-07 之前这里少了状态 3 ⇒ 转成邀约后
 * 申请仍停在 0「待处理」,**俱乐部侧永远看不到自己被通过了**,还以为主办方没理他。
 * 「已邀约/已合作」不落在本表,读取时按 coop_invite(topic_id + to_type=club + to_id)派生,避免双写失步。
 * 同一 (topic_id, club_id) 唯一一行,婉拒/撤回后重新申请 = 同行复位为 0。
 */
public class CoopClubApply extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 主题ID */
    private Long topicId;

    /** 申请俱乐部ID */
    private Long clubId;

    /** 申请时的俱乐部主理人 memberId */
    private Long memberId;

    /** 状态 0待处理 1已婉拒 2已撤回 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DECLINED = 1;
    public static final int STATUS_WITHDRAWN = 2;
    /** 已转为邀约:主办方同意了,球在俱乐部那边(去邀约列表确认条款)。 */
    public static final int STATUS_CONVERTED = 3;

    private Integer status;

    /** 申请留言 */
    private String message;

    /** 处理时间(婉拒/撤回) */
    private java.util.Date handleTime;

    /** 删除标志 0存在 2删除 */
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public java.util.Date getHandleTime() { return handleTime; }
    public void setHandleTime(java.util.Date handleTime) { this.handleTime = handleTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
