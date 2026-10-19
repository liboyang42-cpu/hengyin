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

    /**
     * CL-39：他人只看机审 PASS 的图片(正文照常)，作者本人保留全部图片并带 mediaReviewState。
     * viewerId 为 null 视为他人(未登录)。原地修改 posts。
     */
    void applyMediaVisibility(List<ClubPost> posts, Long viewerId);
}
