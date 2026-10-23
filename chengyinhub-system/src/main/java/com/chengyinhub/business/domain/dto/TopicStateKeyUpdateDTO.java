package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.ThemeStateKey;

/**
 * F2 T01 创作者端单个状态键定义覆盖入参(PUT /api/topic/state-keys/{key})。
 *
 * <p>scope 与 {@link TopicStateKeyRegisterDTO} 同义(MERCHANT = 商家 Owner 代管);键名取路径,
 * {@code key.stateKey} 可省,带了就必须与路径一致。
 */
public class TopicStateKeyUpdateDTO {

    private Long topicId;

    private String scope;

    /** 新定义;整体覆盖 label / 初值 / 范围 / 展示 / 持久化 / 规则,键名与类型不变。 */
    private ThemeStateKey key;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getScope() { return scope; }

    public void setScope(String scope) { this.scope = scope; }

    public ThemeStateKey getKey() { return key; }

    public void setKey(ThemeStateKey key) { this.key = key; }
}
