package com.chengyinhub.business.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiClubDesignReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantTaskSuggestReq;
import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.ai.AiTemplateFillReq;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.dto.ai.AiChapterOverviewReq;
import com.chengyinhub.business.domain.vo.ai.AiClubDesignResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantTaskSuggestResp;
import com.chengyinhub.business.domain.vo.ai.AiNodeContent;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.domain.vo.ai.AiTemplateContent;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.domain.vo.ai.AiPlanNode;
import com.chengyinhub.business.domain.vo.ai.AiSafetyIssue;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp;
import com.chengyinhub.business.domain.vo.ai.AiChapterOverviewResp;
import com.chengyinhub.business.domain.vo.ai.AiThemePlan;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IAiTopicPlanService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.AiThemeDraftPlan;
import com.chengyinhub.business.service.ai.AiThemeDraftPlanRejectedException;
import com.chengyinhub.business.service.ai.AiThemeDraftPlanRejectedException.Code;
import com.chengyinhub.business.service.support.CanonicalDigest;

/**
 * AI 创作服务实现:四大能力编排(候选查询→拼 prompt→调网关→解析 JSON→校验)。
 *
 * @author chengyinhub
 */
@Service
public class AiCreatorServiceImpl implements IAiCreatorService
{
    private static final Logger log = LoggerFactory.getLogger(AiCreatorServiceImpl.class);

    /** 解析失败时给前端的统一文案(与 safety_precheck 的 parse_error issue message 一致)。 */
    private static final String PARSE_ERROR_MSG = "AI 响应格式异常,请重试";
    private static final int PLAYER_THEME_DRAFT_DAILY_LIMIT = 3;
    /** merchant_insight 建议卡 type 白名单 —— 与小程序端 type→路由映射表一一对应 */
    private static final Set<String> INSIGHT_SUGGESTION_TYPES =
            new java.util.HashSet<>(java.util.Arrays.asList("topic_coop", "decor", "content"));
    private static final String PLAYER_THEME_DRAFT_RATE_PREFIX = "ai:theme:draft:player:";
    private static final DateTimeFormatter RATE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 每日额度按北京时间换日，不随 JVM 默认时区漂移。 */
    private static final java.time.ZoneId QUOTA_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    private java.time.Clock themeDraftClock = java.time.Clock.system(QUOTA_ZONE);

    @Autowired
    private IAiTopicPlanService aiTopicPlanService;

    @Autowired
    private AiGatewayService gateway;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private com.chengyinhub.business.service.ai.AiQuotaRecoveryService quotaRecovery;

    @Override
    public AiMerchantPlanResp merchantPlan(AiMerchantPlanReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return aiTopicPlanService.generate(req, memberId);
    }

    @Override
    public AiThemeDraftResp themeDraft(AiThemeDraftReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        return executeThemeDraft(req, memberId, role, null);
    }

    @Override
    public AiThemeDraftPlan planThemeDraft(Long memberId, String nonce)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        try {
            if (nonce == null || !nonce.matches("[a-f0-9]{32}")) throw rejected(Code.PLAN_INVALID);
            String role = resolvePreparedRole(memberId);
            java.time.LocalDate day = java.time.LocalDate.now(themeDraftClock.withZone(QUOTA_ZONE));
            String originalDay = day.format(RATE_DATE_FMT);
            String pool = "player".equals(role) ? "player-draft" : "gen";
            String reservation = "player".equals(role)
                    ? PLAYER_THEME_DRAFT_RATE_PREFIX + memberId + ":" + originalDay + "|" + nonce
                    : gateway.planQuotaReservation(memberId, "theme_draft", nonce);
            if (reservation == null || !reservation.endsWith("|" + nonce)) throw rejected(Code.PLAN_INVALID);
            String counter = reservation.substring(0, reservation.length() - 33);
            String receipt = ("player".equals(role) ? "ai:player:theme:draft:reservation:" : "ai:gen:reservation:")
                    + counter + ":" + nonce;
            AiThemeDraftPlan plan = new AiThemeDraftPlan(memberId, role, pool, originalDay, counter, nonce,
                    receipt, reservation, themeDraftCreatorConfigurationHash(), gateway.themeDraftConfigurationHash(),
                    day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant());
            validatePreparedIdentity(memberId, plan);
            return plan;
        } catch (AiThemeDraftPlanRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw rejected(Code.CHECK_UNAVAILABLE);
        }
    }

    @Override
    public AiThemeDraftResp themeDraftPrepared(AiThemeDraftReq req, Long memberId, AiThemeDraftPlan plan)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        // This is the only boundary that can assert a zero-effect local rejection.
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw rejected(Code.CHECK_UNAVAILABLE);
            if (req == null) throw rejected(Code.PLAN_INVALID);
            validatePreparedIdentity(memberId, plan);
            if (!plan.role().equals(resolvePreparedRole(memberId))) throw rejected(Code.AUTHORITY_CHANGED);
            if ("player".equals(plan.role()) && (quotaRecovery == null || redisTemplate == null))
                throw rejected(Code.CHECK_UNAVAILABLE);
            if (!plan.creatorConfigHash().equals(themeDraftCreatorConfigurationHash())
                    || !plan.gatewayConfigHash().equals(gateway.themeDraftConfigurationHash()))
                throw rejected(Code.CONFIG_CHANGED);
        } catch (AiThemeDraftPlanRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw rejected(Code.CHECK_UNAVAILABLE);
        }
        return executeThemeDraft(req, memberId, plan.role(), plan);
    }

    private AiThemeDraftResp executeThemeDraft(AiThemeDraftReq req, Long memberId, String role, AiThemeDraftPlan plan)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        PlayerThemeDraftQuotaReservation reservation = null;
        boolean accepted = false;
        boolean inputBlocked = false;
        boolean providerOutcomeUnknown = false;
        JSONObject user = new JSONObject();
        user.put("idea", req.getIdea());
        user.put("productType", req.getProductType());
        if (req.getExistingNodeNames() != null)
        {
            user.put("existingNodeNames", req.getExistingNodeNames());
        }
        String userPrompt = user.toJSONString();
        try
        {
            if ("player".equals(role))
            {
                // 必须在调模型前占位；事后才计数会让第 4 次请求仍消耗一次模型调用。
                reservation = plan == null ? reservePlayerThemeDraftQuota(memberId)
                        : consumePlayerThemeDraftPlan(memberId, plan);
            }
            if(reservation!=null && quotaRecovery!=null && plan==null) quotaRecovery.stage(reservation.receipt,"INPUT_REVIEW_STARTED");
            AiGatewayResult r = plan == null
                    ? gateway.generate(memberId, role, "theme_draft", SYS_THEME_DRAFT, userPrompt)
                    : gateway.generateThemeDraftPrepared(memberId, plan, SYS_THEME_DRAFT, userPrompt);
            AiThemeDraftResp resp = new AiThemeDraftResp();
            resp.setTraceId(r.getTraceId());
            // 任何角色拿到 mock 模型输出都必须失败关闭：mock 草稿不是真实 AI 结果，
            // 放出去会让 club/merchant 也把占位内容当成可用主题。
            if (isMockModel(r))
            {
                throw new AiServiceException("AI 服务暂未就绪,请稍后重试");
            }
            JSONObject json = parseAiJson(r.getText());
            JSONArray planArr = json.getJSONArray("plans");
            if (planArr == null || planArr.isEmpty())
            {
                throw new AiServiceException(PARSE_ERROR_MSG);
            }
            AiThemePlan draft = parseThemePlan(planArr.getJSONObject(0), null);
            if (!isValidThemeDraft(draft)) throw new AiServiceException(PARSE_ERROR_MSG);
            resp.setDraft(draft);
            if (reservation != null) resp.setRemainingQuota(reservation.remaining);
            accepted = true;
            return resp;
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused)
        {
            // A restored process cannot prove what happened after its backup.
            providerOutcomeUnknown = true;
            throw paused;
        }
        catch (AiThemeDraftPlanRejectedException failure)
        {
            // A later player-side check is after reservation: never export NOT_SENT preflight proof.
            if (plan != null && reservation == null && !"player".equals(role)) throw failure;
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 草稿原计划不可执行");
        }
        catch (AiServiceException failure)
        {
            inputBlocked = failure.getReason() == AiServiceException.Reason.INPUT_BLOCKED;
            providerOutcomeUnknown = failure instanceof AiProviderOutcomeUnknownException;
            throw failure;
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            if (plan == null) log.error("[AiCreator] JSON parse failed bizType=theme_draft", je);
            else log.warn("[AiCreator] prepared-json-invalid traceId={} code=INVALID_THEME_DRAFT", plan.nonce());
            throw new AiServiceException(PARSE_ERROR_MSG);
        }
        finally
        {
            // Provider UNKNOWN keeps its original receipt for recovery; input-only failures retain their refund policy.
            if (reservation != null && !providerOutcomeUnknown) {
                if (!accepted && !inputBlocked) releasePlayerThemeDraftQuota(reservation);
                else if(quotaRecovery!=null) {
                    try { quotaRecovery.retain(reservation.receipt,inputBlocked ? "INPUT_BLOCKED" : "VALID_PLAYER_DRAFT"); }
                    catch (RuntimeException outcomeUnavailable) {
                        if (plan == null) throw outcomeUnavailable;
                        if (accepted) throw new com.chengyinhub.business.service.ai.AiProviderResultReceivedException(
                                AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                                "AI 服务暂时不可用，请稍后重试", outcomeUnavailable);
                        // Input rejection already entered quota/review: no local zero-effect proof.
                        throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                                "AI 服务暂时不可用，请稍后重试");
                    }
                }
            }
        }
    }

    @Override
    public AiThemeDraftQuotaResp themeDraftQuota(Long memberId)
    {
        AiThemeDraftQuotaResp resp = new AiThemeDraftQuotaResp();
        if (!"player".equals(resolveRole(memberId)))
        {
            // 这里此前答 limited=false(不限量),但网关照样按 gen 池每日切断
            // ——用户会在毫无预告的情况下撞上「今日AI次数已用完」。
            // 展示口必须报网关真正在执行的那个额度,而不是「本层没有额外限制」。
            resp.setLimited(true);
            resp.setLimit(gateway.dailyQuota("theme_draft"));
            resp.setRemaining(gateway.remainingQuota(memberId, "theme_draft"));
            return resp;
        }
        // 玩家起草只受独立专项额度限制，展示与实际预占、成功回包使用同一池。
        int playerRemaining = Math.max(0, PLAYER_THEME_DRAFT_DAILY_LIMIT - playerThemeDraftUsed(memberId));
        resp.setLimited(true);
        resp.setLimit(PLAYER_THEME_DRAFT_DAILY_LIMIT);
        resp.setRemaining(playerRemaining);
        return resp;
    }

    @Override
    public AiNodeGenResp nodeGenerate(AiNodeGenReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        JSONObject user = new JSONObject();
        user.put("nodeName", req.getNodeName());
        user.put("address", req.getAddress());
        user.put("themeContext", req.getThemeContext());
        user.put("validationMethod", req.getValidationMethod());
        user.put("purpose", req.getPurpose());

        AiGatewayResult r = gateway.generate(memberId, role, "node_generate", SYS_NODE_GENERATE, user.toJSONString());

        AiNodeGenResp resp = new AiNodeGenResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setNode(json.getObject("node", AiNodeContent.class));
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=node_generate traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
        }
        return resp;
    }

    @Override
    public AiTemplateFillResp templateFill(AiTemplateFillReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        JSONObject user = new JSONObject();
        user.put("shopName", req.getShopName());
        user.put("category", req.getCategory());
        user.put("reward", req.getReward());
        user.put("playStyle", req.getPlayStyle());
        user.put("validationMethod", req.getValidationMethod());
        user.put("extraNote", req.getExtraNote());

        AiGatewayResult r = gateway.generate(memberId, role, "template_fill", SYS_TEMPLATE_FILL, user.toJSONString());
        if (isMockModel(r))
        {
            throw new AiServiceException("AI 服务暂未就绪,请稍后重试");
        }

        AiTemplateFillResp resp = new AiTemplateFillResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            AiTemplateContent template = json.getObject("template", AiTemplateContent.class);
            if (!isValidTemplateFill(template)) throw new AiServiceException(PARSE_ERROR_MSG);
            resp.setTemplate(template);
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=template_fill traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
            throw new AiServiceException(PARSE_ERROR_MSG, je);
        }
        return resp;
    }

    @Override
    public AiChapterOverviewResp chapterOverview(AiChapterOverviewReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        JSONObject user = new JSONObject();
        user.put("chapterName", req.getChapterName());
        user.put("idea", req.getIdea());
        user.put("themeContext", req.getThemeContext());

        AiGatewayResult r = gateway.generate(memberId, role, "chapter_overview", SYS_CHAPTER_OVERVIEW,
            user.toJSONString());

        AiChapterOverviewResp resp = new AiChapterOverviewResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setOverview(json.getString("overview"));
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=chapter_overview traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
        }
        return resp;
    }

    @Override
    public AiSafetyPrecheckResp safetyPrecheck(AiSafetyPrecheckReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        // 1. 实际文本内容安全检测
        StringBuilder sb = new StringBuilder();
        append(sb, req.getTitle());
        append(sb, req.getSubtitle());
        append(sb, req.getDescription());
        if (req.getNodes() != null)
        {
            for (AiSafetyPrecheckReq.PrecheckNode n : req.getNodes())
            {
                append(sb, n.getName());
                append(sb, n.getTask());
                append(sb, n.getHint1());
                append(sb, n.getHint2());
            }
        }
        String openid = resolveOpenid(memberId);
        String secMsg = wxContentSecurityService.checkText(sb.toString(), openid);
        boolean secPass = secMsg == null;

        // 2. AI 缺口/风险评估
        JSONObject user = new JSONObject();
        user.put("title", req.getTitle());
        user.put("subtitle", req.getSubtitle());
        user.put("description", req.getDescription());
        if (req.getNodes() != null)
        {
            JSONArray nodes = new JSONArray();
            for (AiSafetyPrecheckReq.PrecheckNode n : req.getNodes())
            {
                JSONObject no = new JSONObject();
                no.put("name", n.getName());
                no.put("task", n.getTask());
                nodes.add(no);
            }
            user.put("nodes", nodes);
        }

        AiGatewayResult r = gateway.generate(memberId, role, "safety_precheck", SYS_SAFETY_PRECHECK, user.toJSONString());

        AiSafetyPrecheckResp resp = new AiSafetyPrecheckResp();
        resp.setTraceId(r.getTraceId());

        List<AiSafetyIssue> issues = new ArrayList<>();
        if (!secPass)
        {
            issues.add(new AiSafetyIssue("error", "content_security", secMsg));
        }
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setPass(secPass && json.getBooleanValue("pass", false));
            JSONArray aiIssues = json.getJSONArray("issues");
            if (aiIssues != null)
            {
                for (int i = 0; i < aiIssues.size(); i++)
                {
                    issues.add(aiIssues.getObject(i, AiSafetyIssue.class));
                }
            }
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=safety_precheck traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
            resp.setPass(false);
            issues.add(new AiSafetyIssue("error", "parse_error", "AI 响应格式异常,请重试"));
        }
        resp.setIssues(issues);
        return resp;
    }

    @Override
    public AiMerchantTaskSuggestResp merchantTaskSuggest(AiMerchantTaskSuggestReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        JSONObject user = new JSONObject();
        user.put("merchantContext", req.getMerchantContext());
        user.put("goal", req.getGoal());

        AiGatewayResult r = gateway.generate(memberId, role, "merchant_task_suggest",
                SYS_MERCHANT_TASK_SUGGEST, user.toJSONString());

        AiMerchantTaskSuggestResp resp = new AiMerchantTaskSuggestResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setSuggestions(json.getList("suggestions", AiMerchantTaskSuggestResp.AiMerchantSuggestion.class));
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=merchant_task_suggest traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
            resp.setParseError(PARSE_ERROR_MSG);
        }
        return resp;
    }

    @Override
    public AiClubDesignResp clubDesign(AiClubDesignReq req, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        JSONObject user = new JSONObject();
        user.put("idea", req.getIdea());
        user.put("clubStyle", req.getClubStyle());
        user.put("targetDurationMin", req.getTargetDurationMin());

        AiGatewayResult r = gateway.generate(memberId, role, "club_design",
                SYS_CLUB_DESIGN, user.toJSONString());

        AiClubDesignResp resp = new AiClubDesignResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setPlan(json.getObject("plan", AiThemePlan.class));
            resp.setMerchantSuggestions(json.getList("merchantSuggestions", String.class));
            resp.setPromoCopy(json.getString("promoCopy"));
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=club_design traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
            resp.setParseError(PARSE_ERROR_MSG);
        }
        return resp;
    }

    @Override
    public com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp merchantInsight(String factsJson, Long memberId)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String role = resolveRole(memberId);
        AiGatewayResult r = gateway.generate(memberId, role, "merchant_insight", SYS_MERCHANT_INSIGHT, factsJson);
        // 失败关闭:mock 输出是假解读,放出去商家会照假建议办活动。
        if (isMockModel(r))
        {
            throw new AiServiceException("AI 服务暂未就绪,请稍后重试");
        }
        com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp resp =
                new com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp();
        resp.setTraceId(r.getTraceId());
        try
        {
            JSONObject json = parseAiJson(r.getText());
            resp.setSummary(json.getString("summary"));
            resp.setOpportunity(json.getString("opportunity"));
            resp.setProblem(json.getString("problem"));
            resp.setAudiences(json.getList("audiences",
                    com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightAudience.class));
            List<com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion> raw =
                    json.getList("suggestions",
                            com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion.class);
            resp.setSuggestions(sanitizeInsightSuggestions(raw));
            resp.setRecommendedTopics(json.getList("recommendedTopics",
                    com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightPick.class));
            resp.setRecommendedPartners(json.getList("recommendedPartners",
                    com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightPick.class));
            if (isBlank(resp.getSummary()))
            {
                throw new AiServiceException(PARSE_ERROR_MSG);
            }
            return resp;
        }
        catch (com.alibaba.fastjson2.JSONException je)
        {
            log.error("[AiCreator] JSON parse failed bizType=merchant_insight traceId={} text={}", r.getTraceId(),
                truncate(r.getText(), 200), je);
            throw new AiServiceException(PARSE_ERROR_MSG);
        }
    }

    /** type 白名单校验 + 截断到 3 条:非法 type 整条丢弃,防模型编造不存在的跳转。 */
    private List<com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion>
            sanitizeInsightSuggestions(List<com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion> raw)
    {
        List<com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion> out = new ArrayList<>();
        if (raw == null) return out;
        for (com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion s : raw)
        {
            if (s == null || isBlank(s.getTitle())) continue;
            if (!INSIGHT_SUGGESTION_TYPES.contains(s.getType()))
            {
                log.info("[AiCreator] merchant_insight dropped suggestion with invalid type={}", s.getType());
                continue;
            }
            out.add(s);
            if (out.size() >= 3) break;
        }
        return out;
    }

    @Override
    public String resolveRoleFor(Long memberId)
    {
        return resolveRole(memberId);
    }

    // ---- helpers ----

    private AiThemePlan parseThemePlan(JSONObject pj, Set<Long> validCandidateIds)
    {
        if (pj == null) return null;
        AiThemePlan plan = new AiThemePlan();
        plan.setTitle(pj.getString("title"));
        plan.setSubtitle(pj.getString("subtitle"));
        plan.setStoryline(pj.getString("storyline"));
        plan.setTags(pj.getList("tags", String.class));
        plan.setEstDurationMin(pj.getInteger("estDurationMin"));
        plan.setFitReason(pj.getString("fitReason"));
        plan.setRisks(pj.getList("risks", String.class));

        List<AiPlanNode> nodes = new ArrayList<>();
        JSONArray nodeArr = pj.getJSONArray("nodes");
        if (nodeArr != null)
        {
            for (int i = 0; i < nodeArr.size(); i++)
            {
                JSONObject nj = nodeArr.getJSONObject(i);
                // 反幻觉:candidateId 必须 ∈ 候选集,越界丢弃(静默)然后去 order 全部丢弃。
                if (validCandidateIds != null)
                {
                    Long cid = nj.getLong("candidateId");
                    if (cid == null || !validCandidateIds.contains(cid))
                    {
                        log.info("[AiCreator] invalid candidateId={}, dropping node", cid);
                        continue;
                    }
                }
                AiPlanNode n = nj.toJavaObject(AiPlanNode.class);
                if (n != null) nodes.add(n);
            }
        }
        plan.setNodes(nodes);
        return plan;
    }

    private String resolveRole(Long memberId)
    {
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return m == null ? "player" : roleService.resolveRole(m);
    }

    private String resolvePreparedRole(Long memberId)
    {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().values()
                .stream().anyMatch(org.mybatis.spring.SqlSessionHolder.class::isInstance))
            throw rejected(Code.CHECK_UNAVAILABLE);
        if (memberId == null || memberId <= 0) throw rejected(Code.AUTHORITY_CHANGED);
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        // Same current-member eligibility as RoleService; missing members never become players.
        if (member == null || !memberId.equals(member.getId()) || !Long.valueOf(1L).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getDelFlag())) throw rejected(Code.AUTHORITY_CHANGED);
        String role = roleService.resolveRole(member);
        if (!java.util.Arrays.asList("player", "club", "merchant").contains(role))
            throw rejected(Code.CHECK_UNAVAILABLE);
        return role;
    }

    private void validatePreparedIdentity(Long memberId, AiThemeDraftPlan plan)
    {
        if (plan == null || memberId == null || memberId <= 0 || !memberId.equals(plan.memberId())
                || !java.util.Arrays.asList("player", "club", "merchant").contains(plan.role())
                || plan.nonce() == null || !plan.nonce().matches("[a-f0-9]{32}")
                || plan.originalDay() == null || !plan.originalDay().matches("[0-9]{8}")
                || plan.creatorConfigHash() == null || !plan.creatorConfigHash().matches("[a-f0-9]{64}")
                || plan.gatewayConfigHash() == null || !plan.gatewayConfigHash().matches("[a-f0-9]{64}")
                || plan.executeBefore() == null) throw rejected(Code.PLAN_INVALID);
        java.time.LocalDate day;
        try { day = java.time.LocalDate.parse(plan.originalDay(), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (java.time.DateTimeException invalid) { throw rejected(Code.PLAN_INVALID); }
        boolean player = "player".equals(plan.role());
        String counter = (player ? PLAYER_THEME_DRAFT_RATE_PREFIX : "ai:gen:limit:gen:")
                + memberId + ":" + plan.originalDay();
        String receipt = (player ? "ai:player:theme:draft:reservation:" : "ai:gen:reservation:")
                + counter + ":" + plan.nonce();
        if (!(player ? "player-draft" : "gen").equals(plan.pool()) || !counter.equals(plan.counterKey())
                || !receipt.equals(plan.receiptKey()) || !(counter + "|" + plan.nonce()).equals(plan.reservation())
                || !plan.executeBefore().isAfter(day.atStartOfDay(QUOTA_ZONE).toInstant())
                || plan.executeBefore().isAfter(day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant()))
            throw rejected(Code.PLAN_INVALID);
        java.time.Instant now = themeDraftClock.instant();
        if (day.isAfter(now.atZone(QUOTA_ZONE).toLocalDate())) throw rejected(Code.PLAN_INVALID);
        if (!now.isBefore(plan.executeBefore())
                || !day.equals(now.atZone(QUOTA_ZONE).toLocalDate())) throw rejected(Code.PLAN_EXPIRED);
    }

    private String themeDraftCreatorConfigurationHash()
    {
        return CanonicalDigest.sha256Hex(JSON.toJSONString(java.util.Arrays.asList(
                "theme-draft.creator.v2", CanonicalDigest.sha256Hex(SYS_THEME_DRAFT), "parse-theme-plan.v1",
                "player-draft", PLAYER_THEME_DRAFT_DAILY_LIMIT, "Asia/Shanghai",
                "valid-or-input-blocked-retain;invalid-refund;provider-unknown-retain.v1",
                CanonicalDigest.sha256Hex(RESERVE_QUOTA_SCRIPT.getScriptAsString()),
                CanonicalDigest.sha256Hex(REFUND_QUOTA_SCRIPT.getScriptAsString()))));
    }

    private static AiThemeDraftPlanRejectedException rejected(Code code)
    {
        return new AiThemeDraftPlanRejectedException(code);
    }

    private boolean isMockModel(AiGatewayResult result)
    {
        String model = result == null ? null : result.getModel();
        return model != null && model.toLowerCase().contains("mock");
    }

    private boolean isValidThemeDraft(AiThemePlan draft)
    {
        if (draft == null || isBlank(draft.getTitle()) || isBlank(draft.getStoryline())
                || draft.getNodes() == null || draft.getNodes().isEmpty())
        {
            return false;
        }
        for (AiPlanNode node : draft.getNodes())
        {
            if (node == null || isBlank(node.getMerchantName())) return false;
        }
        return true;
    }

    private boolean isValidTemplateFill(AiTemplateContent template)
    {
        if (template == null || isBlank(template.getTitle()) || isBlank(template.getDescription())
                || isBlank(template.getRuleInstructions()) || template.getValidationMethod() == null)
        {
            return false;
        }
        int vm = template.getValidationMethod();
        if (vm < 0 || vm > 5) return false;
        if (vm == 1) {
            return !isBlank(template.getQuestionName()) && !isBlank(template.getQuestionAnswer());
        }
        if (vm == 3) {
            return !isBlank(template.getQuestionName()) && !isBlank(template.getOptionA())
                    && !isBlank(template.getOptionB()) && !isBlank(template.getOptionC())
                    && !isBlank(template.getOptionD()) && !isBlank(template.getCorrectAnswer());
        }
        return true;
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RESERVE_QUOTA_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local ttl=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); local capped=tonumber(ARGV[3]);\n" +
                    "if not ttl or ttl<1 or ttl>86400 or ttl~=math.floor(ttl) or not limit or limit<0 then return redis.error_reply('invalid quota arguments') end;\n" +
                    "local prior=redis.call('GET',KEYS[3]);\n" +
                    "if prior=='0' then return -1 end;\n" +
                    "if prior then local sep=string.find(prior,'|',1,true); if not sep then return -1 end; local gen=string.sub(prior,1,sep-1); if redis.call('EXISTS',KEYS[1])==1 and redis.call('GET',KEYS[2])==gen then return tonumber(string.sub(prior,sep+1)) end; return -1 end;\n" +
                    "local raw=redis.call('GET',KEYS[1]);\n" +
                    "if raw and not (raw=='0' or string.match(raw,'^[1-9]%d*$')) then return redis.error_reply('invalid quota counter') end;\n" +
                    "local used=raw and tonumber(raw) or 0;\n" +
                    "if not used or used>9007199254740990 then return redis.error_reply('quota counter out of range') end;\n" +
                    "if capped==1 and used>=limit then redis.call('SET',KEYS[3],'0','EX',172800); return -2 end;\n" +
                    "local gen=redis.call('GET',KEYS[2]); if not raw or not gen then gen=KEYS[3] end;\n" +
                    "local next=redis.call('INCR',KEYS[1]); if next==1 then redis.call('EXPIRE',KEYS[1],ttl) end;\n" +
                    "local life=redis.call('PTTL',KEYS[1]); if life>0 then redis.call('SET',KEYS[2],gen,'PX',life) elseif life==-1 then redis.call('SET',KEYS[2],gen) else redis.call('DEL',KEYS[2]) end;\n" +
                    "redis.call('SET',KEYS[3],gen..'|'..next,'EX',172800); return next;", Long.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> REFUND_QUOTA_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local receipt=redis.call('GET',KEYS[3]); if receipt=='0' then return 0 end;\n" +
                    "if not receipt then redis.call('SET',KEYS[3],'0','EX',172800); return 0 end;\n" +
                    "local sep=string.find(receipt,'|',1,true); if not sep then return redis.error_reply('invalid quota receipt') end;\n" +
                    "local gen=string.sub(receipt,1,sep-1); local raw=redis.call('GET',KEYS[1]); local refunded=0;\n" +
                    "if raw and redis.call('GET',KEYS[2])==gen then\n" +
                    "if not (raw=='0' or string.match(raw,'^[1-9]%d*$')) then return redis.error_reply('invalid quota counter') end;\n" +
                    "local used=tonumber(raw); if not used or used>9007199254740991 then return redis.error_reply('quota counter out of range') end;\n" +
                    "if used>0 then redis.call('DECR',KEYS[1]); refunded=1 end; end;\n" +
                    "redis.call('SET',KEYS[3],'0','EX',172800); return refunded;", Long.class);
    /** 原日、nonce 与代际绑定；未知响应只补偿自己的预占。 */
    private PlayerThemeDraftQuotaReservation reservePlayerThemeDraftQuota(Long memberId)
    {
        LocalDateTime now = LocalDateTime.now(QUOTA_ZONE);
        String key = PLAYER_THEME_DRAFT_RATE_PREFIX + memberId + ":" + now.toLocalDate().format(RATE_DATE_FMT);
        String receipt = "ai:player:theme:draft:reservation:" + key + ":"
                + com.chengyinhub.common.utils.uuid.IdUtils.simpleUUID();
        return consumePlayerThemeDraftQuota(memberId, key, receipt, secondsUntilNextLocalMidnight(now));
    }

    private PlayerThemeDraftQuotaReservation consumePlayerThemeDraftPlan(Long memberId, AiThemeDraftPlan plan)
    {
        // Already validated; these exact values came from the committed original intent.
        // Execution authorization may expire earlier; it must never shorten/reset the daily counter.
        java.time.Instant midnight = java.time.LocalDate.parse(plan.originalDay(), DateTimeFormatter.BASIC_ISO_DATE)
                .plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
        long ttl = java.time.Duration.between(themeDraftClock.instant(), midnight).getSeconds();
        return consumePlayerThemeDraftQuota(memberId, plan.counterKey(), plan.receiptKey(), Math.max(1L, ttl));
    }

    private PlayerThemeDraftQuotaReservation consumePlayerThemeDraftQuota(Long memberId, String key, String receipt, long ttl)
    {
        if(quotaRecovery!=null) quotaRecovery.prepare(memberId,"theme_draft","player-draft",key,receipt);
        PlayerThemeDraftQuotaReservation reservation = new PlayerThemeDraftQuotaReservation(key, receipt, 0);
        Long used;
        try {
            used = redisTemplate.execute(RESERVE_QUOTA_SCRIPT,
                    java.util.Arrays.<Object>asList(key, key + ":generation", receipt),
                    Integer.valueOf((int) ttl),
                    Integer.valueOf(PLAYER_THEME_DRAFT_DAILY_LIMIT), Integer.valueOf(1));
        } catch (RuntimeException unknown) {
            releasePlayerThemeDraftQuota(reservation);
            throw new AiServiceException("AI 服务繁忙,请稍后重试", unknown);
        }
        if (Long.valueOf(-2L).equals(used)) {
            if(quotaRecovery!=null) quotaRecovery.retain(receipt,"RATE_LIMITED");
            throw new AiServiceException("今日简单创作次数已用完(3/天),明天再来吧");
        }
        if (used == null || used < 1) {
            releasePlayerThemeDraftQuota(reservation);
            throw new AiServiceException("AI 服务繁忙,请稍后重试");
        }
        if(quotaRecovery!=null) {
            try { quotaRecovery.reserved(receipt,used); }
            catch(RuntimeException persistenceFailure) { releasePlayerThemeDraftQuota(reservation); throw persistenceFailure; }
        }
        return new PlayerThemeDraftQuotaReservation(key, receipt,
                PLAYER_THEME_DRAFT_DAILY_LIMIT - used.intValue());
    }

    private void releasePlayerThemeDraftQuota(PlayerThemeDraftQuotaReservation reservation)
    {
        try {
            if(quotaRecovery!=null) { quotaRecovery.requireRefund(reservation.receipt,"PLAYER_DRAFT_FAILED"); return; }
            redisTemplate.execute(REFUND_QUOTA_SCRIPT, java.util.Arrays.<Object>asList(
                    reservation.key, reservation.key + ":generation", reservation.receipt));
        } catch (RuntimeException unavailable) {
            log.warn("[AiCreator] player-theme-draft quota compensation unavailable traceId={} code=COMPENSATION_UNAVAILABLE",
                    reservation.receipt.substring(Math.max(0, reservation.receipt.length() - 32)));
        }
    }

    private int playerThemeDraftUsed(Long memberId)
    {
        LocalDateTime now = LocalDateTime.now(QUOTA_ZONE);
        String key = PLAYER_THEME_DRAFT_RATE_PREFIX + memberId + ":" + now.toLocalDate().format(RATE_DATE_FMT);
        try
        {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return 0;
            if (raw instanceof Number) return Math.max(0, ((Number) raw).intValue());
            return Math.max(0, Integer.parseInt(String.valueOf(raw)));
        }
        catch (RuntimeException e)
        {
            log.warn("[AiCreator] player-theme-draft quota read failed memberId={}", memberId, e);
            throw new AiServiceException("AI 服务繁忙,请稍后重试");
        }
    }

    private static final class PlayerThemeDraftQuotaReservation
    {
        private final String key;
        private final String receipt;
        private final int remaining;

        private PlayerThemeDraftQuotaReservation(String key, String receipt, int remaining)
        {
            this.key = key;
            this.receipt = receipt;
            this.remaining = remaining;
        }
    }

    private long secondsUntilNextLocalMidnight(LocalDateTime now)
    {
        return Math.max(1L, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds());
    }

    private String resolveOpenid(Long memberId)
    {
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return (m == null || m.getWxOpenId() == null) ? "" : m.getWxOpenId();
    }

    private void append(StringBuilder sb, String s)
    {
        if (s != null && !s.trim().isEmpty())
        {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.trim());
        }
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 剥离 LLM 可能包上的 markdown 代码块围栏(```json ... ``` 或 ``` ... ```)。
     * prompt 里只是「请求」模型不要包,模型仍可能包;不剥离则 fastjson2 必抛 JSONException。
     * 无围栏时原样(trim 后)返回;null 返回 null。
     */
    private static String stripJsonFence(String text)
    {
        if (text == null) return null;
        String s = text.trim();
        if (!s.startsWith("```")) return s;
        // 跳过首行围栏(首行可能带语言标注,如 ```json)
        int nl = s.indexOf('\n');
        if (nl < 0) return s;
        s = s.substring(nl + 1);
        int close = s.lastIndexOf("```");
        if (close >= 0) s = s.substring(0, close);
        return s.trim();
    }

    /**
     * 统一的 AI 响应 JSON 解析:先剥围栏再解析;text 为 null/空 或解析结果为 null 时抛
     * JSONException,让调用方复用既有的 catch 降级分支(否则后续 json.getXxx() 会抛 NPE 冒泡 500)。
     */
    private static JSONObject parseAiJson(String text)
    {
        JSONObject json = JSON.parseObject(stripJsonFence(text));
        if (json == null)
        {
            throw new com.alibaba.fastjson2.JSONException("empty ai response");
        }
        return json;
    }

    // ---- 内嵌 prompt 模板(输出 JSON 模式, [BIZ:xxx] 标记供 MockLlmClient 路由) ----

    private static final String SYS_THEME_DRAFT =
        "[BIZ:theme_draft]\n" +
        "你是城市定向主题草稿生成器。根据一句话想法和 productType 生成1套主题骨架(无坐标,留给用户在地图补点)。\n" +
        "只生成标题、故事线、节点名称与节点文字说明；不要生成玩法规则、题目或答案。\n" +
        "输出严格JSON(不要markdown代码块),同 merchant_plan 的 plans 结构,1 条 plan;nodes 字段 longitude/latitude/candidateId 留空。";

    private static final String SYS_NODE_GENERATE =
        "[BIZ:node_generate]\n" +
        "你是城市定向节点的任务/谜题设计师。根据节点名、地址、验证方式生成可玩内容。\n" +
        "输出严格JSON:\n" +
        "{\"node\":{\"validationMethod\":1,\"ruleInstructions\":\"\",\"questionName\":\"\",\"questionAnswer\":\"\"," +
        "\"optionA\":\"\",\"optionB\":\"\",\"optionC\":\"\",\"optionD\":\"\",\"correctAnswer\":\"\"," +
        "\"hint1\":\"\",\"hint2\":\"\",\"answerReveal\":\"\",\"feedbackText\":\"\",\"storyText\":\"\"}}";

    private static final String SYS_TEMPLATE_FILL =
        "[BIZ:template_fill]\n" +
        "你是城瘾商家玩法模板设计师。根据店名/品类/奖励(可含参考玩法与商家补充)生成玩法模板的核心内容草稿。\n" +
        "要求:标题≤20字像关卡名;描述≤30字直给爽点;规则≤3步;剧情连接真实店内场景;\n" +
        "答案必须是商家在店内可布置/可验证的;coverPrompt 是给生图/摄影的画面描述,不是URL。\n" +
        "输出严格JSON:\n" +
        "{\"template\":{\"title\":\"\",\"description\":\"\",\"validationMethod\":1,\"ruleInstructions\":\"\"," +
        "\"questionName\":\"\",\"questionAnswer\":\"\",\"optionA\":\"\",\"optionB\":\"\",\"optionC\":\"\",\"optionD\":\"\"," +
        "\"correctAnswer\":\"\",\"hint1\":\"\",\"hint2\":\"\",\"answerReveal\":\"\",\"feedbackText\":\"\"," +
        "\"storyText\":\"\",\"coverPrompt\":\"\",\"medalName\":\"\"}}";

    private static final String SYS_CHAPTER_OVERVIEW =
        "[BIZ:chapter_overview]\n" +
        "你是城市定向主题的章节概述撰写助手。根据章节名、主题背景与用户想法,写一段该章节的整体语境概述。\n" +
        "规则:1~3句、120字内、第二人称代入感;只写这一段概述,不编造商家名/坐标/具体地址。\n" +
        "输出严格JSON(不要markdown代码块):\n" +
        "{\"overview\":\"\"}";

    private static final String SYS_SAFETY_PRECHECK =
        "[BIZ:safety_precheck]\n" +
        "你是主题发布前质检助手。检查标题/简介/节点的缺口与风险(缺封面/缺节点/营业时间冲突/疑似敏感)。\n" +
        "输出严格JSON:\n" +
        "{\"pass\":true/false,\"issues\":[{\"level\":\"error|warn|info\",\"type\":\"missing_title|missing_nodes|missing_task|missing_cover|timeline_conflict|suspicious\",\"message\":\"\"}]}";

    private static final String SYS_MERCHANT_TASK_SUGGEST =
        "[BIZ:merchant_task_suggest]\n" +
        "你是城市定向商家的任务顾问。根据商户背景和目标,为该商家推荐适合参与的主题类型、可担任的任务角色、任务创意和合作话术。\n" +
        "规则: 不编造具体商家距离/库存/价格; 只做创意策划与建议。\n" +
        "输出严格JSON(不要markdown代码块):\n" +
        "{\"suggestions\":[{\"title\":\"适合参与的主题类型\",\"themeFit\":\"为什么适合\",\"taskRole\":\"在该主题中的角色\"," +
        "\"taskIdea\":\"任务创意\",\"rewardIdea\":\"奖励建议\",\"coopScript\":\"给俱乐部的合作话术\"}]}";

    private static final String SYS_MERCHANT_INSIGHT =
        "[BIZ:merchant_insight]\n" +
        "你是城市探索平台的商家经营参谋。输入是某商家店铺的聚合统计事实包(店铺档案/归因/打卡/核销/客群/供给),没有任何个人明细。\n" +
        "profile 是商家自填档案(capacity=可容纳人数, suitActivityTypes=适合的活动类型, demand=合作诉求)。\n" +
        "candidates 是真实候选池:recruitingTopics=平台招商中的主题[{id,name}], nearbyMerchants=附近商家[{id,name,category}]。\n" +
        "从候选池各挑最多 3 个输出 recommendedTopics/recommendedPartners,id 必须原样取自候选池(越界会被丢弃),reason 结合档案与客群一句话;候选为空则输出空数组,不许编造。\n" +
        "规则: 只能基于给定数据,不得编造具体数字; 样本量小时只给方向性建议,不下统计结论。\n" +
        "summary 必须是一句翻译过的转化事实(例:「30 天里 18 位玩家打卡,12 位核销——每 3 个到店 2 个消费」),不许写「经营状况良好」这类报告腔;60字内。\n" +
        "opportunity=一条最值得抓的经营机会,problem=一条最该解决的问题,各一句话、引用具体数字。\n" +
        "suggestions 给 2~3 条,type 只能取 topic_coop(承接主题合作)/decor(完善店铺装修)/content(发布内容) 三选一;每条 basis 必须引用事实包里的具体数字。\n" +
        "输出严格JSON(不要markdown代码块):\n" +
        "{\"summary\":\"\",\"opportunity\":\"\",\"problem\":\"\",\"audiences\":[{\"label\":\"人群标签\",\"reason\":\"依据\"}]," +
        "\"recommendedTopics\":[{\"id\":0,\"reason\":\"\"}],\"recommendedPartners\":[{\"id\":0,\"reason\":\"\"}]," +
        "\"suggestions\":[{\"title\":\"\",\"type\":\"topic_coop|decor|content\",\"timeSlot\":\"建议时段\"," +
        "\"audience\":\"面向人群\",\"reason\":\"理由\",\"basis\":\"依据的具体数字\"}]}";

    private static final String SYS_CLUB_DESIGN =
        "[BIZ:club_design]\n" +
        "你是城市定向俱乐部的活动策划助手。根据想法和俱乐部风格,输出一套完整的主题方案、推荐商家类型和宣传文案。\n" +
        "输出严格JSON(不要markdown代码块):\n" +
        "{\"plan\":{\"title\":\"\",\"subtitle\":\"\",\"storyline\":\"\",\"tags\":[],\"estDurationMin\":0,\"fitReason\":\"\"," +
        "\"nodes\":[{\"order\":1,\"merchantName\":\"\",\"roleText\":\"\",\"task\":\"\",\"rewardSuggest\":\"\"}]}," +
        "\"merchantSuggestions\":[\"推荐商家类型1\",\"推荐商家类型2\"],\"promoCopy\":\"社群招募文案\"}";
}
