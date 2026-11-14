package com.chengyinhub.business.mapper.channel.platform;

import java.util.Date;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 空间入会申请台账（im_join_request）与暂停入会（im_space_admission）。 */
public interface ChannelAdmissionMapper
{
    int insertRequest(Map<String, Object> row);

    Map<String, Object> lockRequest(@Param("id") long id);

    int setStatus(@Param("id") long id, @Param("status") int status, @Param("reviewerId") Long reviewerId,
            @Param("reasonCode") Integer reasonCode, @Param("note") String note);

    Map<String, Object> selectSetting(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    int upsertSetting(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("paused") int paused,
            @Param("until") Date until, @Param("reason") String reason, @Param("operator") long operator);
}
