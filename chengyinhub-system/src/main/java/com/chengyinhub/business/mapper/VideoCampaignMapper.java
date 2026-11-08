package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.VideoCampaign;

/**
 * 视频悬赏活动配置 Mapper。
 *
 * @author chengyinhub
 */
public interface VideoCampaignMapper
{
    VideoCampaign selectById(Long id);

    VideoCampaign selectByCampaignKey(@Param("campaignKey") String campaignKey);

    /** 串行化"累加活动已应计"用:同一活动的并发放置在同一把行锁上。 */
    VideoCampaign selectByCampaignKeyForUpdate(@Param("campaignKey") String campaignKey);

    List<VideoCampaign> selectList(@Param("status") Integer status);

    int insertCampaign(VideoCampaign campaign);

    /**
     * 更新配置。仅当状态不是"进行中"或规则版本变化时才允许调用,
     * 具体策略在服务层判定;此处只做 CAS 于 {@code updateTime} 不为空的基本写入。
     *
     * <p>⚠️ 不提供"改费率即追溯历史应计"的路径:历史应计行保留自己的 {@code ruleVersion}。
     */
    int updateCampaign(VideoCampaign campaign);

    /** CAS 活动状态:只有当前状态等于 expected 时才改。 */
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") Integer expectedStatus,
                              @Param("newStatus") Integer newStatus);

    /** 已记录应计合计(只加总 RECORDED_ONLY/PENDING/PAID,不含 SUPERSEDED/REVERSED)。 */
    java.math.BigDecimal sumActiveAccrual(@Param("campaignId") Long campaignId);

    /**
     * 同活动内<b>除指定组以外</b>的已记录应计合计。
     *
     * <p>为什么必须排除本组：整组重算会整组替换，本组旧行马上要作废；
     * 若把本组旧值算进"已用预算"，重算就会被二次 clamp（预算已用满时新应计被压成 0），
     * 变成"替换成漏算"。组内封顶由 {@code per_video_cap} 负责，活动预算只应由其他组占用。
     */
    java.math.BigDecimal sumActiveAccrualExcludingGroup(@Param("campaignId") Long campaignId,
                                                        @Param("groupKey") String groupKey);
}
