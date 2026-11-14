package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 入会引导配置（im_onboarding_prompt/option）与成员最新答案（im_onboarding_answer）。 */
public interface ChannelOnboardingMapper
{
    List<Map<String, Object>> selectPrompts(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    List<Map<String, Object>> selectOptions(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    int insertPromptIgnore(Map<String, Object> row);

    Long selectPromptId(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("sortNo") int sortNo);

    int insertOptionIgnore(@Param("promptId") long promptId, @Param("label") String label, @Param("description") String description,
            @Param("emoji") String emoji, @Param("sortNo") int sortNo);

    Map<String, Object> selectAnswer(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId);

    int upsertAnswer(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId,
            @Param("hash") String hash, @Param("answers") String answers, @Param("requestId") String requestId);
}
