package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopClubApply;

/**
 * 俱乐部承接申请 Mapper 接口(P0-4 开放范围)
 */
public interface CoopClubApplyMapper {

    public CoopClubApply selectCoopClubApplyById(Long id);

    public List<CoopClubApply> selectCoopClubApplyList(CoopClubApply coopClubApply);

    /** 唯一行:同一 (topic, club) 至多一条(uk_club_apply_topic_club) */
    public CoopClubApply selectByTopicAndClub(@Param("topicId") Long topicId, @Param("clubId") Long clubId);

    /** 俱乐部侧「我的申请」:club_id ∈ 我的俱乐部 */
    public List<CoopClubApply> selectByClubIds(@Param("clubIds") List<Long> clubIds);

    /** 发布者侧「收到的」:cms_topic.member_id = 我 的全部主题上的申请(不看锁价/开放态,历史也返回) */
    public List<CoopClubApply> selectByTopicOwner(@Param("memberId") Long memberId);

    public int insertCoopClubApply(CoopClubApply coopClubApply);

    public int updateCoopClubApply(CoopClubApply coopClubApply);

    /** 婉拒/撤回后重新申请:同行复位 status=0、换留言、清 handle_time(<if> 更新无法置 NULL,单列语句) */
    public int reapplyCoopClubApply(@Param("id") Long id, @Param("message") String message);
}
