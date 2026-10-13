package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayPredictService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.support.PredictSettlement;
import com.chengyinhub.business.service.support.RewardRulesValidator;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 竞猜结算。
 *
 * <p>发奖复用模板上的**条件奖励**:商家给 {@code PREDICT_WON} 挂一份,自己决定发不发、
 * 发什么。平台不替商家兜底 —— 见 voidExpiredRounds 的注释。
 */
@Service
public class PlayPredictServiceImpl implements IPlayPredictService {

    private static final Logger logger = LoggerFactory.getLogger(PlayPredictServiceImpl.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 猜中的结果码。商家在模板奖励设置里挂这个码,就能配「猜中才发」的奖 */
    public static final String OUTCOME_WON = "PREDICT_WON";

    /** 一次最多作废多少轮,免得积压时一口气扫爆 */
    private static final int VOID_BATCH = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RewardRulesValidator rewardRulesValidator = new RewardRulesValidator();

    @Autowired
    private PlayPredictMapper predictMapper;

    @Autowired
    private CmsTopicNodeMapper nodeMapper;

    @Autowired
    private CmsMemberTemplateMapper templateMapper;

    @Autowired
    private IGrowthService growthService;

    @Autowired
    private ISmsCouponHistoryService couponHistoryService;

    /**
     * 商家给出答案并结算一轮。
     *
     * <p>★ 整轮结算必须在一个事务里:轮次 CAS({@code settleRound})、逐注落状态
     * ({@code markBetSettled})、发成长值/券,要么全成、要么全不成。
     *
     * <p>分开提交时,进程或连接在中间断掉会留下**半结算孤儿注** —— 轮次已标结算,
     * 但后面还有注停在 {@code reward_status=0};而重跑会被 {@code settleRound} 的
     * {@code settle_status=0} 条件更新拒掉(「已经结算过了」),商家待办也不再列这一轮:
     * 既没人发奖,也没有任何信号。放进事务后失败即整体回滚,重跑从零开始,
     * 配合 {@code markBetSettled} 的 {@code reward_status=0} 条件更新,每注恰好结一次。
     *
     * <p>为什么不用「重扫补发」兜底:现有写序是先落 {@code reward_status} 再发奖,
     * 中断恰好会落在「状态已改、奖没发」的窗口里,按 {@code reward_status=0} 重扫
     * 看不见它;要兜住得给成长值/发券新增幂等键与中间态,改动远大于把结算收进一个事务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle(Long nodeId, Date playDay, String settledOption, Long operatorId) {
        if (nodeId == null || nodeId <= 0 || playDay == null) {
            throw new IllegalArgumentException("参数有误");
        }
        if (StringUtils.isBlank(settledOption)) {
            throw new IllegalArgumentException("请选出正确答案");
        }
        CmsMemberTemplate template = templateOf(nodeId);
        JsonNode predict = predictConfig(template);
        if (predict == null) {
            throw new IllegalArgumentException("这个点位不是竞猜玩法");
        }
        if (findOption(predict, settledOption) == null) {
            throw new IllegalArgumentException("这个选项不在本题里");
        }
        if (predictMapper.selectResult(nodeId, playDay) == null) {
            throw new IllegalArgumentException("这一轮不存在");
        }

        // 条件更新:settle_status 从 0 走到 1 只能一次,命中 0 行即为重复结算
        int settled = predictMapper.settleRound(nodeId, playDay, settledOption,
                PlayPredictResult.SETTLE_DONE, operatorId, new Date());
        if (settled == 0) {
            throw new IllegalStateException("这一轮已经结算过了");
        }

        List<PlayPredictBet> bets = predictMapper.selectBetsForRound(nodeId, playDay);
        List<PredictSettlement.Outcome> outcomes = PredictSettlement.settle(bets, settledOption);

        int winners = 0;
        int coupons = 0;
        Date now = new Date();
        JsonNode wonRule = rewardRulesValidator.ruleFor(template.getRewardRulesJson(), OUTCOME_WON);
        for (PredictSettlement.Outcome outcome : outcomes) {
            PlayPredictBet bet = outcome.getBet();
            // 同一注只结一次:reward_status=0 的条件更新兜住,命中 0 行说明别人先结了
            if (predictMapper.markBetSettled(bet.getId(), outcome.getRewardStatus(), now) == 0) {
                continue;
            }
            if (!outcome.isWinner()) continue;
            winners++;
            if (wonRule == null) continue;
            int xp = wonRule.path("xp").asInt(0);
            if (xp > 0) growthService.addExp(bet.getMemberId(), xp, "竞猜猜中");
            long couponId = wonRule.path("couponId").asLong(0L);
            // C-30:券已失效(3)/已停发(4) 不可发放 → 跳过发券,结算照常;只有券不存在/库存耗尽才需人工补发
            if (couponId > 0 && couponHistoryService.isCouponNotIssuable(couponId)) {
                logger.warn("COUPON_DISABLED_SKIP caller=predict member={} coupon={} node={} day={}",
                        bet.getMemberId(), couponId, nodeId, playDay);
            } else if (couponId > 0 && couponHistoryService.grantCouponById(bet.getMemberId(), couponId)) {
                coupons++;
            } else if (couponId > 0) {
                // 券没落袋不能静默:商家配了奖,玩家该拿没拿到
                logger.error("竞猜发券失败(券不存在或库存耗尽) member={} coupon={} node={} day={},需人工补发",
                        bet.getMemberId(), couponId, nodeId, playDay);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("settledOption", settledOption);
        summary.put("totalBets", bets == null ? 0 : bets.size());
        summary.put("winners", winners);
        summary.put("couponsGranted", coupons);
        summary.put("rewardConfigured", wonRule != null);
        return summary;
    }

    @Override
    public int voidExpiredRounds(int graceHours) {
        int grace = Math.max(graceHours, 1);
        // 按自然日粗算:play_day 早于「今天减去 grace 折算的天数」的都算过期
        LocalDate cutoff = Instant.now().atZone(ZONE).toLocalDate().minusDays(Math.max(1, grace / 24));
        List<PlayPredictResult> stale =
                predictMapper.selectStaleRounds(java.sql.Date.valueOf(cutoff), VOID_BATCH);
        if (stale == null || stale.isEmpty()) return 0;

        Date now = new Date();
        int voided = 0;
        for (PlayPredictResult round : stale) {
            if (predictMapper.settleRound(round.getNodeId(), round.getPlayDay(), null,
                    PlayPredictResult.SETTLE_VOID, null, now) == 0) {
                continue;   // 商家赶在任务之前结了,让他的结算生效
            }
            List<PlayPredictBet> bets =
                    predictMapper.selectBetsForRound(round.getNodeId(), round.getPlayDay());
            for (PredictSettlement.Outcome outcome : PredictSettlement.voidRound(bets)) {
                predictMapper.markBetSettled(outcome.getBet().getId(), outcome.getRewardStatus(), now);
            }
            voided++;
            // 作废是商家没结算造成的,值得留一条记录 —— 不然这事没人知道
            logger.warn("竞猜轮次作废 node={} day={}:商家超过 {} 小时未给出答案,本轮不发奖",
                    round.getNodeId(), round.getPlayDay(), grace);
        }
        return voided;
    }

    @Override
    public List<PlayPredictResult> pendingRounds(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return Collections.emptyList();
        return predictMapper.selectPendingRounds(nodeIds);
    }

    @Override
    public List<Map<String, Object>> pendingInbox(Long ownerMemberId, int graceHours) {
        List<Map<String, Object>> inbox = new ArrayList<>();
        if (ownerMemberId == null || ownerMemberId <= 0) return inbox;

        CmsTopicNode probe = new CmsTopicNode();
        probe.setMerchantMemberId(ownerMemberId);
        List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(probe);
        if (nodes == null || nodes.isEmpty()) return inbox;
        Map<Long, CmsTopicNode> byId = new LinkedHashMap<>();
        for (CmsTopicNode node : nodes) {
            if (node != null && node.getId() != null) byId.put(node.getId(), node);
        }
        if (byId.isEmpty()) return inbox;

        List<PlayPredictResult> rounds =
                predictMapper.selectPendingRounds(new ArrayList<>(byId.keySet()));
        if (rounds == null || rounds.isEmpty()) return inbox;

        LocalDate today = Instant.now().atZone(ZONE).toLocalDate();
        // 与 voidExpiredRounds 同一条口径:play_day 早于「今天减 grace 折算天数」的会被作废
        int graceDays = Math.max(1, Math.max(graceHours, 1) / 24);
        for (PlayPredictResult round : rounds) {
            CmsTopicNode node = byId.get(round.getNodeId());
            if (node == null || round.getPlayDay() == null) continue;
            JsonNode predict;
            try {
                predict = predictConfig(templateOf(round.getNodeId()));
            } catch (ServiceException e) {
                continue;                       // 模板被删了就别让整条待办跟着挂
            }
            if (predict == null) continue;      // 这个点位早就不玩竞猜了

            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : predict.path("options")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", option.path("key").asText(""));
                item.put("label", option.path("label").asText(""));
                options.add(item);
            }
            List<PlayPredictBet> bets =
                    predictMapper.selectBetsForRound(round.getNodeId(), round.getPlayDay());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", round.getNodeId());
            row.put("nodeName", StringUtils.defaultString(node.getName()));
            row.put("topicId", node.getTopicId());
            /* ⚠️ playDay 在领域对象上是 java.util.Date,运行时其实是 java.sql.Date。
               直接 toString 只在后者成立;而 java.sql.Date 又不支持 toInstant()。
               所以统一按毫秒重新包一层,两种都稳。 */
            LocalDate day = new java.sql.Date(round.getPlayDay().getTime()).toLocalDate();
            row.put("playDay", day.toString());
            row.put("question", predict.path("question").asText(""));
            row.put("options", options);
            row.put("betCount", bets == null ? 0 : bets.size());
            /* 还剩几天。0 = 今天不给答案,明天这一轮就作废、谁都拿不到奖。
               负数不可能出现:作废任务扫过之后这一轮就不在待办里了。 */
            long passed = ChronoUnit.DAYS.between(day, today);
            row.put("daysLeft", Math.max(0L, graceDays - passed));
            inbox.add(row);
        }
        return inbox;
    }

    private CmsMemberTemplate templateOf(Long nodeId) {
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTemplateId() == null || node.getTemplateId() <= 0) {
            throw new ServiceException("点位不存在或没有玩法模板");
        }
        CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(node.getTemplateId());
        if (template == null) throw new ServiceException("玩法模板不存在");
        return template;
    }

    private JsonNode predictConfig(CmsMemberTemplate template) {
        if (StringUtils.isBlank(template.getAdvancedConfigJson())) return null;
        try {
            JsonNode root = objectMapper.readTree(template.getAdvancedConfigJson());
            JsonNode predict = root.path("predict");
            return predict.isObject() && predict.path("enabled").asBoolean(false) ? predict : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode findOption(JsonNode predict, String key) {
        for (JsonNode option : predict.path("options")) {
            if (key.equals(option.path("key").asText(""))) return option;
        }
        return null;
    }
}
