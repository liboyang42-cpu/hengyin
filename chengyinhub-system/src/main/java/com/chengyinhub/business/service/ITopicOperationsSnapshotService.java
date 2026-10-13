package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.TopicOperationsSnapshotDTO;

/** 主题工作台 P0 的统一待办只读快照。 */
public interface ITopicOperationsSnapshotService {

    /**
     * 返回一个主题下可安全展示的领域状态摘要；主题不存在或已删除时返回 {@code null}。
     */
    TopicOperationsSnapshotDTO getSnapshot(Long topicId);
}
