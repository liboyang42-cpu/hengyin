package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPostRevision;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 编辑历史只追加、只读取，不提供更新或删除。 */
public interface ClubPostRevisionMapper {
    int insertClubPostRevision(ClubPostRevision revision);
    List<ClubPostRevision> selectByPostId(@Param("postId") Long postId);
}
