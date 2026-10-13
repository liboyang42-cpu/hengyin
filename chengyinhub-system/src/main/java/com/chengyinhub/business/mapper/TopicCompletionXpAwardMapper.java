package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 玩家每期通关 XP 的一次性发放事实，供玩家完局面回读。 */
public interface TopicCompletionXpAwardMapper
{
    int insert(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
               @Param("xpAmount") Integer xpAmount, @Param("grantedAt") Date grantedAt);

    TopicCompletionAwardVO selectOne(@Param("memberId") Long memberId,
                                     @Param("topicId") Long topicId);

    /** 拍板4 冲回。 */
    Integer selectXpForUpdate(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    int deleteAward(@Param("memberId") Long memberId, @Param("topicId") Long topicId);
}
