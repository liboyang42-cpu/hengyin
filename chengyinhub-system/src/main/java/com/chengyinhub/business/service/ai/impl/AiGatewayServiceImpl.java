package com.chengyinhub.business.service.ai.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiSceneRouter;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.business.service.ai.AiThemeDraftPlan;
import com.chengyinhub.business.service.ai.AiThemeDraftPlanRejectedException;
import com.chengyinhub.business.service.ai.AiThemeDraftPlanRejectedException.Code;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import com.chengyinhub.common.utils.uuid.IdUtils;

/**
 * AI 调用网关实现:频控 → 入参内容安全 → 选模型 → 调模型 → 出参内容安全 → 落日志。
 *
 * @author chengyinhub
 */
@Service
public class AiGatewayServiceImpl implements AiGatewayService
{
    private static final Logger log = LoggerFactory.getLogger(AiGatewayServiceImpl.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 每日额度按北京时间换日，不随 JVM 默认时区漂移。 */
    static final java.time.ZoneId QUOTA_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    private static final String RATE_KEY_PREFIX = "ai:gen:limit:";
    private static final int BRIEF_MAX = 500;
    private static final int REVIEW_CHUNK_MAX = 2000;
    private static final int REVIEW_OVERLAP = 100;
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


    /** 频控分组:NPC 聊天(独立额度) */
    private static final String RATE_GROUP_NPC = "npc";
    /** 频控分组:创作类(所有创作 bizType 共用一个额度池,与分池前等价) */
    private static final String RATE_GROUP_GEN = "gen";
    /** 频控分组:拍照审核(独立额度)—— 一次玩家拍照不该吃掉创作额度 */
    private static final String RATE_GROUP_PHOTO = "photo";

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private com.chengyinhub.business.service.ai.AiQuotaRecoveryService quotaRecovery;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private AiGenerationLogMapper aiGenerationLogMapper;

    @Autowired
    private AiModelConfig aiModelConfig;

    @Autowired
    private AiSceneRouter sceneRouter;

    @Autowired(required = false)
    private Map<String, LlmClient> llmClientsByBeanName;

    @Autowired
    private AiProviderProperties providerProps;

    @Autowired
    private List<LlmClient> llmClients;

    /**
     * 单用户每日「拍照审核」上限(photo 独立池)。
     * 一局通常 1–3 个拍照节点 × 每节点最多 maxTries(≤10) 次提交 ⇒ 30 = 极端一局 + 余量;
     * 太大等于不设防,太小会让正常玩家拍到一半静默降级。
     *
     * <p>注:AiModelConfig 在 chengyinhub-common,本轮改动半径只到 chengyinhub-system,
     * 所以这个 ai.model.* 键就近读在网关;若之后放开 common,应挪回 AiModelConfig 与 gen/npc 并列。
     */
    @Value("${ai.model.photoDailyLimit:30}")
    private int photoDailyLimit;

    private volatile Map<String, LlmClient> clientMap;
    private java.time.Clock themeDraftClock = java.time.Clock.system(QUOTA_ZONE);

    @Override
    public String themeDraftConfigurationHash()
    {
        return themeDraftSnapshot().hash();
    }

    @Override
    public AiGatewayResult generateThemeDraftPrepared(Long memberId, AiThemeDraftPlan plan,
            String systemPrompt, String userPrompt)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        Code invalid = themeDraftPlanProblem(memberId, plan);
        if (invalid != null) throw preparedRejection(plan, invalid);
        ThemeDraftSnapshot snapshot;
        String openid;
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw preparedRejection(plan, Code.CHECK_UNAVAILABLE);
            if (org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().values()
                    .stream().anyMatch(org.mybatis.spring.SqlSessionHolder.class::isInstance))
                throw preparedRejection(plan, Code.CHECK_UNAVAILABLE);
            if (quotaRecovery == null || redisTemplate == null)
                throw preparedRejection(plan, Code.CHECK_UNAVAILABLE);
            snapshot = themeDraftSnapshot();
            if (!plan.gatewayConfigHash().equals(snapshot.hash()))
                throw preparedRejection(plan, Code.CONFIG_CHANGED);
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            if (member == null || !memberId.equals(member.getId()) || !Long.valueOf(1L).equals(member.getStatus())
                    || !Integer.valueOf(0).equals(member.getDelFlag()))
                throw preparedRejection(plan, Code.AUTHORITY_CHANGED);
            // RoleService's canonical subject is member.role; compare only, never choose another pool.
            if (!java.util.Arrays.asList("player", "club", "merchant").contains(member.getRole()))
                throw preparedRejection(plan, Code.CHECK_UNAVAILABLE);
            if (!plan.role().equals(member.getRole())) throw preparedRejection(plan, Code.AUTHORITY_CHANGED);
            openid = member.getWxOpenId() == null ? "" : member.getWxOpenId();
        } catch (AiThemeDraftPlanRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            // The player's Creator has already reserved its separate pool. No zero-effect claim here.
            throw preparedRejection(plan, Code.CHECK_UNAVAILABLE);
        }
        String reservation = null;
        if (!"player".equals(plan.role())) {
            try {
                reservation = consumePlannedQuota(memberId, "theme_draft", plan.reservation(), snapshot.dailyLimit());
            } catch (AiThemeDraftPlanRejectedException lateFailure) {
                throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                        "AI 草稿原计划执行失败");
            }
        }
        try {
            return generateReserved(memberId, plan.role(), "theme_draft", systemPrompt, userPrompt,
                    reservation, plan.nonce(), snapshot, plan, openid, false);
        } catch (AiThemeDraftPlanRejectedException lateFailure) {
            // Quota/review may already have effects; only this local type loses preflight authority.
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 草稿原计划执行失败");
        }
    }

    private RuntimeException preparedRejection(AiThemeDraftPlan plan, Code code)
    {
        if (plan != null && "player".equals(plan.role()))
            return new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, "AI 草稿原计划不可执行");
        return new AiThemeDraftPlanRejectedException(code);
    }

    private Code themeDraftPlanProblem(Long memberId, AiThemeDraftPlan plan)
    {
        if (plan == null || memberId == null || memberId <= 0 || !memberId.equals(plan.memberId())
                || !java.util.Arrays.asList("player", "club", "merchant").contains(plan.role())
                || plan.nonce() == null || !plan.nonce().matches("[a-f0-9]{32}")
                || plan.originalDay() == null || !plan.originalDay().matches("[0-9]{8}")
                || plan.creatorConfigHash() == null || !plan.creatorConfigHash().matches("[a-f0-9]{64}")
                || plan.gatewayConfigHash() == null || !plan.gatewayConfigHash().matches("[a-f0-9]{64}")
                || plan.executeBefore() == null) return Code.PLAN_INVALID;
        java.time.LocalDate day;
        try { day = java.time.LocalDate.parse(plan.originalDay(), java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
        catch (java.time.DateTimeException invalid) { return Code.PLAN_INVALID; }
        boolean player = "player".equals(plan.role());
        String counter = (player ? "ai:theme:draft:player:" : "ai:gen:limit:gen:")
                + memberId + ":" + plan.originalDay();
        String receipt = (player ? "ai:player:theme:draft:reservation:" : "ai:gen:reservation:")
                + counter + ":" + plan.nonce();
        if (!(player ? "player-draft" : "gen").equals(plan.pool()) || !counter.equals(plan.counterKey())
                || !receipt.equals(plan.receiptKey()) || !(counter + "|" + plan.nonce()).equals(plan.reservation())
                || !plan.executeBefore().isAfter(day.atStartOfDay(QUOTA_ZONE).toInstant())
                || plan.executeBefore().isAfter(day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant()))
            return Code.PLAN_INVALID;
        java.time.Instant now = themeDraftClock.instant();
        if (day.isAfter(now.atZone(QUOTA_ZONE).toLocalDate())) return Code.PLAN_INVALID;
        if (!now.isBefore(plan.executeBefore()) || !day.equals(now.atZone(QUOTA_ZONE).toLocalDate()))
            return Code.PLAN_EXPIRED;
        return null;
    }

    /** Capture once; execution never re-resolves the route, client, model or quota limit. */
    private ThemeDraftSnapshot themeDraftSnapshot()
    {
        String route = sceneRouter == null ? aiModelConfig.getProvider() : sceneRouter.resolve("theme_draft");
        if (route == null || !route.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalStateException("Invalid local model identity");
        if (sceneRouter != null && sceneRouter.isCacheOnly("theme_draft"))
            throw new IllegalStateException("Theme draft needs a model");
        LlmClient client = sceneRouter == null ? resolveClient(route) : selectRoutedClient(route);
        String clientName = client.name();
        if (clientName == null || !clientName.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalStateException("Invalid local model identity");
        String model = "client:" + clientName;
        if (client instanceof com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient) {
            com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient configured =
                    (com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient) client;
            configured.requireReady();
            model = configured.configuredModel();
        }
        int limit = aiModelConfig.getDailyLimit();
        if (limit < 0 || model == null || model.isBlank()) throw new IllegalStateException("Invalid local model configuration");
        // Only allowlisted non-secret assembly fields. Never serialize config/props or hash an API key.
        String base = aiModelConfig.getBaseUrl();
        if (base != null && !base.isBlank()) {
            java.net.URI uri = java.net.URI.create(base);
            if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null)
                throw new IllegalStateException("Invalid local endpoint configuration");
        }
        String hash = CanonicalDigest.sha256Hex(com.alibaba.fastjson2.JSON.toJSONString(java.util.Arrays.asList(
                "theme-draft.gateway.v2", sceneRouter == null ? "single-provider" : "scene-router", route,
                client.getClass().getName(), clientName, model, base, aiModelConfig.isEnabled(),
                aiModelConfig.getTimeoutMs(), limit, "gen", "Asia/Shanghai", REVIEW_CHUNK_MAX, REVIEW_OVERLAP,
                "typed-not-sent-refund;provider-unknown-retain;received-retain.v1",
                CanonicalDigest.sha256Hex(RESERVE_QUOTA_SCRIPT.getScriptAsString()),
                CanonicalDigest.sha256Hex(REFUND_QUOTA_SCRIPT.getScriptAsString()))));
        return new ThemeDraftSnapshot(client, clientName, limit, hash);
    }

    private record ThemeDraftSnapshot(LlmClient client, String clientName, int dailyLimit, String hash) { }

    private void stageGenerationQuota(Long memberId, String bizType, String reservation, String stage,
            AiThemeDraftPlan plan)
    {
        if (plan != null && "player".equals(plan.role())) {
            if (quotaRecovery != null) quotaRecovery.stage(plan.receiptKey(), stage);
        } else stageQuota(memberId, bizType, reservation, stage);
    }

    @Override
    public AiGatewayResult generate(Long memberId, String role, String bizType,
                                     String systemPrompt, String userPrompt)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        // 玩家草稿由 Creator 的三次独立池计费；其他场景绑定本次原日预占。
        boolean playerThemeDraft = "player".equals(role) && "theme_draft".equals(bizType);
        String reservation = playerThemeDraft ? null : consumeQuota(memberId, bizType);
        // 0. 全链路 traceId:必须先于任何 saveLog 生成,否则 input-blocked / cache-only
        //    两条分支落库的 trace_id 与返给前端的对不上(审计无法 join)。
        //    D-08:traceId 即本次预占 nonce,原日收据/台账 nonce 唯一键保证每个 traceId 至多退一次。
        String traceId = traceIdOf(reservation);
        return generateReserved(memberId, role, bizType, systemPrompt, userPrompt, reservation, traceId, null, null, null, false);
    }

    @Override
    public AiGatewayResult generateNpcAsrHandoff(com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff original,
            String systemPrompt,String userPrompt) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (quotaRecovery==null) throw new IllegalStateException("NPC ASR quota persistence unavailable");
        quotaRecovery.requireNpcAsrHandoff(original);
        return generateReserved(original.memberId(), "player", "npc_shop_chat", systemPrompt, userPrompt,
                original.reservation(), traceIdOf(original.reservation()), null, null, null, true);
    }

    @Override
    public void refundNpcAsrNotSent(Long memberId,String requestId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (quotaRecovery==null) throw new IllegalStateException("NPC ASR quota persistence unavailable");
        quotaRecovery.refundNpcAsrNotSent(memberId,requestId);
    }

    private AiGatewayResult generateReserved(Long memberId, String role, String bizType,
            String systemPrompt, String userPrompt, String reservation, String traceId,
            ThemeDraftSnapshot snapshot, AiThemeDraftPlan preparedPlan, String preparedOpenid, boolean asrHandoff)
    {
        boolean playerThemeDraft = "player".equals(role) && "theme_draft".equals(bizType);
        boolean prepared = snapshot != null;
        String openid;
        String secIn;
        try {
            openid = prepared ? preparedOpenid : resolveOpenid(memberId);
            if (!asrHandoff) stageGenerationQuota(memberId,bizType,reservation,"INPUT_REVIEW_STARTED",preparedPlan);
            // 两段都可能含业务上下文；审核与 provider 收到的文本保持完整，不截掉后缀。
            String assembledInput = (systemPrompt == null ? "" : systemPrompt) + "\n"
                    + (userPrompt == null ? "" : userPrompt);
            secIn = checkTextFully(assembledInput, openid);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unavailable) {
            if (!playerThemeDraft && !asrHandoff) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "内容审核暂时不可用，请稍后重试", unavailable);
        }
        if (secIn != null) {
            if (IWxContentSecurityService.isDetectionUnavailable(secIn)) {
                if (!playerThemeDraft && !asrHandoff) refundQuota(memberId, bizType, reservation);
                saveGenerationLog(prepared, traceId, memberId, role, bizType, null, userPrompt, secIn, 0, 0, 0, 0);
                throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, secIn);
            }
            if (!asrHandoff) retainQuota(memberId,bizType,reservation,"INPUT_REJECTED");
            saveGenerationLog(prepared, traceId, memberId, role, bizType, null, userPrompt, secIn,
                    AiGenerationLog.SAFETY_INPUT_BLOCKED, 0, 0, 0);
            // 与 master #322 统一：微信审核拒绝（含人工复核/未知拒绝）一律按入参被拦截返回，小程序给出同一条“请修改内容”提示。
            throw new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, secIn);
        }

        // 3. 选模型(场景路由)
        LlmClient client;
        String modelName;
        try
        {
            client = prepared ? snapshot.client() : selectClient(bizType);
            // This local check proves no HTTP was sent; provider exceptions cannot prove that.
            if (client instanceof com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient)
            {
                ((com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient) client).requireReady();
            }
            modelName = prepared ? snapshot.clientName() : client == null ? null : client.name();
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException e)
        {
            // 「无可用的 AI 模型」= 配置错,纯我们的锅,用户没拿到任何东西 → 退额度。
            // ⚠️ catch RuntimeException 而非 AiServiceException:选模型/场景路由里的
            //    NPE、IllegalStateException 同样是我们的锅,窄 catch 会让它们带着已扣的额度跑掉。
            if (!playerThemeDraft && !asrHandoff) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务配置缺失,请联系管理员(traceId=" + traceId + ")", e);
        }
        if (client == null)
        {
            // cache-only: npc_event 不调模型,返回空结果
            saveGenerationLog(prepared, traceId, memberId, role, bizType, "cache", userPrompt, "", 0, 0, 0, 0);
            if (!asrHandoff) retainQuota(memberId,bizType,reservation,"CACHE_RESULT");
            return new AiGatewayResult(traceId, "[]", "cache", 0);
        }
        long t0 = System.currentTimeMillis();

        String modelText;
        try {
            if (!asrHandoff) stageGenerationQuota(memberId,bizType,reservation,"MODEL_STARTED",preparedPlan);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException stageUnavailable) {
            // chat has not been invoked; preserve the original unsent compensation path.
            if (!playerThemeDraft && !asrHandoff) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务暂时不可用，请稍后重试", stageUnavailable);
        }
        try
        {
            modelText = client.chat(systemPrompt, userPrompt);
            if (modelText == null || modelText.trim().isEmpty())
            {
                throw new IllegalStateException("AI 模型返回为空");
            }
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (com.chengyinhub.business.service.ai.AiProviderNotSentException notSent)
        {
            // Only the dedicated transport proof can compensate after MODEL_STARTED.
            // The player's separate draft reservation remains owned by Creator.
            if (!playerThemeDraft && !asrHandoff) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务暂时不可用，请稍后重试", notSent);
        }
        catch (RuntimeException e)
        {
            if (prepared) log.error("[AiGateway] prepared-model-error traceId={} provider={} code=PROVIDER_OUTCOME_UNKNOWN",
                    traceId, modelName);
            else log.error("[AiGateway] model-error memberId={} provider={}", memberId, modelName, e);
            int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
            saveGenerationLog(prepared, traceId, memberId, role, bizType, modelName, userPrompt, prepared ? "PROVIDER_OUTCOME_UNKNOWN" : "ERROR:" + truncate(e.getMessage(), 100),
                    0, (int) (System.currentTimeMillis() - t0), tokenIn, null);
            // MODEL_STARTED preserves the original receipt for the existing recovery worker.
            // A timeout/empty or malformed response may already have incurred provider usage.
            // Never refund or resend merely because no answer reached this service.
            throw new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException(
                "AI 请求结果待确认，请保留原请求(traceId=" + traceId + ")", e);
        }

        int latencyMs = (int) (System.currentTimeMillis() - t0);
        int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
        int tokenOut = estimateSingle(modelText);

        // 4. 出参内容安全
        String secOut;
        try { secOut = checkTextFully(modelText, openid); }
        catch(RuntimeException unknownOutput) {
            if (!asrHandoff) retainQuotaAfterResult(memberId,bizType,reservation,"OUTPUT_REVIEW_UNKNOWN");
            throw new com.chengyinhub.business.service.ai.AiProviderResultReceivedException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "内容审核暂时不可用，请稍后重试", unknownOutput);
        }
        if (secOut != null)
        {
            if (IWxContentSecurityService.isDetectionUnavailable(secOut))
            {
                if (!asrHandoff) retainQuotaAfterResult(memberId,bizType,reservation,"OUTPUT_REVIEW_UNKNOWN");
                saveGenerationLog(prepared, traceId, memberId, role, bizType, modelName, userPrompt, secOut,
                        0, latencyMs, tokenIn, tokenOut);
                throw new com.chengyinhub.business.service.ai.AiProviderResultReceivedException(
                        AiServiceException.Reason.UPSTREAM_UNAVAILABLE, secOut);
            }
            log.info("[AiGateway] output-blocked memberId={} bizType={}", memberId, bizType);
            saveGenerationLog(prepared, traceId, memberId, role, bizType, modelName, userPrompt, modelText,
                    AiGenerationLog.SAFETY_OUTPUT_BLOCKED, latencyMs, tokenIn, tokenOut);
            if (!asrHandoff) retainQuotaAfterResult(memberId,bizType,reservation,"OUTPUT_REJECTED");
            throw new com.chengyinhub.business.service.ai.AiProviderResultReceivedException(
                    AiServiceException.Reason.OUTPUT_BLOCKED, "生成内容未通过安全检测,请调整后重试");
        }

        // 5. 落日志
        saveGenerationLog(prepared, traceId, memberId, role, bizType, modelName, userPrompt, modelText, 0, latencyMs, tokenIn, tokenOut);

        if (!asrHandoff) retainQuotaAfterResult(memberId,bizType,reservation,"SUCCESS");
        return new AiGatewayResult(traceId, modelText, modelName, 0);
    }

    /**
     * 频控分组:决定这次调用记在哪个额度池上。
     *
     * ⚠️ 刻意**按组而不是按 bizType** 分池:若每个 bizType 各自一池,
     *    创作类 7 个 bizType 各 20 次 = 单用户实际 140 次/天,等于把额度悄悄放大 7 倍。
     *    这里只切出 npc 一组,其余创作类**共用一池**,与分池前的行为完全等价。
     *
     * @param bizType 场景
     * @return "npc"(NPC 聊天)/ "gen"(创作类,默认)
     */
    @Override
    public int remainingQuota(Long memberId, String bizType)
    {
        if (memberId == null)
        {
            return 0;
        }
        String rateGroup = rateGroupOf(bizType);
        int limit = dailyLimitOf(rateGroup);
        String key = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + LocalDate.now(QUOTA_ZONE).format(DATE_FMT);
        Object raw;
        try
        {
            raw = redisTemplate.opsForValue().get(key);
        }
        catch (Exception e)
        {
            // Redis 读不到时报满额而不是报 0:这是展示口,报 0 会把能用的功能显示成不可用。
            log.warn("[AiGateway] quota-read-failed memberId={} bizType={}", memberId, bizType, e);
            return limit;
        }
        long used = 0;
        if (raw instanceof Number)
        {
            used = ((Number) raw).longValue();
        }
        else if (raw != null)
        {
            try
            {
                used = Long.parseLong(String.valueOf(raw).trim());
            }
            catch (NumberFormatException ignored)
            {
                used = 0;
            }
        }
        return (int) Math.max(0L, (long) limit - Math.max(0L, used));
    }

    @Override
    public int dailyQuota(String bizType)
    {
        return dailyLimitOf(rateGroupOf(bizType));
    }

    /**
     * 非文本调用(拍照视觉审核)的计次:池子由 bizType 决定(photo_check 走独立 photo 池),
     * 键名/上限/分池规则与 generate 同源；未知供应商结果不得按“没有结论”退额。
     */
    @Override
    public String consumeQuota(Long memberId, String bizType)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return consumePlannedQuota(memberId, bizType, planQuotaReservation(memberId, bizType, IdUtils.simpleUUID()));
    }

    @Override
    public String planQuotaReservation(Long memberId, String bizType, String nonce)
    {
        if (memberId == null || memberId <= 0 || nonce == null || !nonce.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid original quota identity");
        String group = rateGroupOf(bizType);
        String key = RATE_KEY_PREFIX + group + ":" + memberId + ":" + LocalDate.now(QUOTA_ZONE).format(DATE_FMT);
        return key + "|" + nonce;
    }

    @Override
    public String consumePlannedQuota(Long memberId, String bizType, String reservation)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (memberId == null || memberId <= 0 || receiptOf(memberId, bizType, reservation) == null)
            throw new IllegalArgumentException("Invalid original quota reservation");
        return consumePlannedQuota(memberId, bizType, reservation, dailyLimitOf(rateGroupOf(bizType)));
    }

    private String consumePlannedQuota(Long memberId, String bizType, String reservation, int limit)
    {
        if (memberId == null || memberId <= 0 || receiptOf(memberId, bizType, reservation) == null)
            throw new IllegalArgumentException("Invalid original quota reservation");
        int split = reservation.lastIndexOf('|');
        String key = reservation.substring(0, split);
        String nonce = reservation.substring(split + 1);
        String day = key.substring(key.length() - 8);
        LocalDate.parse(day, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String group = rateGroupOf(bizType);
        String receipt = "ai:gen:reservation:" + key + ":" + nonce;
        if(quotaRecovery!=null) quotaRecovery.prepare(memberId,bizType,group,key,receipt);
        Long used;
        try {
            used = redisTemplate.execute(RESERVE_QUOTA_SCRIPT,
                    java.util.Arrays.<Object>asList(key, key + ":generation", receipt),
                    Integer.valueOf(86400), Integer.valueOf(limit), Integer.valueOf(0));
        } catch (RuntimeException unknown) {
            cancelQuota(key, receipt);
            throw unknown;
        }
        if (used == null || used < 1) {
            cancelQuota(key, receipt);
            // 不可确认预占是基础服务故障，不能被 Photo 当作真实超限。
            throw new IllegalStateException("AI quota reservation unavailable");
        }
        if(quotaRecovery!=null) {
            try { quotaRecovery.reserved(receipt,used); }
            catch(RuntimeException persistenceFailure) { cancelQuota(key,receipt); throw persistenceFailure; }
        }
        if (used > limit) {
            if(quotaRecovery!=null) quotaRecovery.retain(receipt,"RATE_LIMITED");
            throw new AiServiceException(AiServiceException.Reason.RATE_LIMITED,
                    "今日AI次数已用完(" + limit + "/天),明天再来吧");
        }
        return reservation;
    }

    @Override
    public void refundQuota(Long memberId, String bizType, String reservation)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (reservation == null) return;
        int split = reservation.lastIndexOf('|');
        if (split <= 0) return;
        String key = reservation.substring(0, split);
        String nonce = reservation.substring(split + 1);
        String prefix = RATE_KEY_PREFIX + rateGroupOf(bizType) + ":" + memberId + ":";
        if (!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[0-9]{8}")
                || !nonce.matches("[a-fA-F0-9]{32}")) return;
        cancelQuota(key, "ai:gen:reservation:" + key + ":" + nonce);
    }

    @Override
    public void stageQuota(Long memberId,String bizType,String reservation,String stage) {
        String receipt=receiptOf(memberId,bizType,reservation);
        if ("ASR_STARTED".equals(stage) && (quotaRecovery==null || receipt==null))
            throw new IllegalStateException("NPC ASR quota persistence unavailable");
        if(quotaRecovery!=null && receipt!=null) quotaRecovery.stage(receipt,stage);
    }

    @Override
    public void retainQuota(Long memberId,String bizType,String reservation,String reason) {
        String receipt=receiptOf(memberId,bizType,reservation);
        if(quotaRecovery!=null && receipt!=null) quotaRecovery.retain(receipt,reason);
    }

    private void retainQuotaAfterResult(Long memberId, String bizType, String reservation, String reason) {
        try {
            retainQuota(memberId, bizType, reservation, reason);
        } catch (RuntimeException persistenceFailure) {
            throw new com.chengyinhub.business.service.ai.AiProviderResultReceivedException(
                    AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务暂时不可用，请稍后重试", persistenceFailure);
        }
    }

    private static String traceIdOf(String reservation) {
        if (reservation != null) {
            String nonce = reservation.substring(reservation.lastIndexOf('|') + 1);
            if (nonce.matches("[a-f0-9]{32}")) return nonce;
        }
        return IdUtils.simpleUUID();
    }

    private String receiptOf(Long memberId,String bizType,String token) {
        if(token==null || token.isEmpty()) return null;
        int split=token.lastIndexOf('|'); if(split<=0) return null;
        String key=token.substring(0,split),nonce=token.substring(split+1);
        String prefix=RATE_KEY_PREFIX+rateGroupOf(bizType)+":"+memberId+":";
        if(!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[0-9]{8}")
                || !nonce.matches("[a-fA-F0-9]{32}")) return null;
        return "ai:gen:reservation:"+key+":"+nonce;
    }

    private void cancelQuota(String key, String receipt)
    {
        try {
            if(quotaRecovery!=null) { quotaRecovery.requireRefund(receipt,"SERVICE_FAILURE"); return; }
            redisTemplate.execute(REFUND_QUOTA_SCRIPT,
                    java.util.Arrays.<Object>asList(key, key + ":generation", receipt));
        } catch (RuntimeException unavailable) {
            // 若确定退款意图尚未提交，原结果仍待核；已提交意图由任务按原身份重放。
            log.warn("[AiGateway] quota compensation unavailable traceId={} code=COMPENSATION_UNAVAILABLE",
                    receipt.substring(Math.max(0, receipt.length() - 32)));
        }
    }

    private String rateGroupOf(String bizType)
    {
        // npc_event 刻意不在此列:它是 cache-only(不调模型),且今天并不经本网关
        //  (ApiAiNpcController /event 直连 NpcEventService)。真要收编时再单独判。
        if ("npc_chat".equals(bizType))
        {
            return RATE_GROUP_NPC;
        }
        // 拍照审核独立成池:拍照是「一局里拍几张」的交互,与创作类「一次生成一份稿」
        // 频次量级不同;共用 gen 池会让拍几张照就吃掉创作额度,额度耗尽后审核静默降级,
        // 真审核悄悄停了而玩家只看到 degraded 标记 —— 必须分池,否则发现不了。
        if ("photo_check".equals(bizType))
        {
            return RATE_GROUP_PHOTO;
        }
        return RATE_GROUP_GEN;
    }

    /** 按组取每日上限:npc / photo 独立配置(ai.model.npcDailyLimit / photoDailyLimit),其余走 ai.model.dailyLimit。 */
    private int dailyLimitOf(String rateGroup)
    {
        if (RATE_GROUP_NPC.equals(rateGroup))
        {
            return aiModelConfig.getNpcDailyLimit();
        }
        if (RATE_GROUP_PHOTO.equals(rateGroup))
        {
            return photoDailyLimit;
        }
        return aiModelConfig.getDailyLimit();
    }

    /**
     * 场景路由选模型:先走 AiSceneRouter 解析 provider 名,再从 Spring 容器取对应 bean。
     * null = cache-only(不调模型)。
     * 若 sceneRouter 未注入(单测场景),走旧 AiModelConfig.provider 单模型路径。
     */
    private LlmClient selectClient(String bizType)
    {
        // 单测/旧路径兼容:sceneRouter 未注入时走旧 resolveClient
        if (sceneRouter == null)
        {
            return resolveClient(aiModelConfig.getProvider());
        }

        String providerName = sceneRouter.resolve(bizType);

        // cache-only: caller 应提前短路
        if (sceneRouter.isCacheOnly(bizType))
        {
            return null;
        }

        return selectRoutedClient(providerName);
    }

    private LlmClient selectRoutedClient(String providerName)
    {
        // 按 bean 名查找
        if (llmClientsByBeanName != null)
        {
            LlmClient c = llmClientsByBeanName.get(providerName + "LlmClient");
            if (c != null) return requireAvailableClient(c, providerName);
            c = llmClientsByBeanName.get(providerName);
            if (c != null) return requireAvailableClient(c, providerName);
        }

        // 兜底:走旧 clientMap
        Map<String, LlmClient> m = clientMap;
        if (m == null)
        {
            synchronized (this)
            {
                m = clientMap;
                if (m == null)
                {
                    Map<String, LlmClient> tmp = new HashMap<>();
                    for (LlmClient c : llmClients)
                    {
                        tmp.put(c.name(), c);
                    }
                    clientMap = m = tmp;
                }
            }
        }
        return requireAvailableClient(m.get(providerName), providerName);
    }

    /** 旧单 provider 选择(兼容单测/未注入 sceneRouter 场景)。 */
    private LlmClient resolveClient(String provider)
    {
        Map<String, LlmClient> m = clientMap;
        if (m == null)
        {
            synchronized (this)
            {
                m = clientMap;
                if (m == null)
                {
                    Map<String, LlmClient> tmp = new HashMap<>();
                    for (LlmClient c : llmClients)
                    {
                        tmp.put(c.name(), c);
                    }
                    clientMap = m = tmp;
                }
            }
        }
        return requireAvailableClient(m.get(provider), provider);
    }

    /** 生产网关不接受 mock，包括显式配置和按 bean 名误绑定的 mock。测试使用 LlmClient 替身。 */
    private LlmClient requireAvailableClient(LlmClient client, String provider)
    {
        if (provider == null || "mock".equalsIgnoreCase(provider.trim()) || client == null
                || "mock".equalsIgnoreCase(client.name()))
        {
            log.warn("[AiGateway] unavailable provider={}", provider);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务配置缺失，请联系管理员");
        }
        return client;
    }

    /** 分段覆盖全部文本，重叠一小段上下文且不切断 UTF-16 代理对；任一段非放行立即停止。 */
    private String checkTextFully(String text, String openid)
    {
        if (text == null || text.isEmpty()) return null;
        int start = 0;
        while (start < text.length())
        {
            int end = Math.min(start + REVIEW_CHUNK_MAX, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            String verdict = wxContentSecurityService.checkText(text.substring(start, end), openid);
            if (verdict != null) return verdict;
            if (end == text.length()) return null;
            start = end - REVIEW_OVERLAP;
            if (Character.isLowSurrogate(text.charAt(start))
                    && Character.isHighSurrogate(text.charAt(start - 1))) start--;
        }
        return null;
    }

    private String resolveOpenid(Long memberId)
    {
        try
        {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? "" : (m.getWxOpenId() == null ? "" : m.getWxOpenId());
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private void saveGenerationLog(boolean prepared, String traceId, Long memberId, String role,
            String bizType, String provider, String input, String output, int safetyFlag,
            int latencyMs, int tokenIn, Integer tokenOut)
    {
        saveLog(traceId, memberId, role, bizType, provider,
                prepared ? "sha256:" + CanonicalDigest.sha256Hex(input == null ? "" : input) : input,
                prepared ? "sha256:" + CanonicalDigest.sha256Hex(output == null ? "" : output) : output,
                safetyFlag, latencyMs, tokenIn, tokenOut, prepared);
    }

    private void saveLog(String traceId, Long memberId, String role, String bizType, String provider,
                          String input, String output, int safetyFlag, int latencyMs, int tokenIn, Integer tokenOut)
    {
        saveLog(traceId, memberId, role, bizType, provider, input, output, safetyFlag, latencyMs, tokenIn, tokenOut, false);
    }

    private void saveLog(String traceId, Long memberId, String role, String bizType, String provider,
            String input, String output, int safetyFlag, int latencyMs, int tokenIn, Integer tokenOut, boolean prepared)
    {
        try
        {
            AiGenerationLog l = new AiGenerationLog();
            l.setMemberId(memberId);
            l.setRole(role);
            l.setBizType(bizType);
            l.setProvider(provider);
            l.setInputBrief(truncate(input, BRIEF_MAX));
            l.setOutputBrief(truncate(output, BRIEF_MAX));
            l.setSafetyFlag(safetyFlag);
            l.setLatencyMs(latencyMs);
            // Missing output is unknown usage, not a measured zero. All present counts are estimates.
            l.setTokenEst(tokenOut == null ? null : tokenIn + tokenOut);
            l.setTokenIn(tokenIn);
            l.setTokenOut(tokenOut);
            l.setTraceId(traceId);
            aiGenerationLogMapper.insertAiGenerationLog(l);
        }
        catch (Exception e)
        {
            if (prepared) log.error("[AiGateway] prepared-log-failed traceId={} code=LOG_UNAVAILABLE", traceId);
            else log.error("[AiGateway] log-failed", e);
        }
    }

    /** 粗略 token 估算: 中文 ~ 字符数/2, 英文 ~ 字符数/4, 混合取 字符数/3 */
    private int estimateSingle(String text)
    {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }

    private String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))
                && Character.isLowSurrogate(s.charAt(end))) end--;
        return s.substring(0, end);
    }
}
