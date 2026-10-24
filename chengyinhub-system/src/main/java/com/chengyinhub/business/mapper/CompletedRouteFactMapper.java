package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.support.CompletedRouteFactReader.NodeProgressRequest;
import com.chengyinhub.business.service.support.CompletedRouteFactReader.NodeProgressRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 只读原事实：路线候选独立于PG；节点页仅看PG，不提供历史派奖或经济资格。 */
public interface CompletedRouteFactMapper {
    List<TopicRouteSession> selectCurrentCompletedRoutes(@Param("memberId") Long memberId);

    List<NodeProgressRow> selectNodeProgressPage(@Param("memberId") Long memberId,
                                                @Param("requests") List<NodeProgressRequest> requests);
}
