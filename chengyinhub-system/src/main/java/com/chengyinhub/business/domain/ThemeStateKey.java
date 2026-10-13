package com.chengyinhub.business.domain;

import java.util.Date;
import java.util.regex.Pattern;

/**
 * 主题具名状态键注册表(B1 旅程体验模组化)。
 *
 * <p>主题先在这里注册键,玩法块配置(advancedConfigJson)的 requires/effects/check 才能引用;
 * 发布期会拦截引用了未注册键的主题。运行期状态只由服务端写,本表只存注册声明。
 *
 * @author chengyinhub
 * @date 2026-09-16
 */
public class ThemeStateKey {

    /** 键名正则。clue./relation./tag./counter. 四种前缀,后缀小写下划线,总长 ≤56。 */
    public static final Pattern KEY_NAME = Pattern.compile("^(clue|relation|tag|counter)\\.[a-z][a-z0-9_]{0,47}$");

    public static final String TYPE_FLAG = "flag";
    public static final String TYPE_COUNTER = "counter";
    public static final String TYPE_RELATION = "relation";
    public static final String TYPE_TAG = "tag";

    public static final String SCOPE_RUN = "run";
    public static final String SCOPE_PARTY = "party";
    public static final String SCOPE_DEPLOYMENT = "deployment";

    private Long id;

    /** 所属主题 cms_topic.id。 */
    private Long topicId;

    /** 键名,见 {@link #KEY_NAME}。 */
    private String stateKey;

    /** flag / counter / relation / tag;命名空间与类型一一对应:clue.*=flag、counter.*=counter、relation.*=relation、tag.*=tag。 */
    private String keyType;

    /** run / party / deployment;本轮只实现 run,其余只存不用。 */
    private String scope;

    /** 作者可读名。 */
    private String label;

    /** 初始值,仅 counter 有意义。 */
    private Integer initialValue;

    private Date createTime;

    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getStateKey() { return stateKey; }
    public void setStateKey(String stateKey) { this.stateKey = stateKey; }

    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Integer getInitialValue() { return initialValue; }
    public void setInitialValue(Integer initialValue) { this.initialValue = initialValue; }

    /**
     * 玩家可见展示名(R22-C3):label 为空回落键名去掉前缀后的部分(tag.hurried → hurried),
     * 绝不回落带前缀的完整键名 —— 状态键名是服务端/创作端口径,不该出现在玩家文案里。
     * 案件册与阶段结算的代价标签同用这一份(同数据两个接口口径必须一致)。
     */
    public String displayLabel() {
        if (label != null && !label.trim().isEmpty()) return label;
        String key = stateKey == null ? "" : stateKey;
        int dot = key.indexOf('.');
        return dot >= 0 && dot + 1 < key.length() ? key.substring(dot + 1) : key;
    }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
