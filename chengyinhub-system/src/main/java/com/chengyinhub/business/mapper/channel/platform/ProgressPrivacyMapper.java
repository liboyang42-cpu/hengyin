package com.chengyinhub.business.mapper.channel.platform;

import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 进度隐私（member_progress_privacy / im_channel_progress_privacy）读写原语。 */
public interface ProgressPrivacyMapper
{
    Map<String, Object> selectMember(@Param("memberId") long memberId);

    Map<String, Object> lockMember(@Param("memberId") long memberId);

    int upsertMember(Map<String, Object> row);

    Map<String, Object> selectChannel(@Param("channelId") long channelId);

    Map<String, Object> lockChannel(@Param("channelId") long channelId);

    int upsertChannel(@Param("channelId") long channelId, @Param("aggregateScope") String aggregateScope, @Param("operator") long operator);
}
