package com.chengyinhub.business.service.ai.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /** 频控分组:NPC 聊天(独立额度) */
    private static final String RATE_GROUP_NPC = "npc";
    /** 频控分组:创作类(所有创作 bizType 共用一个额度池,与分池前等价) */
    private static final String RATE_GROUP_GEN = "gen";
    /** 频控分组:拍照审核(独立额度)—— 一次玩家拍照不该吃掉创作额度 */
    private static final String RATE_GROUP_PHOTO = "photo";

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

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

        // 1. 频控:单用户每日上限,按「组」分池(npc 一池 / 创作类共用一池)
        //
        // ⚠️ 额度先扣、后调模型 ⇒ 从这里到「拿到 modelText」之间**每一条失败路径**都要想清楚
        //    退不退(refundQuota 的 javadoc 列了当前判定)。现在是**逐点**退(选模型/调模型两处
        //    catch),不是区域级守卫 —— 代价是:**新加的前置检查若不显式退,就默认白扣用户额度**。
        //    没做成「区域 try/finally + 成功标志」是因为那需要先定死「入参内容安全拦截退不退」,
        //    而那条边界恰恰未决(见 refundQuota javadoc)。等那单落地,这里应改成区域守卫。
        boolean playerThemeDraft = "player".equals(role) && "theme_draft".equals(bizType);
        String rateKey = null;
        Long used = null;
        if (!playerThemeDraft)
        {
            String today = LocalDate.now().format(DATE_FMT);
            String rateGroup = rateGroupOf(bizType);
            rateKey = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + today;
            used = redisTemplate.opsForValue().increment(rateKey);
            if (used != null && used == 1)
            {
                redisTemplate.expire(rateKey, 86400, TimeUnit.SECONDS);
            }
            int dailyLimit = dailyLimitOf(rateGroup);
            if (used != null && used > dailyLimit)
            {
                log.info("[AiGateway] rate-limit memberId={} used={} limit={}", memberId, used, dailyLimit);
                throw new AiServiceException("今日AI次数已用完(" + dailyLimit + "/天),明天再来吧");
            }
        }

        // 2. 入参内容安全
        String openid = resolveOpenid(memberId);
        String secIn = wxContentSecurityService.checkText(truncate(userPrompt, 2000), openid);
        if (secIn != null)
        {
            log.info("[AiGateway] input-blocked memberId={} bizType={}", memberId, bizType);
            saveLog(traceId, memberId, role, bizType, null, userPrompt, secIn,
                    AiGenerationLog.SAFETY_INPUT_BLOCKED, 0, 0, 0);
            throw new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, secIn);
        }

        // 3. 选模型(场景路由)
        LlmClient client;
        try
        {
            client = selectClient(bizType);
        }
        catch (RuntimeException e)
        {
            // 「无可用的 AI 模型」= 配置错,纯我们的锅,用户没拿到任何东西 → 退额度。
            // ⚠️ catch RuntimeException 而非 AiServiceException:选模型/场景路由里的
            //    NPE、IllegalStateException 同样是我们的锅,窄 catch 会让它们带着已扣的额度跑掉。
            if (!playerThemeDraft) refundQuota(traceId, memberId, rateKey, used);
            throw e;
        }
        if (client == null)
        {
            // cache-only: npc_event 不调模型,返回空结果
            saveLog(traceId, memberId, role, bizType, "cache", userPrompt, "", 0, 0, 0, 0);
            return new AiGatewayResult(traceId, "[]", "cache", 0);
        }
        String modelName = client.name();
        long t0 = System.currentTimeMillis();

        String modelText;
        try
        {
            modelText = client.chat(systemPrompt, userPrompt);
        }
        catch (RuntimeException e)
        {
            log.error("[AiGateway] model-error memberId={} provider={}", memberId, modelName, e);
            int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
            saveLog(traceId, memberId, role, bizType, modelName, userPrompt, "ERROR:" + truncate(e.getMessage(), 100),
                    0, (int) (System.currentTimeMillis() - t0), tokenIn, 0);
            // 模型炸了是我们的锅,用户什么都没拿到 → 退还刚才扣的那一次
            if (!playerThemeDraft) refundQuota(traceId, memberId, rateKey, used);
            if (isConfigurationFailure(e))
            {
                throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 服务配置缺失,请联系管理员(traceId=" + traceId + ")", e);
            }
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                "AI 繁忙,请稍后重试(traceId=" + traceId + ")", e);
        }

        int latencyMs = (int) (System.currentTimeMillis() - t0);
        int tokenIn = estimateSingle(systemPrompt) + estimateSingle(userPrompt);
        int tokenOut = estimateSingle(modelText);

        // 4. 出参内容安全
        String secOut = wxContentSecurityService.checkText(modelText, openid);
        if (secOut != null)
        {
            log.info("[AiGateway] output-blocked memberId={} bizType={}", memberId, bizType);
            saveLog(traceId, memberId, role, bizType, modelName, userPrompt, modelText,
                    AiGenerationLog.SAFETY_OUTPUT_BLOCKED, latencyMs, tokenIn, tokenOut);
            throw new AiServiceException(AiServiceException.Reason.OUTPUT_BLOCKED, "生成内容未通过安全检测,请调整后重试");
        }

        // 5. 落日志
        saveLog(traceId, memberId, role, bizType, modelName, userPrompt, modelText, 0, latencyMs, tokenIn, tokenOut);

        return new AiGatewayResult(traceId, modelText, modelName, 0);
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
    public void consumeQuota(Long memberId, String bizType)
    {
        String today = LocalDate.now().format(DATE_FMT);
        String rateGroup = rateGroupOf(bizType);
        String rateKey = RATE_KEY_PREFIX + rateGroup + ":" + memberId + ":" + today;
        Long used = redisTemplate.opsForValue().increment(rateKey);
        if (used != null && used == 1)
        {
            redisTemplate.expire(rateKey, 86400, TimeUnit.SECONDS);
        }
        int dailyLimit = dailyLimitOf(rateGroup);
        if (used != null && used > dailyLimit)
        {
            log.info("[AiGateway] rate-limit bizType={} group={} memberId={} used={} limit={}",
                    bizType, rateGroup, memberId, used, dailyLimit);
            throw new AiServiceException("今日AI次数已用完(" + dailyLimit + "/天),明天再来吧");
        }
    }

    /** 退还 consumeQuota 扣掉的那一次;池子与 consumeQuota 逐字一致(退错池子 = 没退)。 */
    @Override
    public void refundQuota(Long memberId, String bizType)
    {
        String today = LocalDate.now().format(DATE_FMT);
        String rateKey = RATE_KEY_PREFIX + rateGroupOf(bizType) + ":" + memberId + ":" + today;
        refundQuota("non-text:" + bizType, memberId, rateKey, 1L);
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
            if (c != null) return c;
            c = llmClientsByBeanName.get(providerName);
            if (c != null) return c;
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
        LlmClient c = m.get(providerName);
        if (c == null)
        {
            log.warn("[AiGateway] unknown provider={}, fallback to mock", providerName);
            c = m.get("mock");
        }
        if (c == null)
        {
            throw new AiServiceException("无可用的 AI 模型");
        }
        return c;
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
        LlmClient c = m.get(provider);
        if (c == null)
        {
            log.warn("[AiGateway] unknown provider={}, fallback to mock", provider);
            c = m.get("mock");
        }
        if (c == null)
        {
            throw new AiServiceException("无可用的 AI 模型");
        }
        return c;
    }

    /**
     * 退还刚才 increment 掉的那一次每日额度。
     *
     * <p>⚠️ 只在**我们的锅**时调用 —— 模型炸(AI 繁忙)、无可用模型(配置错)。
     * 这两种情况用户什么都没拿到,不该扣他的额度:每天 20 次的池子,AI 抖 3 次就白掉 15%,
     * 而用户完全无从知晓(日志里只有 ERROR),也无处申诉。
     *
     * <p>⚠️ 本次<b>不</b>退的几处,理由各不相同,别一视同仁:
     * <ul>
     *   <li>已超限 —— 本来就超了,退了反而让狂刷看起来像没刷过。<b>这条是确定的。</b></li>
     *   <li>出参安全拦截 —— 模型产出违规算谁的?产品策略未拍板,不动。</li>
     *   <li><b>入参内容安全拦截 —— 边界未决,不是「刻意不退」。</b>
     *       {@code WxContentSecurityServiceImpl.checkText} 是 fail-close,且用<b>同一个非空
     *       String 通道</b>表达两件事:<b>四条 fail-close 的「审核服务繁忙」返回</b>(微信接口报错/
     *       超时/兜底 catch —— 我们的锅)与<b>两条真违规返回</b>(87014 命中、suggest=risky ——
     *       用户的锅)。本层 {@code secIn != null} 把两者塌成一种处理 ⇒ <b>微信审核一抖,
     *       用户白扣额度、还在 ai_generation_log 里被标成 SAFETY_INPUT_BLOCKED(审计留污点)</b>
     *       —— 与本方法要修的是同一类病。要分开需让 checkText 返回可区分的判定
     *       (改内容安全接口 = 合规路径),超出本次范围,<b>另开单</b>。
     *       在那之前这里维持原状,但别把它当成「设计如此」。
     *       <p>(刻意不写行号:跨文件坐标必烂,且烂得静默。指语义,别指坐标。)</li>
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
        if (used == null)
        {
            return;
        }
        try
        {
            Long left = redisTemplate.opsForValue().decrement(rateKey);
            // ⚠️ Redis DECR 对**不存在的 key** 会「先建 key 置 0 再减 1」⇒ 留下一个值 -1、
            //    且**没有 TTL** 的 key(TTL 只在 increment 返回 1 那一刻设过)。本机实测:
            //      DEL k; DECR k → 值 -1、TTL -1(永不过期)。
            //
            //    key 何时会「不存在」?两种,后果不同,别混:
            //     ① **正常 TTL 到期**:rateKey 带日期(…:yyyyMMdd),而每个请求在自己开始时算 today
            //        ⇒ 用 K_D 的请求必然发生在 D 当天,早于 K_D 过期(TTL 从首次调用起 86400s,
            //        必然 ≥ D+1 零点)。所以 DECR 扑空只可能发生在 D+1 零点之后,而那之后
            //        再没有请求会算出 K_D ⇒ 这个 -1 key **没人读得到**,是纯内存泄漏,**不是白送额度**。
            //     ② **日期内 key 丢失**(maxmemory 淘汰 / Redis 重启无持久化 / 人工 DEL):
            //        当天后续请求仍会算出同一个 K_D、读到 -1 ⇒ 计数从 -1 起 ⇒ **真会白送一次**。
            //        (本机那次 `DEL k; DECR k` 实验模拟的正是这一种,不是 ①。)
            //
            //    两种都该删:① 是泄漏,② 是白送。key 都没了,这一天的计数本就该归零。
            //
            // ⚠️ decrement→delete 非原子(TOCTOU):若在这两步之间有并发 increment 把 key 重建,
            //    这里的 delete 会把它抹掉 ⇒ 该用户当天计数清零。窗口极窄、只影响他自己、
            //    且方向对用户有利(本方法本就是给用户退额度),故本次不上 Lua;
            //    真要收口就把「DECR; IF result < 0 THEN DEL」写成一个 Lua 脚本原子执行。
            if (left != null && left < 0)
            {
                redisTemplate.delete(rateKey);
            }
        }
        catch (RuntimeException e)
        {
            // 带上 traceId/memberId:退款失败 = 用户被静默多扣一次,只打 rateKey 的话
            // 事后无法按人排查、也无法与 ai_generation_log join。
            log.warn("[AiGateway] refund-quota failed traceId={} memberId={} key={}", traceId, memberId, rateKey, e);
        }
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
                          String input, String output, int safetyFlag, int latencyMs, int tokenIn, int tokenOut)
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
            l.setTokenEst(tokenIn + tokenOut);
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
