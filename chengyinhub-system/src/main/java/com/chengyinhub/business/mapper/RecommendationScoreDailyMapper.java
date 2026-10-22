package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberInterestProfile;
import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.domain.vo.RecommendationCandidateVO;
import com.chengyinhub.business.domain.vo.RecommendationItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * P3 推荐评分 Mapper。
 */
public interface RecommendationScoreDailyMapper {
    int insertRecommendationScoreDaily(RecommendationScoreDaily score);

    List<RecommendationScoreDaily> selectAdminScoreList(RecommendationScoreDaily score);

    List<RecommendationItemVO> selectColdStartItems(@Param("candidateType") String candidateType,
                                                    @Param("limit") Integer limit);

    List<RecommendationItemVO> selectCandidateItems(@Param("memberId") Long memberId,
                                                    @Param("candidateType") String candidateType,
                                                    @Param("limit") Integer limit);

    /** O-14:公开主题候选池，按近 N 天热度(收藏 + 3×已付报名)降序。 */
    List<RecommendationCandidateVO> selectTopicCandidatePool(@Param("since") Date since,
                                                             @Param("limit") Integer limit);

    /** O-14:公开活动候选池，口径同上。 */
    List<RecommendationCandidateVO> selectActivityCandidatePool(@Param("since") Date since,
                                                                @Param("limit") Integer limit);

    /** O-14:近期有行为的会员画像，按 member_id 键集分页。 */
    List<MemberInterestProfile> selectActiveInterestProfiles(@Param("activeSince") Date activeSince,
                                                             @Param("afterMemberId") Long afterMemberId,
                                                             @Param("limit") Integer limit);

    int deleteMemberDailyScores(@Param("statDate") Date statDate,
                                @Param("memberId") Long memberId,
                                @Param("candidateType") String candidateType);

    int batchInsertRecommendationScoreDaily(@Param("list") List<RecommendationScoreDaily> scores);

    int deleteScoresBefore(@Param("cutoff") Date cutoff);
}
