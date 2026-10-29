package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ClubPostRepost;

/**
 * 俱乐部帖文转发 Mapper 接口
 */
public interface ClubPostRepostMapper
{
    /** 不论有效与否,取这对 (帖文, 会员) 的那一行;唯一键保证至多一行。 */
    public ClubPostRepost selectByPostAndMember(@Param("postId") Long postId, @Param("memberId") Long memberId);

    public int insertClubPostRepost(ClubPostRepost repost);

    /** 已撤销的行重新生效,转发时间刷新。只改 del_flag=2 的行。 */
    public int reactivateById(@Param("id") Long id, @Param("createTime") Date createTime);

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
}
