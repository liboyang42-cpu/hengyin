package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * O-01 /api/play/arrive 位置守卫判定留痕(play_arrive_guard_log)。
 *
 * <p>只记服务端的站点坐标与端上上报的精度,**不落玩家原始定位**:速度/位移判定只需要
 * 「上一站坐标 + 上一站时间」,玩家位置已由 50m 围栏约束在站点附近。
 * 被拒行的 id 即申诉引用号(audit_appeal.biz_type='play_arrive_guard', biz_id=本表 id)。
 */
public class PlayArriveGuardLog
{
    private Long id;
    private Long memberId;
    private Long activityId;
    private Long topicId;
    private Long nodeId;
    private BigDecimal nodeLat;
    private BigDecimal nodeLng;
    /** 端上上报的水平精度(米);旧版小程序未上报为 null */
    private BigDecimal accuracyM;
    /** PASS / REJECT / SHADOW(观察模式下本应拒绝、实际放行) */
    private String verdict;
    /** 判定原因码,PASS 为 null */
    private String reasonCode;
    /** 供申诉审核看的判定数据(速度、位移、时间差) */
    private String detail;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public BigDecimal getNodeLat() { return nodeLat; }
    public void setNodeLat(BigDecimal nodeLat) { this.nodeLat = nodeLat; }
    public BigDecimal getNodeLng() { return nodeLng; }
    public void setNodeLng(BigDecimal nodeLng) { this.nodeLng = nodeLng; }
    public BigDecimal getAccuracyM() { return accuracyM; }
    public void setAccuracyM(BigDecimal accuracyM) { this.accuracyM = accuracyM; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
