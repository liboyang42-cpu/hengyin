package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 圈层主题商家供给声明快照。 */
public interface MerchantCircleSupplyProfileMapper {
    int insert(MerchantCircleSupplyProfile profile);

    /** 新版本写入前退休当前版本；历史版本保持只读。 */
    int retireCurrentSnapshot(@Param("offerId") Long offerId);

    MerchantCircleSupplyProfile selectByOfferId(@Param("offerId") Long offerId);

    /** 只返回当前仍能进入新计划的供给；夜间7日时效和单场结束在 SQL 侧同口径过滤。 */
    List<MerchantCircleSupplyProfile> selectEffectiveByTopicId(@Param("topicId") Long topicId);

    /** 发布前读取草稿实例的有效供给；不要求主题已经公开。 */
    List<MerchantCircleSupplyProfile> selectEffectiveForPublishByTopicId(@Param("topicId") Long topicId);

    /** 城市实例公开可见门槛按不同商家数计算，不能用同一商家的多份供给凑满3家。 */
    int countEffectiveMerchantsByTopicId(@Param("topicId") Long topicId);

    int countEffectiveOfferForTopic(@Param("topicId") Long topicId, @Param("offerId") Long offerId);
}
