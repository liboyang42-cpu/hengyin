package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 节点名次奖牌占位 Mapper。
 *
 * <p>某 node 全平台头三个完成的人各拿金/银/铜,第四位起无牌。并发裁判是
 * node_medal_claim 的 PRIMARY KEY (node_id, medal_rank),不在应用层。
 */
public interface NodeMedalClaimMapper
{
    /** 抢占一个档位。撞 PK(该档被别人占)或 uk_node_member(自己已持牌)会抛 DuplicateKeyException。 */
    int insertClaim(@Param("nodeId") Long nodeId, @Param("medalRank") int medalRank,
                    @Param("memberId") Long memberId, @Param("activityId") Long activityId,
                    @Param("topicId") Long topicId, @Param("progressId") Long progressId);

    Integer selectRankByProgress(@Param("nodeId") Long nodeId,@Param("memberId") Long memberId,@Param("progressId") Long progressId);

    /** 回读:此人在该节点上已持有的名次;无牌返回 null。 */
    Integer selectRankByNodeAndMember(@Param("nodeId") Long nodeId, @Param("memberId") Long memberId);
}
