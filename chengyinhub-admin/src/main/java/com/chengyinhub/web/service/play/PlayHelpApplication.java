package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayHelpUseVO;
import com.chengyinhub.business.domain.vo.PlayHelpViewVO;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.PlayHelpContent;
import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.chengyinhub.business.service.support.PlayHelpReceiptReadModel;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaApplication;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaPolicy;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelSpec;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelView;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * BE-16 帮助路由的服务端编排:原局身份授权、冻结层级读取、获准内容下发;代价与幂等由
 * {@link PlayHelpCostApplication}(G5)在同一事务里完成。
 *
 * <p>原文依据(私有施工文档 v1.3.2,只读):BE-16「服务端下发本局可用层级 + 剩余免费额度 + 每次代价;
 * 使用后按代价扣分;不泄露答案文本(除 answer 级)」;M.7「同一级重新查看不算新使用」「跳过记 0」;
 * G12「同动作仅落一次账;扣款失败不解锁,解锁失败不留扣款」。
 *
 * <p>事务边界:本类 {@link #use} 是外层事务,{@link PlayHelpCostApplication} 的三个入口都是
 * {@code MANDATORY},所以业务拒绝必须抛出去交给控制器映射文案 —— 在事务里 catch 后返回会把
 * 已写的半状态提交。
 *
 * <p>刻意不做:自己判题/判分/发奖励;接受客户端上报的已解锁层或余额;把 {@code unlockNodeHint}
 * 一次付费取两条的旧语义包装成按级收费;L6 位置工具(扩半径/越权解锁)。
 */
@Service
public class PlayHelpApplication {

    static final String ACTION_HELP = "HELP";
    static final String ACTION_SKIP = "SKIP";

    @Autowired
    private TopicRouteSessionMapper topicRouteSessionMapper;
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired
    private PlayAttemptLogMapper playAttemptLogMapper;
    @Autowired
    private PlayNodeContext playNodeContext;
    /** 整局共享免费额度(G7):额度池初始化、剩余次数与首次免费消费都只有它一处。 */
    @Autowired
    private PlayHelpRunQuotaApplication playHelpRunQuotaApplication;
    @Autowired
    private ObjectMapper objectMapper;
    /** 成绩明细只读视图:完成回包与本只读回执共用同一份计算。 */
    @Autowired
    private PlayHelpReceiptReadModel playHelpReceiptReadModel;
    /** 基准分只认原模板验真方式(1/3 = 文字/选项谜题),与 PuzzleScorePolicy 同一链。 */
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    /** 进度行是「跳过/揭示答案」的服务端权威落点(completion_mode)。 */
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    /** 跳过的终态与成绩事实在高级玩法运行时就地判定(幂等/CAS 都在那里)。 */
    @Autowired
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService advancedGameRuntimeService;
    /** 跳过仍走既有完成链:进度/奖励/通关都是它一家写的,不另开一条写入路径。 */
    @Autowired
    private PlayNodeCompletionApplication playNodeCompletionApplication;
    /** 纯高级玩法局(game_runtime_session)的帮助入口:同一套层级/额度规则,不新开第二套。 */
    @Autowired
    private GameRuntimeSessionMapper gameRuntimeSessionMapper;

    /** GET /api/play/help:按冻结层级与既有事实下发,不含任何答案或提示正文。 */
    public PlayHelpViewVO view(Long actorMemberId, String runId, String nodeId) {
        return viewResolved(resolve(actorMemberId, runId, nodeId));
    }

    private PlayHelpViewVO viewResolved(Resolved resolved) {
        // 整局共享 N:剩余次数由 G7 额度账本给出,未使用过时等于冻结的 N;旧局没有整局 N 时为 null。
        Integer remaining = resolved.freeUsesPerRun == null ? null
                : playHelpRunQuotaApplication.remainingFreeUses(
                        resolved.memberId, resolved.scope, resolved.freeUsesPerRun);
        List<LevelView> layers = remaining == null
                // 旧局没有整局 N:沿用逐层 freeQuota 的老口径,一字不改。
                ? PlayHelpUsagePolicy.views(resolved.ladder, unlockedLevels(resolved))
                : PlayHelpUsagePolicy.views(resolved.ladder, unlockedLevels(resolved), remaining);
        // 没有可用层时不给计数器:enabled=false 的旧主题界面保持原样。
        return new PlayHelpViewVO(resolved.runId, resolved.nodeId, !layers.isEmpty(), List.copyOf(layers),
                layers.isEmpty() ? null : remaining);
    }

    /**
     * POST /api/play/help/use:只有获准层才返回内容。
     *
     * <p>{@code action=SKIP} 不是「一层提示」而是本题收尾:交回执时必须走
     * {@link #skip} 那条链(本题 0 分 + 辅助完成),不能在这里当成免费提示下发。
     */
    @Transactional(rollbackFor = Exception.class)
    public PlayHelpUseVO use(Long actorMemberId, String runId, String nodeId, Integer level, String action,
                             String requestId) {
        return useResolved(resolve(actorMemberId, runId, nodeId), level, action, requestId);
    }

    private PlayHelpUseVO useResolved(Resolved resolved, Integer level, String action, String requestId) {
        if (StringUtils.isBlank(requestId)) {
            // BE-16 要求带 requestId;缺了就无法证明"重发同一次动作不重复扣"。
            throw new HelpRefusal("缺少请求编号");
        }
        String normalized = StringUtils.isBlank(action) ? ACTION_HELP : action.trim().toUpperCase(Locale.ROOT);
        if (ACTION_SKIP.equals(normalized)) {
            // 跳过有独立的终态与成绩事实(辅助完成 + 0 分),绝不能借提示回执返回。
            throw new HelpRefusal("跳过请使用 /api/play/help/skip");
        }
        if (!ACTION_HELP.equals(normalized)) throw new HelpRefusal("参数有误");
        if (level == null || level <= 0) throw new HelpRefusal("参数有误");

        LevelSpec spec = levelOf(resolved.specs, level);
        if (spec == null) throw new HelpRefusal("该级帮助未配置");
        if (!spec.usable()) {
            throw new HelpRefusal("该层不可用（" + spec.reason() + "），未扣除任何代价");
        }
        // 先证明这层内容拿得出来,再动钱;拿不出来既不解锁也不扣款。
        String content = PlayHelpContent.approved(level, spec.type(),
                ladderEntry(resolved.ladder, level), resolved.frozenAnswer);
        if (content == null) throw new HelpRefusal("该层内容未配置，未扣除任何代价");

        // 走 G7 的整局额度入口:它先认领请求、再按剩余额度决定免费还是收费、最后把首次免费记进额度池。
        // 同一 requestId 重发只回放,不再消耗额度;本局没有整局 N 时它原样转给 G5(旧逐层口径)。
        PlayHelpRunQuotaApplication.QuotaOutcome outcome = playHelpRunQuotaApplication.use(
                resolved.memberId, resolved.scope, level, resolved.freeUsesPerRun,
                spec.freeQuota(), spec.costPoints(), requestId.trim());
        PlayHelpCostApplication.Charge charge = outcome.charge();
        return new PlayHelpUseVO(resolved.runId, resolved.nodeId, level, spec.type(), content,
                charge.applied(), charge.alreadyUnlocked(), charge.chargedPoints(), charge.balanceAfter());
    }

    /**
     * `GET /api/play/help/receipt?runId=&nodeId=`:重进后的**只读成绩明细**。
     *
     * <p>与完成回包里的 `helpReceipt` 是同一份计算（{@link PlayHelpReceiptReadModel}），
     * 因此两边数值不可能漂开。本方法不写任何行：同一动作重试只会读到同一批既有事实。
     */
    public Map<String, Object> receipt(Long actorMemberId, String runId, String nodeId) {
        return receiptOf(resolve(actorMemberId, runId, nodeId));
    }

    /** 纯高级玩法局的只读成绩明细（没有个人路线进度行，跳过/揭示只看局状态）。 */
    public Map<String, Object> receiptAdvanced(Long actorMemberId, String sessionId, String nodeId) {
        return receiptOf(resolveAdvanced(actorMemberId, sessionId, nodeId));
    }

    /**
     * qx-c1 #4(UG-12)后台只读:同一冻结层级 + 同一 {@link PlayHelpReceiptReadModel} 计算,明细与玩家侧一致。
     * 只读,不写任何行;不做玩家准入闸(局已结束也能看),授权由后台权限 business:playhelp:query 负责。
     * 返回 {levels:[{level,type,costPoints,freeQuota}], freeUsesPerRun, receipt};不含提示正文、答案与别名。
     */
    public Map<String, Object> auditReceipt(TopicRouteSession session, CmsTopicNode topicNode) {
        if (session == null || session.getId() == null || session.getMemberId() == null || topicNode == null
                || topicNode.getId() == null || !Objects.equals(topicNode.getTopicId(), session.getTopicId())) {
            throw new HelpRefusal("节点与原局不一致");
        }
        Resolved resolved = frozenResolved(session.getMemberId(), session.getId(), topicNode.getId(), session, topicNode);
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("levels", levelSummary(resolved.specs));
        view.put("freeUsesPerRun", resolved.freeUsesPerRun);
        // 逐笔帮助事实:每局每级唯一事实行,receiptId = play_attempt_log.id(申诉核对用)。不带提示正文。
        List<Map<String, Object>> used = new java.util.ArrayList<>();
        for (LevelSpec spec : resolved.specs) {
            String factKey = PlayHelpUsagePolicy.levelFactKey(resolved.memberId, resolved.scopeKey, spec.level());
            PlayAttemptLog fact = playAttemptLogMapper.selectByIdempotencyKey(factKey);
            if (fact == null || !Objects.equals(fact.getMemberId(), resolved.memberId)
                    || !Integer.valueOf(PlayHelpCostApplication.ATTEMPT_TYPE_HELP).equals(fact.getAttemptType())) continue;
            JsonNode result = null;
            try { result = fact.getResultJson() == null ? null : objectMapper.readTree(fact.getResultJson()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) { result = null; }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("receiptId", fact.getId());
            row.put("level", spec.level());
            row.put("type", spec.type());
            row.put("free", result == null || !result.has("free") ? null : result.path("free").asBoolean());
            row.put("pointsCharged", result == null || !result.has("chargedPoints") ? null : result.path("chargedPoints").asInt());
            row.put("createTime", fact.getCreatedAt());
            used.add(row);
        }
        view.put("helpLines", used);
        view.put("receipt", receiptOf(resolved));
        return view;
    }

    /**
     * qx-c1 #4 主题级配置摘要:按冻结快照逐节点列出层数与代价、整局免费次数。只读;不含提示正文。
     * {@code freeUsesPerRun}: 整数 / null(旧逐层口径);各节点声明冲突时 {@code freeUsesState=CONFLICT}。
     */
    public Map<String, Object> auditConfig(String configSnapshotJson) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        JsonNode snapshot = parseSnapshot(configSnapshotJson);
        if (snapshot == null || !snapshot.isObject()) {
            view.put("state", "NO_SNAPSHOT");
            return view;
        }
        java.util.TreeSet<Long> nodeIds = new java.util.TreeSet<>();
        for (String key : List.of(PlayHelpRunQuotaPolicy.NODE_CONFIGS_KEY, PlayHelpRunQuotaPolicy.NODE_ANSWERS_KEY)) {
            snapshot.path(key).fieldNames().forEachRemaining(id -> {
                long parsed = positiveId(id);
                if (parsed > 0) nodeIds.add(parsed);
            });
        }
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (Long nodeId : nodeIds) {
            JsonNode ladder = frozenLadder(NodeJourneySnapshot.answer(configSnapshotJson, nodeId),
                    frozenNodeConfig(configSnapshotJson, nodeId));
            List<LevelSpec> specs = PlayHelpUsagePolicy.levels(ladder);
            if (specs.isEmpty()) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("nodeId", nodeId);
            row.put("layerCount", specs.size());
            row.put("levels", levelSummary(specs));
            nodes.add(row);
        }
        view.put("state", "OK");
        try {
            view.put("freeUsesPerRun", PlayHelpRunQuotaPolicy.runFreeUses(snapshot));
            view.put("freeUsesState", "OK");
        } catch (IllegalArgumentException conflict) {
            view.put("freeUsesPerRun", null);
            view.put("freeUsesState", "CONFLICT");
        }
        view.put("nodes", nodes);
        return view;
    }

    private static List<Map<String, Object>> levelSummary(List<LevelSpec> specs) {
        List<Map<String, Object>> levels = new java.util.ArrayList<>();
        for (LevelSpec spec : specs) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("level", spec.level());
            row.put("type", spec.type());
            row.put("costPoints", spec.costPoints());
            row.put("freeQuota", spec.freeQuota());
            levels.add(row);
        }
        return levels;
    }

    private Map<String, Object> receiptOf(Resolved resolved) {
        Integer baseScore = baseScoreOf(resolved);
        boolean skipped = isSkipped(resolved);
        boolean revealed = isAnswerRevealed(resolved);
        return playHelpReceiptReadModel.receipt(resolved.memberId, resolved.scope, resolved.ladder,
                baseScore, skipped, revealed);
    }

    /**
     * 本题基准分。
     *
     * <p>只在该节点真的走**既有解谜分链**时给出：线性城市定向 + 模板验真方式为文字/选项（1/3）。
     * 其它玩法（自由收集、纯高级玩法、拍照/扫码等）返回 {@code null}，
     * 明细里就是 {@code NOT_SCORED} —— 不给假分数，也不拿 {@code xp} 冒充解谜分。
     *
     * <p>★ 验真方式优先取**本局冻结的节点答案**（{@link NodeJourneySnapshot#contentTemplate}），
     * 只有真正早于答案冻结的老局（{@link NodeJourneySnapshot#predatesAnswerFreeze}：整份快照
     * 没有 {@code nodeAnswers} 键）才退回当前模板。条目在而字段缺 = 建局时就没有这个字段，
     * 不回退 —— 否则作者在本局开局后改模板，会让同一局的明细在 SCORED / NOT_SCORED 之间跳变，
     * 与实际完成的记分对不上。
     */
    private Integer baseScoreOf(Resolved resolved) {
        if (resolved.node == null || resolved.node.getTemplateId() == null) return null;
        Long activityId = resolved.scope.activityId();
        if (playNodeContext.resolveMode(activityId, resolved.scope.topicId())
                != com.chengyinhub.common.constant.PlayMode.LINEAR_ORIENTEERING) return null;
        com.chengyinhub.business.domain.CmsMemberTemplate frozen =
                NodeJourneySnapshot.contentTemplate(resolved.frozenAnswer);
        boolean legacyRun = resolved.session != null
                && NodeJourneySnapshot.predatesAnswerFreeze(resolved.session.getConfigSnapshotJson());
        com.chengyinhub.business.domain.CmsMemberTemplate template = frozen != null
                ? frozen
                : (legacyRun ? cmsMemberTemplateService.selectCmsMemberTemplateById(resolved.node.getTemplateId()) : null);
        if (template == null || template.getValidationMethod() == null) return null;
        int method = template.getValidationMethod().intValue();
        if (method != 1 && method != 3) return null;
        // 基准分取既有策略在「无提示」档的值（100），降档由 assemble 用同一策略算。
        return Integer.valueOf(com.chengyinhub.business.util.PuzzleScorePolicy.solvedScore(0));
    }

    /**
     * 本局是否已按跳过收尾。**只读本局这一类原局的事实**：
     * <ul>
     *   <li>{@link PlayHelpUsagePolicy.RunKind#ADVANCED_GAME}：局状态 {@code state.skip}；</li>
     *   <li>{@link PlayHelpUsagePolicy.RunKind#TOPIC_ROUTE}：个人进度行的 {@code completion_mode}。</li>
     * </ul>
     *
     * <p>★ 不能两边都查：同一会员可能在同一节点另开过高级玩法局并跳过，
     * 那条 {@code state.skip} 属于**另一个原局**，拿它当路线局的跳过会把
     * 一个正常完成的节点报成「跳过 · 0 分」。
     */
    private boolean isSkipped(Resolved resolved) {
        if (resolved.scope.kind() == PlayHelpUsagePolicy.RunKind.ADVANCED_GAME) {
            return advancedSkipped(resolved);
        }
        return com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SKIPPED
                .equals(persistedCompletionMode(resolved));
    }

    /** 高级玩法局的跳过事实（只在该局确实是高级局时调用）。 */
    private boolean advancedSkipped(Resolved resolved) {
        if (advancedGameRuntimeService == null) return false;
        try {
            return advancedGameRuntimeService.skippedAssistedCompletion(resolved.memberId,
                    resolved.scope.activityId(), resolved.scope.topicId(), resolved.nodeId);
        } catch (RuntimeException unusableSkipFact) {
            return false;
        }
    }

    /** 进度行是否记为「揭示答案」：辅助完成，但不扣满基准分。路线局才有进度行。 */
    private boolean isAnswerRevealed(Resolved resolved) {
        if (resolved.scope.kind() == PlayHelpUsagePolicy.RunKind.ADVANCED_GAME) return false;
        return com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_REVEALED
                .equals(persistedCompletionMode(resolved));
    }
    /**
     * 读本节点本局已落库的完成方式。只读；没有进度行返回 null（尚未完成）。
     * 按 {@code memberId + topicId + activityId + nodeId + playInstance} 精确收窄，
     * 避免把别局/别实例的完成方式读成本局的。
     */
    private String persistedCompletionMode(Resolved resolved) {
        try {
            com.chengyinhub.business.domain.PlayerNodeProgress probe =
                    new com.chengyinhub.business.domain.PlayerNodeProgress();
            probe.setMemberId(resolved.memberId);
            probe.setTopicId(resolved.scope.topicId());
            probe.setActivityId(resolved.scope.activityId());
            probe.setNodeId(resolved.nodeId);
            // 进度行按原局的进度实例落(首局是 default),帮助身份只用于额度/事实分局。
            probe.setPlayInstanceId(resolved.session != null
                    ? resolved.session.getPlayInstanceId() : resolved.scope.playInstance());
            java.util.List<com.chengyinhub.business.domain.PlayerNodeProgress> rows =
                    playerNodeProgressService.selectPlayerNodeProgressList(probe);
            if (rows == null || rows.isEmpty()) return null;
            for (com.chengyinhub.business.domain.PlayerNodeProgress row : rows) {
                if (row == null || row.getStatus() == null || row.getStatus() != 1) continue;
                if (row.getCompletionMode() != null && !row.getCompletionMode().isBlank()) {
                    return row.getCompletionMode();
                }
            }
            return null;
        } catch (RuntimeException unusableProgress) {
            return null;
        }
    }

    // ==================== 纯高级玩法局（game_runtime_session）的帮助入口 ====================

    /**
     * `GET /api/play/help/advanced?sessionId=&nodeId=`:高级玩法局的层级与剩余额度。
     *
     * <p>纯高级玩法局没有 `topic_route_session`,但**规则完全一样** —— 冻结配置里的 `hintLadder`
     * 与整局 `hintFreeUsesPerRun`,同一份 `PlayHelpUsagePolicy`/G7 额度/`PlayHelpContent`。
     * 不新开第二套判定,只换"原局身份"的来源。
     */
    public PlayHelpViewVO viewAdvanced(Long actorMemberId, String sessionId, String nodeId) {
        return viewResolved(resolveAdvanced(actorMemberId, sessionId, nodeId));
    }

    /** `POST /api/play/help/advanced/use`:与 topic_route 局同一条扣减/幂等链。 */
    @Transactional(rollbackFor = Exception.class)
    public PlayHelpUseVO useAdvanced(Long actorMemberId, String sessionId, String nodeId, Integer level,
                                     String action, String requestId) {
        return useResolved(resolveAdvanced(actorMemberId, sessionId, nodeId), level, action, requestId);
    }

    /**
     * 高级玩法局的原局解析:本人可访问的 session、节点必须是该 session 的节点、
     * 局仍在进行中。层级与 N 只从**冻结**配置快照读,不看实时模板。
     */
    private Resolved resolveAdvanced(Long actorMemberId, String sessionId, String nodeId) {
        long memberId = actorMemberId == null ? 0L : actorMemberId;
        if (memberId <= 0) throw new HelpRefusal("请先登录");
        long run = positiveId(sessionId);
        long node = positiveId(nodeId);
        if (run <= 0 || node <= 0) throw new HelpRefusal("参数有误");
        GameRuntimeSession session;
        try {
            session = gameRuntimeSessionMapper.selectAccessibleById(run, memberId);
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("原局状态暂时不可用，请重试", unavailable);
        }
        if (session == null) throw new HelpRefusal("原局不存在或无权访问");
        if (parseSnapshot(session.getStateJson()) != null && parseSnapshot(session.getStateJson()).path("cooperationVersion").asInt() == 1)
            throw new HelpRefusal("合作局请使用 /api/play/help/shared，共享解锁且仅扣实际使用者积分");
        if (!Long.valueOf(node).equals(session.getNodeId())) throw new HelpRefusal("节点与当前原局不一致");
        if (!Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new HelpRefusal("本局已结束，不能使用帮助");
        }
        CmsTopicNode topicNode = cmsTopicNodeMapper.selectCmsTopicNodeById(node);
        if (topicNode == null) throw new HelpRefusal("节点不存在");
        if (!Objects.equals(topicNode.getTopicId(), session.getTopicId())) {
            throw new HelpRefusal("节点与当前原局不一致");
        }
        // kind 由本入口按已核验的 game_runtime_session 赋值；客户端没有任何途径指定它。
        // 原局实例名固定 game:<sessionId>，同一局的多次动作共享同一份额度。
        PlayHelpUsagePolicy.Scope scope = PlayHelpUsagePolicy.Scope.advanced(
                session.getTopicId(), session.getActivityId(), node, session.getId());
        JsonNode snapshot = parseSnapshot(session.getConfigSnapshotJson());
        JsonNode ladder = snapshot == null ? null : snapshot.path("hintLadder");
        Integer configured;
        try {
            configured = PlayHelpRunQuotaPolicy.runFreeUses(snapshot);
        } catch (IllegalArgumentException conflict) {
            throw new HelpRefusal("本局各节点的整局免费提示次数不一致，请作者统一为一个值");
        }
        return new Resolved(memberId, run, node, scope, PlayHelpUsagePolicy.scopeKey(scope),
                snapshot, ladder, PlayHelpUsagePolicy.levels(ladder), configured, null, topicNode);
    }

    private JsonNode parseSnapshot(String configSnapshotJson) {
        if (StringUtils.isBlank(configSnapshotJson)) return null;
        try {
            return objectMapper.readTree(configSnapshotJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unusable) {
            throw new IllegalStateException("原局玩法配置不可用", unusable);
        }
    }

    /**
     * 跳过本题(BE-16 L5 / M.7「跳过记 0 分」)。整条链都在一个事务里:
     * <ol>
     *   <li>作者必须在**冻结**阶梯里配了 SKIP 层,且该节点不是现场凭证类(运行时再判一次);</li>
     *   <li>运行时把本局标成「跳过·辅助完成」(幂等 + 版本 CAS,重发同一 requestId 只回放);</li>
     *   <li>既有完成链写进度:0 分、{@code completion_mode=SKIPPED}、奖励降为探索值+徽章。</li>
     * </ol>
     * 任一步失败整体回滚:不会出现「进度已成但没记跳过」或「记了跳过却发了通过奖励」。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> skip(Long actorMemberId, String runId, String nodeId, String requestId) {
        Resolved resolved = resolve(actorMemberId, runId, nodeId);
        if (StringUtils.isBlank(requestId)) throw new HelpRefusal("缺少请求编号");
        boolean skipConfigured = false;
        for (LevelSpec spec : resolved.specs) {
            if (PlayHelpUsagePolicy.TYPE_SKIP.equals(spec.type()) && spec.usable()) {
                skipConfigured = true;
                break;
            }
        }
        if (!skipConfigured) throw new HelpRefusal("该节点未配置跳过");
        if (resolved.session == null) {
            // 纯高级玩法局:没有个人路线进度行,跳过只落局状态,完成由 completeGate 读 skip 事实。
            return skipAdvanced(resolved, requestId);
        }

        Map<String, Object> sessionView = advancedGameRuntimeService.skip(resolved.memberId,
                resolved.session.getActivityId(), resolved.session.getTopicId(), resolved.nodeId, requestId.trim());
        PlayOriginalRun originalRun = new PlayOriginalRun(resolved.session, resolved.session.getId(), null, null);
        Map<String, Object> progress = playNodeCompletionApplication.advanceProgress(
                resolved.memberId, resolved.node, resolved.session.getActivityId(), null,
                com.chengyinhub.business.util.PuzzleScorePolicy.skippedScore(),
                com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SKIPPED,
                com.chengyinhub.common.constant.RewardPolicy.XP_BADGE_ONLY,
                null, null, null, null, originalRun);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("skipped", true);
        result.put("score", com.chengyinhub.business.util.PuzzleScorePolicy.skippedScore());
        result.put("completionMode", com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SKIPPED);
        result.put("assisted", true);
        result.put("session", sessionView);
        result.put("progress", progress);
        // 跳过回执与只读回执同源：同一份明细，避免两边各算一套。
        result.put("helpReceipt", receiptOf(resolved));
        return result;
    }

    /**
     * 纯高级玩法局的跳过:只落局状态(0 分 + 辅助完成),不写个人节点进度 ——
     * 那一局的完成事实由 `completeGate` 读 {@code state.skip} 得出,不另造一条进度写入路径。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> skipAdvanced(Long actorMemberId, String sessionId, String nodeId, String requestId) {
        return skipAdvanced(resolveAdvanced(actorMemberId, sessionId, nodeId), requestId);
    }

    private Map<String, Object> skipAdvanced(Resolved resolved, String requestId) {
        boolean skipConfigured = false;
        for (LevelSpec spec : resolved.specs) {
            if (PlayHelpUsagePolicy.TYPE_SKIP.equals(spec.type()) && spec.usable()) {
                skipConfigured = true;
                break;
            }
        }
        if (!skipConfigured) throw new HelpRefusal("该节点未配置跳过");
        Map<String, Object> sessionView = advancedGameRuntimeService.skip(resolved.memberId,
                resolved.scope.activityId(), resolved.scope.topicId(), resolved.nodeId, requestId.trim());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("skipped", true);
        result.put("score", com.chengyinhub.business.util.PuzzleScorePolicy.skippedScore());
        result.put("completionMode", com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SKIPPED);
        result.put("assisted", true);
        result.put("session", sessionView);
        result.put("helpReceipt", receiptOf(resolved));
        return result;
    }

    /**
     * 授权只复用既有口径:原局会话按会员取(`topic_route_session.id + member_id`),
     * 节点必须属于该原局主题,再走既有节点准入闸(报名/带领/路线)。不另造一套归属判定。
     */
    private Resolved resolve(Long actorMemberId, String runId, String nodeId) {
        long memberId = actorMemberId == null ? 0L : actorMemberId;
        if (memberId <= 0) throw new HelpRefusal("请先登录");
        long run = positiveId(runId);
        long node = positiveId(nodeId);
        if (run <= 0 || node <= 0) throw new HelpRefusal("参数有误");

        TopicRouteSession session = topicRouteSessionMapper.selectAccessibleById(run, memberId);
        if (session == null) throw new HelpRefusal("原局不存在或无权访问");
        if (!Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus())) {
            throw new HelpRefusal("本局已结束，不能使用帮助");
        }
        CmsTopicNode topicNode = cmsTopicNodeMapper.selectCmsTopicNodeById(node);
        if (topicNode == null) throw new HelpRefusal("节点不存在");
        if (!Objects.equals(topicNode.getTopicId(), session.getTopicId())) {
            throw new HelpRefusal("节点与当前原局不一致");
        }

        String activityId = session.getActivityId() == null || session.getActivityId() <= 0
                ? null : String.valueOf(session.getActivityId());
        String topicId = String.valueOf(session.getTopicId());
        PlayNodeContext.PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, topicNode);
        if (ctx.error != null) {
            Object message = ctx.error.get(AjaxResult.MSG_TAG);
            throw new HelpRefusal(message == null ? "该节点当前不可游玩" : String.valueOf(message));
        }
        long sessionActivity = session.getActivityId() == null ? 0L : session.getActivityId();
        long contextActivity = ctx.actId == null ? 0L : ctx.actId;
        if (!Objects.equals(ctx.topicId, session.getTopicId()) || contextActivity != sessionActivity) {
            throw new HelpRefusal("原局与当前游玩场次不一致");
        }
        String leadError = playNodeContext.leadGateError(ctx.actId, topicNode);
        if (leadError != null) throw new HelpRefusal(leadError);
        String routeError = playNodeContext.routeAccessError(memberId, ctx.actId, ctx.topicId, topicNode, null);
        if (routeError != null) throw new HelpRefusal(routeError);

        return frozenResolved(memberId, run, node, session, topicNode);
    }

    /**
     * 原局冻结层级/额度/作用域的唯一推导(玩家入口与后台只读审计共用,不另算一套)。
     * 不做准入闸,调用方负责归属与权限。
     */
    private Resolved frozenResolved(long memberId, long run, long node, TopicRouteSession session,
                                    CmsTopicNode topicNode) {
        // 原局身份:重玩等非 default 实例原样沿用;首局(自玩/活动场次)用建局时冻结进快照的身份。
        // 旧首局(无该身份)仍然没有帮助身份,照旧拒绝。
        String playInstance = com.chengyinhub.business.service.support.FirstRunHelpIdentity.helpInstance(
                session, parseSnapshot(session.getConfigSnapshotJson()));
        if (playInstance == null) {
            throw new HelpRefusal("原局身份不可用，请重新进入本局");
        }
        PlayHelpUsagePolicy.Scope scope = new PlayHelpUsagePolicy.Scope(session.getTopicId(),
                session.getActivityId(), node, playInstance);
        String scopeKey = PlayHelpUsagePolicy.scopeKey(scope);
        JsonNode frozenAnswer = NodeJourneySnapshot.answer(session.getConfigSnapshotJson(), node);
        // R18 冻结把块配置放在 nodeConfigs[nodeId]，节点答案本身不带 hintLadder。
        // 不从这里取会让新局 help 恒为 enabled=false，而整局 N 却能从同一快照读到。
        JsonNode nodeConfig = frozenNodeConfig(session.getConfigSnapshotJson(), node);
        JsonNode ladder = frozenLadder(frozenAnswer, nodeConfig);
        return new Resolved(memberId, run, node, scope, scopeKey, frozenAnswer, ladder,
                PlayHelpUsagePolicy.levels(ladder),
                runFreeUses(session.getConfigSnapshotJson(), node, nodeConfig, frozenAnswer),
                session, topicNode);
    }

    /**
     * 整局共享免费次数 N —— **一次解析、一局一个值**。
     *
     * <p>解析唯一交给 {@link PlayHelpRunQuotaPolicy#runFreeUses(JsonNode)}:它扫完根层、全部
     * {@code nodeConfigs}、全部 {@code nodeAnswers}(含嵌套 {@code advancedConfigJson}),
     * 任何两处显式声明不同即抛冲突。这里**不再**在整局值为空时回退到"当前节点自己的 N" ——
     * 那正是"按第一个节点初始化另一份额度"的来源。
     *
     * <p>快照缺失(极老的局没有 {@code config_snapshot_json})时,用当前节点这两份冻结事实拼一个
     * 单节点快照走同一个解析器:冲突判定与"新阶梯默认 0 / 旧局 null"的口径完全一致。
     *
     * @return 整局 N;{@code 0} = 新阶梯局且作者未给免费次数;{@code null} = 真正旧局(逐层旧语义)
     */
    private Integer runFreeUses(String configSnapshotJson, long nodeId,
                                JsonNode nodeConfig, JsonNode frozenAnswer) {
        JsonNode snapshot = null;
        if (StringUtils.isNotBlank(configSnapshotJson)) {
            try {
                snapshot = objectMapper.readTree(configSnapshotJson);
            } catch (com.fasterxml.jackson.core.JsonProcessingException unusableSnapshot) {
                throw new IllegalStateException("原局内容快照不可用", unusableSnapshot);
            }
        }
        if (snapshot == null || !snapshot.isObject()) {
            // 没有整份快照:只能看得到本节点这两份冻结事实,但仍用同一个解析器判冲突与默认值。
            com.fasterxml.jackson.databind.node.ObjectNode synthetic = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode configs =
                    synthetic.putObject(PlayHelpRunQuotaPolicy.NODE_CONFIGS_KEY);
            com.fasterxml.jackson.databind.node.ObjectNode answers =
                    synthetic.putObject(PlayHelpRunQuotaPolicy.NODE_ANSWERS_KEY);
            if (nodeConfig != null && nodeConfig.isObject()) configs.set(String.valueOf(nodeId), nodeConfig);
            if (frozenAnswer != null && frozenAnswer.isObject()) answers.set(String.valueOf(nodeId), frozenAnswer);
            snapshot = synthetic;
        }
        try {
            return PlayHelpRunQuotaPolicy.runFreeUses(snapshot);
        } catch (IllegalArgumentException conflict) {
            // 声明不一致是配置错误:如实报给玩家/作者,不猜一个值继续跑。
            throw new HelpRefusal("本局各节点的整局免费提示次数不一致，请作者统一为一个值");
        }
    }

    /**
     * 层级只从**本局冻结内容**里取，按冻结形状先后：
     * <ol>
     *   <li>旧冻结：阶梯直接写在节点答案里；</li>
     *   <li>旧冻结：节点答案带整份进阶配置串，则从该串取 —— 串里没有阶梯就是作者没配，不再往别处找；</li>
     *   <li>R18 冻结：块配置在 {@code nodeConfigs[nodeId]}。</li>
     * </ol>
     *
     * <p>实时模板一律不看：开局后作者改配置不得改变本局代价。冻结配置显式没有阶梯时返回
     * {@code null}（旧行为），不回退到实时作者配置，也不借用别的节点。
     */
    private JsonNode frozenLadder(JsonNode frozenAnswer, JsonNode nodeConfig) {
        if (frozenAnswer != null && frozenAnswer.path("hintLadder").isArray()) {
            return frozenAnswer.path("hintLadder");
        }
        JsonNode advanced = frozenAnswer == null ? null : frozenAnswer.get("advancedConfigJson");
        if (advanced != null && advanced.isTextual() && !StringUtils.isBlank(advanced.asText())) {
            JsonNode parsed = parseSnapshot(advanced.asText());
            JsonNode legacy = parsed == null ? null : parsed.get("hintLadder");
            return legacy != null && legacy.isArray() ? legacy : null;
        }
        JsonNode ladder = nodeConfig == null ? null : nodeConfig.get("hintLadder");
        return ladder != null && ladder.isArray() ? ladder : null;
    }

    /**
     * 本节点的冻结块配置：与 {@link PlayHelpRunQuotaPolicy#runFreeUses(JsonNode)} 及
     * {@code ContentCapabilityRequirements} 同一份 {@code nodeConfigs} 来源。缺失返回 {@code null}。
     */
    private JsonNode frozenNodeConfig(String configSnapshotJson, long nodeId) {
        if (StringUtils.isBlank(configSnapshotJson)) return null;
        JsonNode snapshot = parseSnapshot(configSnapshotJson);
        if (snapshot == null) return null;
        JsonNode row = snapshot.path(PlayHelpRunQuotaPolicy.NODE_CONFIGS_KEY).path(String.valueOf(nodeId));
        return row.isObject() ? row : null;
    }
    /**
     * 已解锁层级按 G5 的每局每级事实键回查既有流水:同一原局同一节点同一层,只有一行事实;
     * 不新增表,也不从客户端读已解锁状态。
     */
    private Set<Integer> unlockedLevels(Resolved resolved) {
        Set<Integer> unlocked = new HashSet<>();
        for (LevelSpec spec : resolved.specs) {
            String factKey = PlayHelpUsagePolicy.levelFactKey(resolved.memberId, resolved.scopeKey, spec.level());
            PlayAttemptLog fact = playAttemptLogMapper.selectByIdempotencyKey(factKey);
            if (fact == null) continue;
            if (!Objects.equals(fact.getMemberId(), resolved.memberId)) continue;
            if (!Integer.valueOf(PlayHelpCostApplication.ATTEMPT_TYPE_HELP).equals(fact.getAttemptType())) continue;
            unlocked.add(spec.level());
        }
        return unlocked;
    }

    private static LevelSpec levelOf(List<LevelSpec> specs, int level) {
        for (LevelSpec spec : specs) if (spec.level() == level) return spec;
        return null;
    }

    private static JsonNode ladderEntry(JsonNode ladder, int level) {
        if (ladder == null || !ladder.isArray()) return null;
        for (JsonNode entry : ladder) if (entry.path("level").asInt(0) == level) return entry;
        return null;
    }

    private static long positiveId(String raw) {
        if (StringUtils.isBlank(raw)) return 0L;
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : 0L;
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    private static final class Resolved {
        private final long memberId;
        private final long runId;
        private final long nodeId;
        /** 原局作用域:整局额度、每局每级事实、请求回执都用它,不再各拼一份 key。 */
        private final PlayHelpUsagePolicy.Scope scope;
        private final String scopeKey;
        private final JsonNode frozenAnswer;
        private final JsonNode ladder;
        private final List<LevelSpec> specs;
        /** 冻结的整局共享免费次数 N；{@code null} = 本局未声明，沿用旧逐层口径。 */
        private final Integer freeUsesPerRun;
        /** 原局会话与节点:跳过要按同一份原局身份走既有完成链,不能只靠 runId/nodeId 重查。 */
        private final TopicRouteSession session;
        private final CmsTopicNode node;

        private Resolved(long memberId, long runId, long nodeId, PlayHelpUsagePolicy.Scope scope,
                         String scopeKey, JsonNode frozenAnswer, JsonNode ladder, List<LevelSpec> specs,
                         Integer freeUsesPerRun, TopicRouteSession session, CmsTopicNode node) {
            this.memberId = memberId;
            this.runId = runId;
            this.nodeId = nodeId;
            this.scope = scope;
            this.scopeKey = scopeKey;
            this.frozenAnswer = frozenAnswer;
            this.ladder = ladder;
            this.specs = specs;
            this.freeUsesPerRun = freeUsesPerRun;
            this.session = session;
            this.node = node;
        }
    }

    /** 明确的业务拒绝(4xx 文案),与意外异常区分;必须穿过事务边界交给控制器。 */
    static class HelpRefusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        HelpRefusal(String message) {
            super(message);
        }
    }
}
