package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 只读完成列表候选，不依赖节点进度行，不提供历史派奖事实。 */
public interface CompletedRouteFactMapper {
    List<TopicRouteSession> selectCurrentCompletedRoutes(@Param("memberId") Long memberId);
}
