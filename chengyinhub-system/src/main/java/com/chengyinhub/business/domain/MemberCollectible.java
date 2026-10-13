package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * FE-01 玩家收集(碎片/彩蛋)member 级持久化。
 * coll_type:1=碎片(mode2 完成节点)/ 2=彩蛋(途中触发)。
 *
 * @author cy
 * @date 2026-07-07
 */
public class MemberCollectible
{
    private Long id;
    private Long memberId;
    private Integer collType;   // 1=碎片 2=彩蛋
    private Long topicId;
    private Long refId;         // 碎片=node_id / 彩蛋=egg_id
    private String content;
    private Date createTime;

    public static final int TYPE_FRAGMENT = 1;
    public static final int TYPE_EGG = 2;
    public static final int TYPE_NODE_FAV = 3;   // 漫游商家节点收藏(ref_id=roam_poi.id)
    public static final int TYPE_EVENT_BADGE = 4; // 官方活动限定徽章(ref_id=official_event.id)
    public static final int TYPE_EXPLORE_STAMP = 5; // 探店日按章核销凭证(ref_id=chapter_id)

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getCollType() { return collType; }
    public void setCollType(Integer collType) { this.collType = collType; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
