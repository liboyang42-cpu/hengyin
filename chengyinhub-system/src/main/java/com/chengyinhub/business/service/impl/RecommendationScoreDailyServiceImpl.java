package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.MemberInterestProfile;
import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.domain.vo.RecommendationCandidateVO;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import com.chengyinhub.business.service.IRecommendationScoreDailyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O-14 每日推荐打分(v1 口径，写在这里便于评审):
 * score = 0.5 × 热度归一 + 0.3 × 类型兴趣占比 + 0.2 × 同城。
 * <ul>
 *   <li>热度：近 30 天收藏 + 3×已付报名，按候选池最大值归一；</li>
 *   <li>类型兴趣：member_interest_profile.category_weights_json 中该类型权重 / 总权重；</li>
 *   <li>同城：画像 city_code 与候选 city_code 相同。</li>
 * </ul>
 * 候选池只含对任何人都公开的内容；不推荐会员自己发布的内容。日期一律按 Asia/Shanghai。
 */
@Service
public class RecommendationScoreDailyServiceImpl implements IRecommendationScoreDailyService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationScoreDailyServiceImpl.class);

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int POPULARITY_WINDOW_DAYS = 30;
    static final int ACTIVE_WINDOW_DAYS = 30;
    static final int RETENTION_DAYS = 7;
    static final int POOL_LIMIT = 200;
    static final int PROFILE_PAGE_SIZE = 500;
    static final int TOP_N = 20;
    static final String[] CANDIDATE_TYPES = {"topic", "activity"};

    private static final BigDecimal W_POPULARITY = new BigDecimal("0.5");
    private static final BigDecimal W_INTEREST = new BigDecimal("0.3");
    private static final BigDecimal W_CITY = new BigDecimal("0.2");

    @Autowired
    private RecommendationScoreDailyMapper recommendationScoreDailyMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Override
    public Map<String, Integer> computeDaily(LocalDate statDate) {
        Date stat = toDate(statDate);
        Map<String, List<RecommendationCandidateVO>> pools = new LinkedHashMap<>();
        Date popularitySince = toDate(statDate.minusDays(POPULARITY_WINDOW_DAYS));
        pools.put("topic", nonNull(recommendationScoreDailyMapper.selectTopicCandidatePool(popularitySince, POOL_LIMIT)));
        pools.put("activity", nonNull(recommendationScoreDailyMapper.selectActivityCandidatePool(popularitySince, POOL_LIMIT)));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Date activeSince = toDate(statDate.minusDays(ACTIVE_WINDOW_DAYS));
        Date now = new Date();
        int members = 0;
        int rows = 0;
        int failures = 0;
        long afterMemberId = 0L;
        while (true) {
            List<MemberInterestProfile> page = nonNull(recommendationScoreDailyMapper
                    .selectActiveInterestProfiles(activeSince, afterMemberId, PROFILE_PAGE_SIZE));
            if (page.isEmpty()) break;
            for (MemberInterestProfile profile : page) {
                afterMemberId = Math.max(afterMemberId, profile.getMemberId());
                members++;
                for (String type : CANDIDATE_TYPES) {
                    List<RecommendationScoreDaily> scores = score(stat, profile, type, pools.get(type), now);
                    try {
                        tx.executeWithoutResult(status -> {
                            recommendationScoreDailyMapper.deleteMemberDailyScores(stat, profile.getMemberId(), type);
                            if (!scores.isEmpty()) {
                                recommendationScoreDailyMapper.batchInsertRecommendationScoreDaily(scores);
                            }
                        });
                        rows += scores.size();
                    } catch (RuntimeException failure) {
                        // 单个会员失败不阻断其余会员；同日重跑会覆盖，最终仍让任务留下失败状态。
                        failures++;
                        log.warn("每日推荐打分失败 statDate={} memberId={} type={} error={}",
                                statDate, profile.getMemberId(), type, failure.getClass().getSimpleName());
                    }
                }
            }
            if (page.size() < PROFILE_PAGE_SIZE) break;
        }
        int purged = recommendationScoreDailyMapper.deleteScoresBefore(toDate(statDate.minusDays(RETENTION_DAYS - 1)));
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("members", members);
        result.put("rows", rows);
        result.put("failures", failures);
        result.put("purged", purged);
        return result;
    }

    List<RecommendationScoreDaily> score(Date stat, MemberInterestProfile profile, String type,
                                         List<RecommendationCandidateVO> pool, Date now) {
        long maxPopularity = 0L;
        for (RecommendationCandidateVO c : pool) {
            maxPopularity = Math.max(maxPopularity, c.getPopularity() == null ? 0L : c.getPopularity());
        }
        BigDecimal interest = interestShare(profile.getCategoryWeightsJson(), type);
        List<RecommendationScoreDaily> scored = new ArrayList<>();
        for (RecommendationCandidateVO c : pool) {
            if (c.getCandidateId() == null || profile.getMemberId().equals(c.getOwnerMemberId())) continue;
            long popularity = c.getPopularity() == null ? 0L : c.getPopularity();
            BigDecimal popularityNorm = maxPopularity <= 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(popularity).divide(BigDecimal.valueOf(maxPopularity), 4, RoundingMode.HALF_UP);
            boolean sameCity = profile.getCityCode() != null && !profile.getCityCode().isEmpty()
                    && profile.getCityCode().equals(c.getCityCode());
            BigDecimal score = W_POPULARITY.multiply(popularityNorm)
                    .add(W_INTEREST.multiply(interest))
                    .add(sameCity ? W_CITY : BigDecimal.ZERO)
                    .setScale(4, RoundingMode.HALF_UP);
            List<String> reasons = new ArrayList<>();
            if (popularity > 0) reasons.add("popular");
            if (interest.signum() > 0) reasons.add("interest_" + type);
            if (sameCity) reasons.add("same_city");
            if (reasons.isEmpty()) reasons.add("fresh");

            RecommendationScoreDaily row = new RecommendationScoreDaily();
            row.setStatDate(stat);
            row.setMemberId(profile.getMemberId());
            row.setCandidateType(type);
            row.setCandidateId(c.getCandidateId());
            row.setScore(score);
            row.setReasonCodes(String.join(",", reasons));
            row.setCreateTime(now);
            scored.add(row);
        }
        // 池子已按热度/新鲜度排好；稳定排序保证同分时沿用池内顺序，重跑结果一致。
        scored.sort(Comparator.comparing(RecommendationScoreDaily::getScore).reversed());
        List<RecommendationScoreDaily> top = new ArrayList<>(scored.subList(0, Math.min(TOP_N, scored.size())));
        for (int i = 0; i < top.size(); i++) {
            top.get(i).setRankPosition(i + 1);
        }
        return top;
    }

    static BigDecimal interestShare(String categoryWeightsJson, String type) {
        if (categoryWeightsJson == null || categoryWeightsJson.isEmpty()) return BigDecimal.ZERO;
        JSONObject weights;
        try {
            weights = JSON.parseObject(categoryWeightsJson);
        } catch (RuntimeException malformed) {
            // 画像是旁路写入的统计值：坏 JSON 只让兴趣项记 0，不让整个会员打分失败。
            log.debug("member_interest_profile.category_weights_json 解析失败，兴趣项按 0 计");
            return BigDecimal.ZERO;
        }
        if (weights == null) return BigDecimal.ZERO;
        long total = 0L;
        long mine = 0L;
        for (Map.Entry<String, Object> entry : weights.entrySet()) {
            long value = entry.getValue() instanceof Number ? ((Number) entry.getValue()).longValue() : 0L;
            if (value <= 0) continue;
            total += value;
            if (type.equals(entry.getKey())) mine = value;
        }
        return total <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(mine).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE).toInstant());
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
