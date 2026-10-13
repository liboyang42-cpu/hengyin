package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M6 随机掉落规则 reward_drop_rule。
 */
public class RewardDropRule {
    private Long id;
    /** 0=全局 1=指定主题 2=指定场景 */
    private Integer scope;
    private Long topicId;
    /** scope=2 时匹配调用场景，例如 roam_merchant_poi。 */
    private String sceneKey;
    /** 1=XP倍率 2=徽章碎片 3=商家彩蛋券 */
    private Integer dropType;
    private Integer weight;
    /** 类型参数 json:{"mult":2} / {"badgeCode":"x"} / {"couponTemplateId":1} */
    private String paramJson;
    /** 1=启用 0=停用 */
    private Integer status;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getScope() { return scope; }
    public void setScope(Integer scope) { this.scope = scope; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSceneKey() { return sceneKey; }
    public void setSceneKey(String sceneKey) { this.sceneKey = sceneKey; }
    public Integer getDropType() { return dropType; }
    public void setDropType(Integer dropType) { this.dropType = dropType; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public String getParamJson() { return paramJson; }
    public void setParamJson(String paramJson) { this.paramJson = paramJson; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
