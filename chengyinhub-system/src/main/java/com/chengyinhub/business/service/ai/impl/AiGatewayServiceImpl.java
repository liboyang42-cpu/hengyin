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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Arrays;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.WxTextCheckResult;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiSceneRouter;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
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
    private static final String RATE_KEY_PREFIX = "ai:gen:limit:";
    private static final int BRIEF_MAX = 500;
<<<<<<< public
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

=======
    private static final int SAFETY_CHUNK_SIZE = 2000;
    private static final int SAFETY_CHUNK_OVERLAP = 32;
    private static final String REFUND_KEY_PREFIX = "ai:gen:refund:";
    private static final DefaultRedisScript<Long> REFUND_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end; "
        + "local used = redis.call('GET', KEYS[1]); "
        + "if used and not string.match(used, '^%d+$') then return -1 end; "
        + "redis.call('SET', KEYS[2], '1', 'EX', 172800); "
        + "if used and tonumber(used) > 0 then redis.call('DECR', KEYS[1]); return 1 end; "
        + "return 0", Long.class);

    // shortcut: 持续 Redis 故障或进程退出仍缺持久补偿，要求完整恢复时接入现有补偿任务。
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
        "local reserved = redis.call('GET', KEYS[2]); "
        + "if reserved then if tonumber(reserved) and tonumber(reserved) > 0 then return tonumber(reserved) end; return -1 end; "
        + "local used = redis.call('GET', KEYS[1]); "
        + "if used and not string.match(used, '^%d+$') then return -1 end; "
        + "if not string.match(ARGV[1], '^%d+$') or tonumber(ARGV[1]) < 1 or tonumber(ARGV[1]) > 86400 then return -1 end; "
        + "local next = redis.call('INCR', KEYS[1]); "
        + "if next == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; "
        + "redis.call('SET', KEYS[2], next, 'EX', 172800); return next", Long.class);
    private static final DefaultRedisScript<Long> RESERVED_REFUND_SCRIPT = new DefaultRedisScript<>(
        "local reserved = redis.call('GET', KEYS[2]); "
        + "if not reserved then redis.call('SET', KEYS[2], '0', 'EX', 172800); return 0 end; "
        + "if reserved == '0' then return 0 end; "
        + "local used = redis.call('GET', KEYS[1]); "
        + "if not tonumber(reserved) or tonumber(reserved) <= 0 or (used and not string.match(used, '^%d+$')) then return -1 end; "
        + "redis.call('SET', KEYS[2], '0', 'EX', 172800); "
        + "if used and tonumber(used) > 0 then redis.call('DECR', KEYS[1]); return 1 end; "
        + "return 0", Long.class);
>>>>>>> private-codex/batch1

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

    @Override
    public AiGatewayResult generate(Long memberId, String role, String bizType,
                                     String systemPrompt, String userPrompt)
    {
        // 0. 全链路 traceId:必须先于任何 saveLog 生成,否则 input-blocked / cache-only
        //    两条分支落库的 trace_id 与返给前端的对不上(审计无法 join)。
        String traceId = IdUtils.simpleUUID();

<<<<<<< public
        // 玩家草稿由 Creator 的三次独立池计费；其他场景绑定本次原日预占。
        boolean playerThemeDraft = "player".equals(role) && "theme_draft".equals(bizType);
        String reservation = playerThemeDraft ? null : consumeQuota(memberId, bizType);
        String openid = resolveOpenid(memberId);
        stageQuota(memberId,bizType,reservation,"INPUT_REVIEW_STARTED");
        String secIn;
        try {
            // 两段都可能含业务上下文；审核与 provider 收到的文本保持完整，不截掉后缀。
            String assembledInput = (systemPrompt == null ? "" : systemPrompt) + "\n"
                    + (userPrompt == null ? "" : userPrompt);
            secIn = checkTextFully(assembledInput, openid);
        } catch (RuntimeException unavailable) {
            if (!playerThemeDraft) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "内容审核暂时不可用，请稍后重试", unavailable);
        }
        if (secIn != null) {
            if (IWxContentSecurityService.isDetectionUnavailable(secIn)) {
                if (!playerThemeDraft) refundQuota(memberId, bizType, reservation);
                saveLog(traceId, memberId, role, bizType, null, userPrompt, secIn, 0, 0, 0, 0);
                throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, secIn);
            }
            retainQuota(memberId,bizType,reservation,"INPUT_REJECTED");
            saveLog(traceId, memberId, role, bizType, null, userPrompt, secIn,
                    AiGenerationLog.SAFETY_INPUT_BLOCKED, 0, 0, 0);
            // 人工复核及未知拒绝仍沿用 UNKNOWN；只有现有微信明确违规文案改变分类。
            if ("内容包含违规信息，请修改后重试".equals(secIn)) {
                throw new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, secIn);
            }
            throw new AiServiceException(secIn);
=======
        // 1. 频控:单用户每日上限,按「组」分池(npc 一池 / 创作类共用一池)
        //
        // ⚠️ 额度先扣、后调模型 ⇒ 从这里到「拿到 modelText」之间**每一条失败路径**都要想清楚
        //    退不退(refundQuota 的 javadoc 列了当前判定)。现在是**逐点**退(选模型/调模型两处
        //    catch),不是区域级守卫 —— 代价是:**新加的前置检查若不显式退,就默认白扣用户额度**。
        //    typed 审核已明确：SERVICE_UNAVAILABLE 退，CONTENT_REJECT 不退。
        boolean playerThemeDraft = "player".equals(role) && "theme_draft".equals(bizType);
        String rateKey = null;
        Long used = null;
        if (!playerThemeDraft)
        {
            String today = LocalDate.now().format(DATE_FMT);
            String rateGroup = rateGroupOf(bizType);
            rateKey = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + today;
            used = reserveQuota(rateKey, "v2:" + traceId);
            int dailyLimit = dailyLimitOf(rateGroup);
            if (used != null && used > dailyLimit)
            {
                log.info("[AiGateway] rate-limit memberId={} used={} limit={}", memberId, used, dailyLimit);
                throw new AiServiceException("今日AI次数已用完(" + dailyLimit + "/天),明天再来吧");
            }
        }

        // 2. 入参内容安全
        String openid = resolveOpenid(memberId);
        WxTextCheckResult secIn = checkCompleteModelInput(systemPrompt, userPrompt, openid);
        if (secIn.getStatus() != WxTextCheckResult.Status.PASS)
        {
            if (secIn.getStatus() == WxTextCheckResult.Status.SERVICE_UNAVAILABLE)
            {
                log.warn("[AiGateway] input-check-unavailable memberId={} bizType={} reason={}",
                        memberId, bizType, secIn.getReason());
                saveLog(traceId, memberId, role, bizType, null, userPrompt,
                        "INPUT_CHECK_UNAVAILABLE:" + secIn.getReason(), 0, 0, 0, 0);
                if (!playerThemeDraft) refundQuota(traceId, memberId, rateKey, used);
            }
            else
            {
                log.info("[AiGateway] input-blocked memberId={} bizType={} reason={}",
                        memberId, bizType, secIn.getReason());
                saveLog(traceId, memberId, role, bizType, null, userPrompt, secIn.getMessage(),
                        AiGenerationLog.SAFETY_INPUT_BLOCKED, 0, 0, 0);
            }
            throw new AiServiceException(secIn.getStatus() == WxTextCheckResult.Status.CONTENT_REJECT
                    ? AiServiceException.Reason.INPUT_BLOCKED : AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    secIn.getMessage());
>>>>>>> private-codex/batch1
        }

        // 3. 选模型(场景路由)
        LlmClient client;
        try
        {
            client = selectClient(bizType);
            // This local check proves no HTTP was sent; provider exceptions cannot prove that.
            if (client instanceof com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient)
            {
                ((com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient) client).requireReady();
            }
        }
        catch (RuntimeException e)
        {
            // 「无可用的 AI 模型」= 配置错,纯我们的锅,用户没拿到任何东西 → 退额度。
            // ⚠️ catch RuntimeException 而非 AiServiceException:选模型/场景路由里的
            //    NPE、IllegalStateException 同样是我们的锅,窄 catch 会让它们带着已扣的额度跑掉。
            if (!playerThemeDraft) refundQuota(memberId, bizType, reservation);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务配置缺失,请联系管理员(traceId=" + traceId + ")", e);
        }
        if (client == null)
        {
            // cache-only: npc_event 不调模型,返回空结果
            saveLog(traceId, memberId, role, bizType, "cache", userPrompt, "", 0, 0, 0, 0);
            retainQuota(memberId,bizType,reservation,"CACHE_RESULT");
            return new AiGatewayResult(traceId, "[]", "cache", 0);
        }
        String modelName = client.name();
        long t0 = System.currentTimeMillis();

        String modelText;
        try
        {
            stageQuota(memberId,bizType,reservation,"MODEL_STARTED");
            modelText = client.chat(systemPrompt, userPrompt);
            if (modelText == null || modelText.trim().isEmpty())
            {
                throw new IllegalStateException("AI 模型返回为空");
            }
        }
        catch (RuntimeException e)
        {
            log.error("[AiGateway] model-error memberId={} provider={}", memberId, modelName, e);
            int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
            saveLog(traceId, memberId, role, bizType, modelName, userPrompt, "ERROR:" + truncate(e.getMessage(), 100),
                    0, (int) (System.currentTimeMillis() - t0), tokenIn, null);
            // MODEL_STARTED preserves the original receipt for the existing recovery worker.
            // A timeout/empty or malformed response may already have incurred provider usage.
            // Never refund or resend merely because no answer reached this service.
            throw new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException(
                "AI 请求结果待确认，请保留原请求(traceId=" + traceId + ")", e);
        }

        if (modelText == null || modelText.trim().length() == 0)
        {
            int emptyOutputTokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
            saveLog(traceId, memberId, role, bizType, modelName, userPrompt, "ERROR:EMPTY_MODEL_OUTPUT",
                    0, (int) (System.currentTimeMillis() - t0), emptyOutputTokenIn, 0);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 返回空结果,请稍后重试(traceId=" + traceId + ")");
        }

        int latencyMs = (int) (System.currentTimeMillis() - t0);
        int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
        int tokenOut = estimateSingle(modelText);

        // 4. 出参内容安全
<<<<<<< public
        String secOut;
        try { secOut = checkTextFully(modelText, openid); }
        catch(RuntimeException unknownOutput) {
            retainQuota(memberId,bizType,reservation,"OUTPUT_REVIEW_UNKNOWN");
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "内容审核暂时不可用，请稍后重试", unknownOutput);
        }
        if (secOut != null)
=======
        WxTextCheckResult secOut = checkTextInChunks(modelText, openid);
        if (secOut.getStatus() != WxTextCheckResult.Status.PASS)
>>>>>>> private-codex/batch1
        {
            if (IWxContentSecurityService.isDetectionUnavailable(secOut))
            {
                retainQuota(memberId,bizType,reservation,"OUTPUT_REVIEW_UNKNOWN");
                saveLog(traceId, memberId, role, bizType, modelName, userPrompt, secOut,
                        0, latencyMs, tokenIn, tokenOut);
                throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, secOut);
            }
            log.info("[AiGateway] output-blocked memberId={} bizType={}", memberId, bizType);
            saveLog(traceId, memberId, role, bizType, modelName, userPrompt, modelText,
                    AiGenerationLog.SAFETY_OUTPUT_BLOCKED, latencyMs, tokenIn, tokenOut);
            retainQuota(memberId,bizType,reservation,"OUTPUT_REJECTED");
            throw new AiServiceException("生成内容未通过安全检测,请调整后重试");
        }

        // 5. 落日志
        saveLog(traceId, memberId, role, bizType, modelName, userPrompt, modelText, 0, latencyMs, tokenIn, tokenOut);

        retainQuota(memberId,bizType,reservation,"SUCCESS");
        return new AiGatewayResult(traceId, modelText, modelName, 0);
    }

<<<<<<< public
=======
    /**
     * 安全覆盖必须与真正发送的输入一致：system 资料和 user 文本均逐段送检。
     * 每段不超过既有 2000 字符边界，但不再丢弃第 2001 字符之后的内容。
     */
    private WxTextCheckResult checkCompleteModelInput(String systemPrompt, String userPrompt, String openid)
    {
        WxTextCheckResult result = checkTextInChunks(systemPrompt, openid);
        return result.getStatus() == WxTextCheckResult.Status.PASS
                ? checkTextInChunks(userPrompt, openid) : result;
    }

    private WxTextCheckResult checkTextInChunks(String text, String openid)
    {
        String value = text == null ? "" : text;
        if (value.length() == 0) return wxContentSecurityService.checkTextTyped(value, openid);
        int start = 0;
        while (start < value.length())
        {
            int end = Math.min(value.length(), start + SAFETY_CHUNK_SIZE);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)))
            {
                end--;
            }
            WxTextCheckResult result = wxContentSecurityService.checkTextTyped(value.substring(start, end), openid);
            if (result.getStatus() != WxTextCheckResult.Status.PASS) return result;
            if (end == value.length()) break;
            start = Math.max(start + 1, end - SAFETY_CHUNK_OVERLAP);
            if (Character.isLowSurrogate(value.charAt(start))) start--;
        }
        return WxTextCheckResult.pass(WxTextCheckResult.Reason.PASS);
    }

    /** 模型未启用/未配置 key 是部署配置错误，必须和短时上游繁忙分开报。 */
    private boolean isConfigurationFailure(RuntimeException e)
    {
        String message = e == null ? null : e.getMessage();
        if (message == null) return false;
        return message.contains("未配置 apiKey")
            || message.contains("enabled=false")
            || message.contains("provider is not enabled");
    }

>>>>>>> private-codex/batch1
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
        String key = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + LocalDate.now().format(DATE_FMT);
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
        int used = 0;
        if (raw instanceof Number)
        {
            used = ((Number) raw).intValue();
        }
        else if (raw != null)
        {
            try
            {
                used = Integer.parseInt(String.valueOf(raw).trim());
            }
            catch (NumberFormatException ignored)
            {
                used = 0;
            }
        }
        return Math.max(0, limit - used);
    }

    @Override
    public int dailyQuota(String bizType)
    {
        return dailyLimitOf(rateGroupOf(bizType));
    }

    /**
     * 非文本调用(拍照视觉审核)的计次:池子由 bizType 决定(photo_check 走独立 photo 池),
     * 键名/上限/分池规则与 generate 同源。先扣后调,失败路径退不退由调用方按
     * 「用户有没有拿到结论」决定(refundQuota 对应本方法)。
     */
    @Override
    public String consumeQuota(Long memberId, String bizType)
    {
<<<<<<< public
        String group = rateGroupOf(bizType);
        String key = RATE_KEY_PREFIX + group + ":" + memberId + ":" + LocalDate.now().format(DATE_FMT);
        String nonce = IdUtils.simpleUUID();
        String receipt = "ai:gen:reservation:" + key + ":" + nonce;
        if(quotaRecovery!=null) quotaRecovery.prepare(memberId,bizType,group,key,receipt);
        Long used;
        try {
            used = redisTemplate.execute(RESERVE_QUOTA_SCRIPT,
                    java.util.Arrays.<Object>asList(key, key + ":generation", receipt),
                    Integer.valueOf(86400), Integer.valueOf(dailyLimitOf(group)), Integer.valueOf(0));
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
        if (used > dailyLimitOf(group)) {
            if(quotaRecovery!=null) quotaRecovery.retain(receipt,"RATE_LIMITED");
            throw new AiServiceException("今日AI次数已用完(" + dailyLimitOf(group) + "/天),明天再来吧");
        }
        return key + "|" + nonce;
=======
        String today = LocalDate.now().format(DATE_FMT);
        String rateGroup = rateGroupOf(bizType);
        String rateKey = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + today;
        String reservationId = "v2:" + IdUtils.simpleUUID();
        Long used = reserveQuota(rateKey, reservationId);
        int dailyLimit = dailyLimitOf(rateGroup);
        if (used != null && used > dailyLimit)
        {
            log.info("[AiGateway] rate-limit bizType={} group={} memberId={} used={} limit={}",
                    bizType, rateGroup, memberId, used, dailyLimit);
            throw new AiServiceException("今日AI次数已用完(" + dailyLimit + "/天),明天再来吧");
        }
        return rateKey + "|" + reservationId;
>>>>>>> private-codex/batch1
    }

    @Override
    public void refundQuota(Long memberId, String bizType, String reservation)
    {
<<<<<<< public
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
        if(quotaRecovery!=null && receipt!=null) quotaRecovery.stage(receipt,stage);
    }

    @Override
    public void retainQuota(Long memberId,String bizType,String reservation,String reason) {
        String receipt=receiptOf(memberId,bizType,reservation);
        if(quotaRecovery!=null && receipt!=null) quotaRecovery.retain(receipt,reason);
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
            log.warn("[AiGateway] quota compensation unavailable key={}", key, unavailable);
        }
=======
        String expectedPrefix = RATE_KEY_PREFIX + rateGroupOf(bizType) + ":" + memberId + ":";
        int separator = reservation == null ? -1 : reservation.lastIndexOf('|');
        if (separator <= expectedPrefix.length() || !reservation.startsWith(expectedPrefix)
                || separator == reservation.length() - 1)
        {
            log.warn("[AiGateway] invalid refund reservation memberId={} bizType={}", memberId, bizType);
            return;
        }
        refundQuota("non-text:" + bizType, memberId, reservation.substring(0, separator), 1L,
                reservation.substring(separator + 1));
>>>>>>> private-codex/batch1
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

<<<<<<< public
    /** 生产网关不接受 mock，包括显式配置和按 bean 名误绑定的 mock。测试使用 LlmClient 替身。 */
    private LlmClient requireAvailableClient(LlmClient client, String provider)
    {
        if (provider == null || "mock".equalsIgnoreCase(provider.trim()) || client == null
                || "mock".equalsIgnoreCase(client.name()))
        {
            log.warn("[AiGateway] unavailable provider={}", provider);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务配置缺失，请联系管理员");
=======
    /**
     * 退还刚才 increment 掉的那一次每日额度。
     *
     * <p>⚠️ 只在**我们的锅**时调用 —— 输入审核服务不可用、模型炸(AI 繁忙)、无可用模型(配置错)。
     * 这些情况用户什么都没拿到,不该扣他的额度:每天 20 次的池子,AI 抖 3 次就白掉 15%,
     * 而用户完全无从知晓(日志里只有 ERROR),也无处申诉。
     *
     * <p>⚠️ 本次<b>不</b>退的几处,理由各不相同,别一视同仁:
     * <ul>
     *   <li>已超限 —— 本来就超了,退了反而让狂刷看起来像没刷过。<b>这条是确定的。</b></li>
     *   <li>出参安全拦截 —— 模型产出违规算谁的?产品策略未拍板,不动。</li>
     *   <li>入参明确 CONTENT_REJECT —— 用户内容被拒，不退；审核 SERVICE_UNAVAILABLE 在调用处退款。</li>
     *   <li>cache-only 早返回(选不到 client ⇒ npc_event 直接返回 {@code []})——
     *       <b>扣了额度但没调模型</b>。当前不退:调用方拿到了可用结果,算一次。
     *       ⚠️ 若将来认为「没调模型就不该计费」,这里就是要改的地方。</li>
     * </ul>
     *
     * <p>用与 increment 逐字相同的 rateKey(退错池子 = 没退,还会还错别人的额度)。
     * used==null 表示 increment 本身就没成功(Redis 异常),没什么可退。
     * 退失败只 warn:少还一次额度事小,把原始错误盖掉事大。
     */
    private void refundQuota(String traceId, Long memberId, String rateKey, Long used)
    {
        refundQuota(traceId, memberId, rateKey, used, "v2:" + traceId);
    }

    private void refundQuota(String traceId, Long memberId, String rateKey, Long used, String reservationId)
    {
        if (used == null) return;
        try
        {
            // 旧在途 opaque 凭据仍按旧退款协议收口，新凭据必须有自己的预占记录。
            Long result = redisTemplate.execute(reservationId.startsWith("v2:") ? RESERVED_REFUND_SCRIPT : REFUND_SCRIPT,
                    Arrays.asList(rateKey, REFUND_KEY_PREFIX + reservationId));
            if (result == null || result < 0)
                log.warn("[AiGateway] refund not confirmed traceId={} memberId={} key={}", traceId, memberId, rateKey);
        }
        catch (RuntimeException e)
        {
            // 丢响应时可能已执行；保留防重标记，重试不能再次补额。
            log.warn("[AiGateway] refund-quota failed traceId={} memberId={} key={}", traceId, memberId, rateKey, e);
        }
    }

    private Long reserveQuota(String rateKey, String reservationId)
    {
        try
        {
            Long used = redisTemplate.execute(RESERVE_SCRIPT,
                    Arrays.asList(rateKey, REFUND_KEY_PREFIX + reservationId), 86400);
            if (used == null || used < 1)
            {
                // 未确认预占属于基础设施故障，不能被消费者误判为次数超限。
                throw new IllegalStateException("AI次数记录未确认，请稍后重试");
            }
            return used;
        }
        catch (RuntimeException e)
        {
            // 保留调用方原异常语义；取消缺失预占只落标记，不能扣减其他请求。
            refundQuota("reserve-failed", null, rateKey, 1L, reservationId);
            throw e;
>>>>>>> private-codex/batch1
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

    private void saveLog(String traceId, Long memberId, String role, String bizType, String provider,
                          String input, String output, int safetyFlag, int latencyMs, int tokenIn, Integer tokenOut)
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
            log.error("[AiGateway] log-failed", e);
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
        return s.length() <= max ? s : s.substring(0, max);
    }
}
