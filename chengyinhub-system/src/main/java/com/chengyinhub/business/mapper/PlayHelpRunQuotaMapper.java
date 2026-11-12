package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayHelpRunQuota;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 整局共享免费提示额度的原子计数器。
 *
 * <p>额度 key 是 {@code (topic_id, activity_id, play_instance_id, owner_member_id)}：
 * 个人局按会员隔离；shared-game 命名空间按已核验的 team owner ID 共池。
 * 只读写 {@code play_help_run_quota} 一行；不参与积分、不写第二套钱包。
 */
public interface PlayHelpRunQuotaMapper {

    /**
     * 第一次使用时冻结 {@code quotaTotal}；行已存在时保持原 total/owner 不变
     * （旧局不随主题改价重置，也不被另一会员改写）。
     */
    int insertIfAbsent(@Param("topicId") Long topicId,
                       @Param("activityId") Long activityId,
                       @Param("playInstanceId") String playInstanceId,
                       @Param("ownerMemberId") Long ownerMemberId,
                       @Param("quotaTotal") Integer quotaTotal);

    /** shared-game 命名空间的 ownerMemberId 是已核验的 team owner ID；旧消费只在首次创建时结转。 */
    int insertSharedIfAbsent(@Param("topicId") Long topicId,
                             @Param("activityId") Long activityId,
                             @Param("playInstanceId") String playInstanceId,
                             @Param("ownerMemberId") Long ownerMemberId,
                             @Param("quotaTotal") Integer quotaTotal,
                             @Param("quotaUsed") Integer quotaUsed);

    /** 返回本队本主题的已绑定旧额度，以及无法确定原局身份的旧额度；后者阻止换池。 */
    List<PlayHelpRunQuota> selectLegacySharedQuotas(@Param("topicId") Long topicId,
                                                   @Param("activityId") Long activityId,
                                                   @Param("gameSessionId") Long gameSessionId,
                                                   @Param("teamId") Long teamId);

    PlayHelpRunQuota selectByScope(@Param("topicId") Long topicId,
                                   @Param("activityId") Long activityId,
                                   @Param("playInstanceId") String playInstanceId,
                                   @Param("ownerMemberId") Long ownerMemberId);

    /**
     * 原子消费一次免费额度：同一 owner 的 {@code quota_used < quota_total} 才成功。
     * 同一 scope 的并发请求在这里串行；最后一份额度只有一个 UPDATE 返回 1。
     */
    int consumeOne(@Param("topicId") Long topicId,
                   @Param("activityId") Long activityId,
                   @Param("playInstanceId") String playInstanceId,
                   @Param("ownerMemberId") Long ownerMemberId);
}
