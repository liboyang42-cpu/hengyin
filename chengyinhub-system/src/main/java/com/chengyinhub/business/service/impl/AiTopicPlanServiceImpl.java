package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.AiMerchantMatchSnapshot;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.vo.MerchantCandidateVO;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.domain.vo.ai.AiPlanNode;
import com.chengyinhub.business.domain.vo.ai.AiThemePlan;
import com.chengyinhub.business.mapper.AiMerchantMatchSnapshotMapper;
import com.chengyinhub.business.mapper.ApiAiMapper;
import com.chengyinhub.business.service.IAiTopicPlanService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;

/**
 * AI 主题方案生成实现。
 *
 * <p>规则层先按距离、合作评分与可用券库存从至多 60 个附近商家中选 Top-15；
 * 模型只负责策划表达，节点的商家名、地址、坐标与营业时间一律从候选事实回填。
 */
@Service
public class AiTopicPlanServiceImpl implements IAiTopicPlanService
{
    private static final Logger log = LoggerFactory.getLogger(AiTopicPlanServiceImpl.class);

    private static final int DEFAULT_RADIUS_M = 2000;
    private static final int MIN_RADIUS_M = 200;
    private static final int MAX_RADIUS_M = 5000;
    private static final int CANDIDATE_SCAN_LIMIT = 60;
    private static final int CANDIDATE_TOP_LIMIT = 15;
    private static final String PARSE_ERROR_MSG = "AI 响应格式异常,请重试";

    @Autowired
    private ApiAiMapper apiAiMapper;

    @Autowired
    private AiMerchantMatchSnapshotMapper snapshotMapper;

    @Autowired
    private AiGatewayService gateway;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Override
    public AiMerchantPlanResp generate(AiMerchantPlanReq req, Long memberId)
    {
        validateRequest(req);
        int radius = normalizedRadius(req.getRadiusM());
        String role = resolveRole(memberId);

        List<MerchantCandidateVO> nearby;
        try
        {
            nearby = apiAiMapper.selectNearbyMerchantCandidates(
                req.getLng(), req.getLat(), radius, CANDIDATE_SCAN_LIMIT);
        }
        catch (RuntimeException e)
        {
            log.error("[AiTopicPlan] candidate-query-failed memberId={}", memberId, e);
            throw new AiServiceException("商家候选查询失败,请稍后重试", e);
        }
        List<MerchantCandidateVO> candidates = selectTopCandidates(nearby, radius);
        if (candidates.isEmpty())
        {
            throw new AiServiceException("附近暂无可用商家节点,请调整地图位置或范围");
        }

        Map<Long, MerchantCandidateVO> facts = new HashMap<>();
        JSONArray candidateJson = new JSONArray();
        for (MerchantCandidateVO candidate : candidates)
        {
            if (candidate.getCandidateId() == null) continue;
            facts.put(candidate.getCandidateId(), candidate);
            candidateJson.add(toPromptCandidate(candidate));
        }
        if (facts.isEmpty())
        {
            throw new AiServiceException("附近商家候选数据不完整,请稍后重试");
        }

        JSONObject user = new JSONObject();
        user.put("idea", req.getIdea().trim());
        user.put("lat", req.getLat());
        user.put("lng", req.getLng());
        user.put("durationMin", req.getDurationMin());
        user.put("needCoupon", Boolean.TRUE.equals(req.getNeedCoupon()));
        user.put("candidates", candidateJson);

        AiGatewayResult gatewayResult = gateway.generate(memberId, role, "merchant_plan",
            SYS_MERCHANT_PLAN, user.toJSONString());
        if (isMockModel(gatewayResult))
        {
            throw new AiServiceException("AI 服务暂未就绪,请联系管理员");
        }

        List<AiThemePlan> plans = parsePlans(gatewayResult, facts);
        AiMerchantPlanResp response = new AiMerchantPlanResp();
        response.setTraceId(gatewayResult.getTraceId());
        response.setPlans(plans);
        response.setCandidateCount(candidateJson.size());
        response.setAiLabel("AI 辅助生成");
        saveSnapshot(memberId, gatewayResult.getTraceId(), req, radius, candidateJson, plans);
        return response;
    }

    private void validateRequest(AiMerchantPlanReq req)
    {
        if (req == null || isBlank(req.getIdea()))
        {
            throw new AiServiceException("请先输入主题想法");
        }
        if (req.getLat() == null || req.getLng() == null
                || !Double.isFinite(req.getLat()) || !Double.isFinite(req.getLng())
                || req.getLat() < -90D || req.getLat() > 90D
                || req.getLng() < -180D || req.getLng() > 180D)
        {
            throw new AiServiceException("请先在地图上选择方案所在区域");
        }
    }

    private int normalizedRadius(Integer radiusM)
    {
        int radius = radiusM == null || radiusM <= 0 ? DEFAULT_RADIUS_M : radiusM;
        return Math.max(MIN_RADIUS_M, Math.min(MAX_RADIUS_M, radius));
    }

    private List<MerchantCandidateVO> selectTopCandidates(List<MerchantCandidateVO> raw, int radius)
    {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<MerchantCandidateVO> ranked = new ArrayList<>();
        for (MerchantCandidateVO candidate : raw)
        {
            if (!hasRequiredFacts(candidate)) continue;
            candidate.setMatchScore(weightedScore(candidate, radius));
            ranked.add(candidate);
        }
        ranked.sort(new Comparator<MerchantCandidateVO>()
        {
            @Override
            public int compare(MerchantCandidateVO a, MerchantCandidateVO b)
            {
                int score = Double.compare(value(b.getMatchScore()), value(a.getMatchScore()));
                if (score != 0) return score;
                int distance = Double.compare(value(a.getDistance()), value(b.getDistance()));
                if (distance != 0) return distance;
                return Long.compare(a.getCandidateId(), b.getCandidateId());
            }
        });
        return new ArrayList<>(ranked.subList(0, Math.min(CANDIDATE_TOP_LIMIT, ranked.size())));
    }

    private boolean hasRequiredFacts(MerchantCandidateVO candidate)
    {
        if (candidate == null || candidate.getCandidateId() == null
                || isBlank(candidate.getMerchantName()) || isBlank(candidate.getAddress()))
        {
            return false;
        }
        try
        {
            double lng = Double.parseDouble(candidate.getLongitude());
            double lat = Double.parseDouble(candidate.getLatitude());
            return Double.isFinite(lng) && Double.isFinite(lat)
                && lng >= -180D && lng <= 180D && lat >= -90D && lat <= 90D;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private double weightedScore(MerchantCandidateVO candidate, int radius)
    {
        double distance = Math.max(0D, value(candidate.getDistance()));
        double distanceScore = 1D - Math.min(1D, distance / Math.max(1D, radius));
        double ratingScore = Math.max(0D, Math.min(1D, value(candidate.getRating()) / 5D));
        double stock = candidate.getCouponStock() == null ? 0D : Math.max(0D, candidate.getCouponStock());
        double stockScore = Math.min(1D, stock / 100D);
        return distanceScore * 0.5D + ratingScore * 0.3D + stockScore * 0.2D;
    }

    private double value(Double n)
    {
        return n == null ? 0D : n;
    }

    private JSONObject toPromptCandidate(MerchantCandidateVO c)
    {
        JSONObject json = new JSONObject();
        json.put("candidateId", c.getCandidateId());
        json.put("merchantId", c.getMerchantId());
        json.put("merchantName", c.getMerchantName());
        json.put("address", c.getAddress());
        json.put("longitude", c.getLongitude());
        json.put("latitude", c.getLatitude());
        json.put("businessTime", c.getBusinessTime());
        json.put("couponName", c.getCouponName());
        json.put("couponStock", c.getCouponStock());
        json.put("distance", c.getDistance());
        json.put("rating", c.getRating());
        json.put("matchScore", c.getMatchScore());
        json.put("suitActivityTypes", c.getSuitActivityTypes());
        return json;
    }

    private List<AiThemePlan> parsePlans(AiGatewayResult result, Map<Long, MerchantCandidateVO> facts)
    {
        try
        {
            JSONObject root = parseAiJson(result == null ? null : result.getText());
            JSONArray rawPlans = root.getJSONArray("plans");
            if (rawPlans == null || rawPlans.isEmpty()) throw new AiServiceException(PARSE_ERROR_MSG);
            List<AiThemePlan> plans = new ArrayList<>();
            for (int i = 0; i < rawPlans.size() && plans.size() < 3; i++)
            {
                JSONObject rawPlan = rawPlans.getJSONObject(i);
                AiThemePlan plan = rawPlan == null ? null : rawPlan.toJavaObject(AiThemePlan.class);
                if (plan == null || isBlank(plan.getTitle())) continue;
                List<AiPlanNode> factualNodes = new ArrayList<>();
                if (plan.getNodes() != null)
                {
                    for (AiPlanNode node : plan.getNodes())
                    {
                        MerchantCandidateVO fact = node == null ? null : facts.get(node.getCandidateId());
                        if (fact == null) continue;
                        restoreFact(node, fact, factualNodes.size() + 1);
                        factualNodes.add(node);
                    }
                }
                if (factualNodes.isEmpty()) continue;
                plan.setNodes(factualNodes);
                plans.add(plan);
            }
            if (plans.size() < 2) throw new AiServiceException(PARSE_ERROR_MSG);
            return plans;
        }
        catch (AiServiceException e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            log.error("[AiTopicPlan] parse-failed traceId={} text={}",
                result == null ? null : result.getTraceId(),
                truncate(result == null ? null : result.getText(), 200), e);
            throw new AiServiceException(PARSE_ERROR_MSG, e);
        }
    }

    private void restoreFact(AiPlanNode node, MerchantCandidateVO fact, int order)
    {
        node.setOrder(order);
        node.setMerchantName(fact.getMerchantName());
        node.setAddress(fact.getAddress());
        node.setLongitude(fact.getLongitude());
        node.setLatitude(fact.getLatitude());
        node.setBusinessTime(fact.getBusinessTime());
    }

    private JSONObject parseAiJson(String text)
    {
        String source = stripJsonFence(text);
        JSONObject json = JSON.parseObject(source);
        if (json == null) throw new com.alibaba.fastjson2.JSONException("empty ai response");
        return json;
    }

    private String stripJsonFence(String text)
    {
        if (text == null) return null;
        String source = text.trim();
        if (!source.startsWith("```")) return source;
        int newline = source.indexOf('\n');
        if (newline < 0) return source;
        source = source.substring(newline + 1);
        int close = source.lastIndexOf("```");
        return (close >= 0 ? source.substring(0, close) : source).trim();
    }

    private String resolveRole(Long memberId)
    {
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        return member == null ? IRoleService.ROLE_PLAYER : roleService.resolveRole(member);
    }

    private boolean isMockModel(AiGatewayResult result)
    {
        String model = result == null ? null : result.getModel();
        return model != null && model.toLowerCase().contains("mock");
    }

    private void saveSnapshot(Long memberId, String traceId, AiMerchantPlanReq req, int radius,
            JSONArray candidates, List<AiThemePlan> plans)
    {
        try
        {
            AiMerchantMatchSnapshot snapshot = new AiMerchantMatchSnapshot();
            snapshot.setMemberId(memberId);
            snapshot.setTraceId(traceId);
            snapshot.setIdea(truncate(req.getIdea(), 500));
            JSONObject conditions = new JSONObject();
            conditions.put("lat", req.getLat());
            conditions.put("lng", req.getLng());
            conditions.put("radiusM", radius);
            conditions.put("durationMin", req.getDurationMin());
            snapshot.setConditionsJson(conditions.toJSONString());
            snapshot.setCandidatesJson(candidates.toJSONString());
            snapshot.setChoiceJson(JSON.toJSONString(plans));
            snapshotMapper.insertAiMerchantMatchSnapshot(snapshot);
        }
        catch (RuntimeException e)
        {
            log.error("[AiTopicPlan] snapshot-failed traceId={}", traceId, e);
        }
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private String truncate(String value, int max)
    {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final String SYS_MERCHANT_PLAN =
        "[BIZ:merchant_plan]\n" +
        "你是一个城市定向主题策划助手。根据用户想法和候选商家列表,输出2~3套主题方案。\n" +
        "规则:只引用传入的candidateId;商家名、地址、坐标、营业时间、距离、评分和库存都是系统事实,不得修改或编造;只做策划与表达。\n" +
        "输出严格JSON(不要markdown代码块):\n" +
        "{\"plans\":[{\"title\":\"\",\"subtitle\":\"\",\"storyline\":\"\",\"tags\":[],\"estDurationMin\":0,\"fitReason\":\"\",\"risks\":[]," +
        "\"nodes\":[{\"order\":1,\"candidateId\":0,\"roleText\":\"起点·暖场\",\"task\":\"\",\"validationMethod\":4,\"hint1\":\"\",\"hint2\":\"\",\"feedbackText\":\"\",\"rewardSuggest\":\"\"}]}]}";
}
