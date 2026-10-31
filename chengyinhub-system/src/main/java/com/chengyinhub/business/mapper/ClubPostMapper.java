package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Date;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRepost;

/**
 * 俱乐部动态 Mapper 接口
 */
public interface ClubPostMapper
{
    public ClubPost selectClubPostById(Long id);

    public ClubPost selectClubPostByIdForUpdate(Long id);

    /** 列表(join 作者昵称头像,del_flag=0,倒序) */
    public List<ClubPost> selectClubPostList(ClubPost clubPost);

    /** 动态流分页:pinned=true 取置顶帖;否则非置顶帖 id 倒序,beforeId 之前 */
    List<ClubPost> selectClubPostPage(@Param("clubId") Long clubId, @Param("pinned") boolean pinned,
                                      @Param("beforeId") Long beforeId, @Param("limit") int limit);

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

    /** 转发数 +delta（原子） */
    public int incrementRepostCount(@Param("id") Long id, @Param("delta") int delta);

    /** 转发条目里嵌的原帖:只取仍在营俱乐部里未删的帖文。ids 必须非空。 */
    List<ClubPost> selectFeedPostsByIds(@Param("ids") List<Long> ids);

    // ---- 帖文转发 club_post_repost(dw29 L8):转发是帖文聚合的一部分,与转发数同一 mapper ----

    /** 不论有效与否,取这对 (帖文, 会员) 的那一行;唯一键保证至多一行。 */
    public ClubPostRepost selectRepostByPostAndMember(@Param("postId") Long postId, @Param("memberId") Long memberId);

    public int insertClubPostRepost(ClubPostRepost repost);

    /** 已撤销的行重新生效,转发时间刷新。只改 del_flag=2 的行。 */
    public int reactivateRepostById(@Param("id") Long id, @Param("createTime") Date createTime);

    /** 撤销有效转发。只改本人、有效的那一行。 */
    public int cancelByPostAndMember(@Param("postId") Long postId, @Param("memberId") Long memberId);

    /** postIds 里本人有效转发过的帖文ID。postIds 必须非空。 */
    public List<Long> selectRepostedPostIds(@Param("memberId") Long memberId, @Param("postIds") List<Long> postIds);

    /**
     * 动态流里的转发条目:转发人是 clubIds 任一俱乐部的在册成员;按转发时间倒序。
     * since(含)/ until(不含)为空即不设界。clubIds 必须非空。
     */
    public List<ClubPostRepost> selectFeedReposts(@Param("clubIds") List<Long> clubIds,
                                                  @Param("since") Date since,
                                                  @Param("until") Date until,
                                                  @Param("limit") int limit);

    /** 聚合动态流:按俱乐部集合取。clubIds 必须非空(空集合会生成 `in ()` 语法错,调用方先挡)。 */
    List<ClubPost> selectFeedByClubIds(@org.apache.ibatis.annotations.Param("clubIds") List<Long> clubIds,
                                       @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 全站帖文流(发现页):审核通过且对 viewer 可见的俱乐部的帖文,排除双向拉黑,按 id 倒序,beforeId 为翻页游标。 */
    List<ClubPost> selectSiteFeed(@Param("viewerId") Long viewerId, @Param("beforeId") Long beforeId,
                                  @Param("limit") int limit);
}
