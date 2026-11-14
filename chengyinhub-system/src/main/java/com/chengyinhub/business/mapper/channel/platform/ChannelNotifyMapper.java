package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 通知偏好（im_notify_pref）与读状态投影的读写原语。 */
public interface ChannelNotifyMapper
{
    Map<String, Object> selectPref(@Param("memberId") long memberId, @Param("scopeType") int scopeType, @Param("scopeId") long scopeId);

    Map<String, Object> lockPref(@Param("memberId") long memberId, @Param("scopeType") int scopeType, @Param("scopeId") long scopeId);

    List<Map<String, Object>> listPrefs(@Param("memberId") long memberId);

    int upsertPref(Map<String, Object> row);

    Map<String, Object> selectClubDefault(@Param("clubId") long clubId);

    Map<String, Object> lockClubDefault(@Param("clubId") long clubId);

    /** 首次保存（version 1）；已存在时返回 0，由调用方按版本冲突处理。 */
    int insertClubDefault(@Param("clubId") long clubId, @Param("level") int level, @Param("updatedBy") long updatedBy);

    /** 按版本 CAS：version 不等时返回 0。 */
    int updateClubDefault(@Param("clubId") long clubId, @Param("level") int level, @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") long updatedBy);

    Integer conversationType(@Param("id") long id);

    Map<String, Object> membership(@Param("conversationId") long conversationId, @Param("memberId") long memberId);

    Long latestVisible(@Param("conversationId") long conversationId);

    List<Map<String, Object>> unreadRows(@Param("memberId") long memberId);
}
