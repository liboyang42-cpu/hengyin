package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.enums.TopicLifecycleState;

/** 主题生命周期的唯一迁移入口。 */
public interface ITopicLifecycleService {

    /**
     * 仅当数据库当前状态仍等于 expected 时迁移，避免旧请求覆盖较新的状态。
     *
     * @return true=迁移成功；false=当前状态已变化
     */
    boolean transition(Long topicId, TopicLifecycleState expected, TopicLifecycleState target);
}
