package com.chengyinhub.business.service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRepost;

/**
 * 俱乐部帖文转发 Service 接口(dw29 L8)
 *
 * 可见性、封禁与锁由调用方(接口层)先过完,与点赞同一顺序;这里只管去重、撤销和转发数。
 */
public interface IClubPostRepostService
{
    /** 本人对这条帖文当前是否有有效转发 */
    public boolean isReposted(Long postId, Long memberId);

    /**
     * 把本人对帖文的转发置为目标态。已是目标态则什么都不写(幂等,不会重复计数)。
     * @return 是否真的改了状态
     */
    public boolean setReposted(Long postId, Long memberId, boolean reposted);

    /** postIds 里本人有效转发过的帖文ID */
    public Set<Long> selectRepostedPostIds(Long memberId, List<Long> postIds);

    /** 转发人属于 clubIds 的有效转发,按转发时间倒序;since 含、until 不含,空即不设界。 */
    public List<ClubPostRepost> selectFeedReposts(List<Long> clubIds, Date since, Date until, int limit);

    /** 转发条目要嵌的原帖:只返回仍在营俱乐部里未删的帖文。 */
    public List<ClubPost> selectOriginalPosts(List<Long> postIds);
}
