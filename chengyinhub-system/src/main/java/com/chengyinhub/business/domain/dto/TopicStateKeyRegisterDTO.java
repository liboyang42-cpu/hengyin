package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.ThemeStateKey;

import java.util.List;

/**
 * R8 创作者端状态键批量注册入参。
 *
 * <p>scope 与主题编辑接口同义:显式声明 MERCHANT 时按商家 Owner 主体鉴权(员工代管场景)。
 */
public class TopicStateKeyRegisterDTO {

    private Long topicId;

    private String scope;

    /** 待注册的键;逐条校验,任一条非法则整批不写(见 ThemeStateKeyService#register)。 */
    private List<ThemeStateKey> keys;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getScope() { return scope; }

    public void setScope(String scope) { this.scope = scope; }

    public List<ThemeStateKey> getKeys() { return keys; }

    public void setKeys(List<ThemeStateKey> keys) { this.keys = keys; }
}
