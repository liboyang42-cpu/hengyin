package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.domain.vo.RecommendationItemVO;
import org.apache.ibatis.annotations.Param;

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
}
