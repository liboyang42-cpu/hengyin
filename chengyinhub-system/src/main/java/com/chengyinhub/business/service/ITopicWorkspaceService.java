package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;

/** 主题运营工作台只读聚合服务。 */
public interface ITopicWorkspaceService {
    TopicWorkspaceDTO getWorkspace(Long topicId);
}
