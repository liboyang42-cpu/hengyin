package com.chengyinhub.business.guestwalk.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.Map;

/**
 * R4 guest-walk: one row per saved pre-sign-in walk (roam_guest_walk). An install saves at most one walk and a member
 * receives at most one (both unique keys), so a retried upload reads its first result back instead of crediting again.
 * Table: migration_20261115_guest_walk.sql.
 */
public interface GuestWalkMapper {

    @Select("select install_id as installId, walk_id as walkId, member_id as memberId, session_id as sessionId, "
            + "result_json as resultJson from roam_guest_walk where install_id = #{installId} limit 1")
    Map<String, Object> selectByInstall(@Param("installId") String installId);

    @Select("select install_id as installId, walk_id as walkId, member_id as memberId, session_id as sessionId, "
            + "result_json as resultJson from roam_guest_walk where member_id = #{memberId} limit 1")
    Map<String, Object> selectByMember(@Param("memberId") Long memberId);

    @Insert("insert into roam_guest_walk (install_id, walk_id, member_id, session_id, started_at, finished_at, "
            + "cells_sent, cells_credited, xp_awarded, withheld, trust_code, result_json, create_time) values "
            + "(#{installId}, #{walkId}, #{memberId}, #{sessionId}, #{startedAt}, #{finishedAt}, #{cellsSent}, "
            + "#{cellsCredited}, #{xpAwarded}, #{withheld}, #{trustCode}, #{resultJson}, now())")
    int insert(@Param("installId") String installId, @Param("walkId") String walkId, @Param("memberId") Long memberId,
               @Param("sessionId") Long sessionId, @Param("startedAt") Date startedAt, @Param("finishedAt") Date finishedAt,
               @Param("cellsSent") int cellsSent, @Param("cellsCredited") int cellsCredited,
               @Param("xpAwarded") int xpAwarded, @Param("withheld") int withheld,
               @Param("trustCode") String trustCode, @Param("resultJson") String resultJson);
}
