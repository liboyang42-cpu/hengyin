package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 主题角色卡跨局属性台账(F2 RPG P0 / T02)。
 *
 * <p>一位玩家在一个主题下一行,只存 {@code persist_scope=topic} 的属性值;run 作用域的属性
 * 每局从初值重置,不落这里。只由服务端在建新一局时从该玩家最近一局的权威状态结转,
 * {@code (sourceSessionId, sourceVersion)} 只增不减 —— 同一份来源重放不会改写台账。
 *
 * @author chengyinhub
 * @date 2026-10-22
 */
public class ThemeCharacter {

    private Long id;

    private Long memberId;

    private Long topicId;

    /** {stateKey:int},只含 topic 作用域的属性。 */
    private String valuesJson;

    private Long sourceSessionId;

    private Integer sourceVersion;

    private Date createTime;

    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getValuesJson() { return valuesJson; }
    public void setValuesJson(String valuesJson) { this.valuesJson = valuesJson; }

    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long sourceSessionId) { this.sourceSessionId = sourceSessionId; }

    public Integer getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(Integer sourceVersion) { this.sourceVersion = sourceVersion; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
