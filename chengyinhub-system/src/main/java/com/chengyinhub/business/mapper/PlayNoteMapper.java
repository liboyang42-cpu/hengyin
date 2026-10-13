package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayNote;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 预制人生 note 留言的跨玩家存储。 */
public interface PlayNoteMapper {

    /** 留下一句。撞 uk_member_node = 同一个人在同一站已经留过,调用方据此拒重复。 */
    int insert(PlayNote note);

    /** 这一站最近的前几条留言(时间倒序),供 note 段投影 previous / mine。 */
    List<PlayNote> selectRecentByNode(@Param("nodeId") Long nodeId, @Param("limit") int limit);
}
