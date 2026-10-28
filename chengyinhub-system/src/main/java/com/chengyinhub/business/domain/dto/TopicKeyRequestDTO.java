package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * 文档二 L8 创作者写接口共用请求体(属性 / 按名字建键 / 改名 / 排序 / 开局带着)。
 * 各接口只读自己用到的字段,见 docs/design/doc2-ui-impl-20261028/api-contracts.md §2.4 / §3。
 */
public class TopicKeyRequestDTO {

    private Long topicId;
    private String scope;
    /** 属性类型预设。 */
    private String preset;
    /** 线索 clue / 标签 tag / 数值 counter。 */
    private String kind;
    private String key;
    private String label;
    private Integer min;
    private Integer max;
    private Integer initial;
    private List<String> keys;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getMin() { return min; }
    public void setMin(Integer min) { this.min = min; }
    public Integer getMax() { return max; }
    public void setMax(Integer max) { this.max = max; }
    public Integer getInitial() { return initial; }
    public void setInitial(Integer initial) { this.initial = initial; }
    public List<String> getKeys() { return keys; }
    public void setKeys(List<String> keys) { this.keys = keys; }
}
