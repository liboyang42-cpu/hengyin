package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRevision;
import com.chengyinhub.business.domain.dto.ClubPostUpdateResult;

/**
 * 俱乐部动态 Service 接口
 */
public interface IClubPostService
{
    public ClubPost selectClubPostById(Long id);

    /** 在调用方事务内锁定动态，写入前复核删除状态。 */
    ClubPost selectClubPostByIdForUpdate(Long id);

    public List<ClubPost> selectClubPostList(ClubPost clubPost);

    /**
     * 俱乐部动态流一页:首页(beforeId 为空)= 置顶帖 + 最新 limit 条非置顶帖;
     * 翻页 = beforeId 之前的 limit 条非置顶帖。limit 由调用方限幅。
     */
    List<ClubPost> selectClubPostPage(Long clubId, Long beforeId, int limit);

    public int insertClubPost(ClubPost clubPost);

    /**
     * 在仍在营的俱乐部内发布动态。锁定 club 行并在同一事务内复核成员权限，
     * 防止解散与发帖并发时产生 success-after-dissolve 的幽灵动态。
     */
    public int insertClubPostInActiveClub(ClubPost clubPost, Long memberId);

    public int updateClubPost(ClubPost clubPost);

    ClubPostUpdateResult updatePost(Long principalMemberId, Long postId, String content, String images,
                                    Integer expectedVersion, String requestId);

    ClubPost setPinned(Long principalMemberId, Long postId, boolean pinned,
                       Integer expectedVersion, String requestId);

    List<ClubPostRevision> listRevisionHistory(Long postId);

    public int deleteClubPostById(Long id);

    /** 点赞数 +delta（原子） */
    public int incrementLikeCount(Long id, int delta);

    /** 评论数 +delta（原子） */
    public int incrementCommentCount(Long id, int delta);

    /** 聚合动态流:按俱乐部集合取,clubIds 为空直接返回空列表。 */
    List<ClubPost> selectFeedByClubIds(java.util.List<Long> clubIds, int limit);

    // ---- 帖文转发(dw29 L8)。可见性、封禁与锁由接口层先过完,与点赞同一顺序;这里只管去重、撤销和转发数。 ----

    /** 本人对这条帖文当前是否有有效转发 */
    boolean isReposted(Long postId, Long memberId);

    /**
     * 把本人对帖文的转发置为目标态。已是目标态则什么都不写(幂等,不会重复计数)。
     * @return 是否真的改了状态
     */
    boolean setReposted(Long postId, Long memberId, boolean reposted);

    /** postIds 里本人有效转发过的帖文ID */
    java.util.Set<Long> selectRepostedPostIds(Long memberId, List<Long> postIds);

    /** 转发人属于 clubIds 的有效转发,按转发时间倒序;since 含、until 不含,空即不设界。 */
    List<com.chengyinhub.business.domain.ClubPostRepost> selectFeedReposts(List<Long> clubIds, java.util.Date since, java.util.Date until, int limit);

    /** 转发条目要嵌的原帖:只返回仍在营俱乐部里未删的帖文。 */
    List<ClubPost> selectOriginalPosts(List<Long> postIds);
}
