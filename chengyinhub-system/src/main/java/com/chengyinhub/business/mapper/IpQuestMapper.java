package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.IpQuest;

import java.util.List;

/**
 * P3 世界任务 Mapper。
 */
public interface IpQuestMapper {
    List<IpQuest> selectPublishedQuests(Long worldId);
}
