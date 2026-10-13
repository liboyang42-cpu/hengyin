package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Date;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ClubPost;

/**
 * 俱乐部动态 Mapper 接口
 */
public interface ClubPostMapper
{
    public ClubPost selectClubPostById(Long id);

    public ClubPost selectClubPostByIdForUpdate(Long id);

    /** 列表(join 作者昵称头像,del_flag=0,倒序) */
    public List<ClubPost> selectClubPostList(ClubPost clubPost);

    public int insertClubPost(ClubPost clubPost);

    public int updateClubPost(ClubPost clubPost);

    int updateContentCas(@Param("id") Long id,
                         @Param("content") String content,
                         @Param("images") String images,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("updateTime") Date updateTime);

    int updatePinnedCas(@Param("id") Long id,
                        @Param("isPinned") int isPinned,
                        @Param("pinnedAt") Date pinnedAt,
                        @Param("expectedVersion") int expectedVersion,
                        @Param("updateTime") Date updateTime);

    /** 软删 */
    public int deleteClubPostById(Long id);

    /** 点赞数 +delta（原子） */
    public int incrementLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /** 评论数 +delta（原子） */
    public int incrementCommentCount(@Param("id") Long id, @Param("delta") int delta);

    /** 聚合动态流:按俱乐部集合取。clubIds 必须非空(空集合会生成 `in ()` 语法错,调用方先挡)。 */
    List<ClubPost> selectFeedByClubIds(@org.apache.ibatis.annotations.Param("clubIds") List<Long> clubIds,
                                       @org.apache.ibatis.annotations.Param("limit") int limit);
}
