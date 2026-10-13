package com.chengyinhub.business.mapper;

import java.util.List;

import com.chengyinhub.business.domain.NpcLineCache;

/**
 * NPC 事件话术缓存 Mapper。
 *
 * @author chengyinhub
 */
public interface NpcLineCacheMapper
{
    /**
     * 按 profile + event + node 查已审话术(含 nodeId 为 NULL 的通用话术)。
     */
    List<NpcLineCache> selectByProfileAndEvent(Long profileId, String eventType, Long nodeId);
}
