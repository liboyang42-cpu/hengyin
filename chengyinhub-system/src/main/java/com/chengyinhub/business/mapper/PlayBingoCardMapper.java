package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayBingoCard;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 宾果任务卡。 */
public interface PlayBingoCardMapper {

    PlayBingoCard selectCard(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 开卡。撞 uk_member_topic 说明并发下别人先开了,调用方重查即可。 */
    int insertCard(PlayBingoCard card);

    /**
     * 填格:直接在库里做位或,不是"读出来算完再写回去"。
     *
     * <p>并发下两个事件同时填不同格子,读改写会丢掉一个;库里位或不会。
     * 这也让重放天然无副作用 —— 同一位或两次结果不变。
     */
    int fillCell(@Param("memberId") Long memberId,
                 @Param("topicId") Long topicId,
                 @Param("bit") Integer bit,
                 @Param("updateTime") Date updateTime);

    /**
     * 记账已发的奖档。带 reward_mask 未含该档的条件,命中 0 行即为重复发放。
     */
    int markRewarded(@Param("memberId") Long memberId,
                     @Param("topicId") Long topicId,
                     @Param("tierBits") Integer tierBits,
                     @Param("updateTime") Date updateTime);
}
