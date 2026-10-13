package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P3 推荐策略后台入口。
 */
@RestController
@RequestMapping("/business/recommendation")
public class RecommendationController extends BaseController {

    @Autowired
    private RecommendationScoreDailyMapper recommendationScoreDailyMapper;

    @PreAuthorize("@ss.hasPermi('business:recommendation:list')")
    @GetMapping("/scores")
    public TableDataInfo listScores(String candidateType, Integer limit) {
        RecommendationScoreDaily query = new RecommendationScoreDaily();
        query.setCandidateType(candidateType);
        startPage();
        List<RecommendationScoreDaily> list = recommendationScoreDailyMapper.selectAdminScoreList(query);
        return getDataTable(list);
    }
}
