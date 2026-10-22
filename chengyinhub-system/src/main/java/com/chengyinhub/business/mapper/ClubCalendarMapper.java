package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部月历场次只读投影(W10a #19)。
 *
 * <p>场次 = 本俱乐部名下的 cms_activity(主理人开团写 club_id + topic_id)。
 * 只读、只按 club_id 收窄;不写任何表。
 */
public interface ClubCalendarMapper {
    /**
     * [rangeStart, rangeEnd) 内开始的本俱乐部场次,按开始时间升序。
     *
     * @param includeUnpublished false ⇒ 只回公开可见场次(已过审已发布、未取消、父主题在架);
     *                           true ⇒ 治理者视角,未删除的全部场次(含待审/已取消)
     * @param limit              最多返回条数;调用方多取一条判断是否截断
     */
    List<Map<String, Object>> selectClubSessionsInRange(@Param("clubId") Long clubId,
                                                        @Param("rangeStart") LocalDateTime rangeStart,
                                                        @Param("rangeEnd") LocalDateTime rangeEnd,
                                                        @Param("includeUnpublished") boolean includeUnpublished,
                                                        @Param("limit") int limit);
}
