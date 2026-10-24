package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayBingoCard;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayBingoCardMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IPlayBingoService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.support.BingoCard;
import com.chengyinhub.business.service.support.BingoCells;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宾果任务格。
 *
 * <p>⚠️ 本服务**不带 @Transactional**,而且调用方要把它包在 try-catch 里。
 * 宾果是主流程之外的一层:它出问题不能让节点完成回滚,否则玩家会卡在这一站
 * 反复重试,而原因是一个跟他这次动作无关的九宫格。
 * 数据库瞬态错误必须透传：原事务可能已被数据库回滚，不能继续提交完成回执。
 */
@Service
public class PlayBingoServiceImpl implements IPlayBingoService {

    private static final Logger logger = LoggerFactory.getLogger(PlayBingoServiceImpl.class);

    /** 三档奖的探索值。券与勋章不在这里发 —— 那是商家的东西,宾果是平台出的 */
    private static final int XP_FIRST = 20;
    private static final int XP_LINE = 60;
    private static final int XP_FULL = 300;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PlayBingoCardMapper cardMapper;

    @Autowired
    private PlayerNodeProgressMapper progressMapper;

    @Autowired
    private IGrowthService growthService;

    @Autowired
    private CmsTopicMapper topicMapper;

    @Autowired
    private ISmsCouponHistoryService couponHistoryService;

    private final TopicCompletionRulePolicy topicRulePolicy = new TopicCompletionRulePolicy();

    @Override
    public PlayBingoCard onNodeDone(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) return null;

        // 九格的点亮条件是内置的、跨节点的,商家零输入;商家能改的只有每格的名称与奖励。
        String rule = topicRule(topicId);

        PlayBingoCard card = ensureCard(memberId, topicId, rule);
        if (card == null) return null;

        int shouldBe = BingoCells.evaluate(loadProgress(memberId, topicId));
        Date now = new Date();

        // 逐格填。库里做位或,不是读改写 —— 并发下两个事件填不同格子,读改写会丢一个。
        for (int position = 0; position < BingoCard.SIZE; position++) {
            if (!BingoCard.isFilled(shouldBe, position)) continue;
            if (BingoCard.isFilled(value(card.getFilledMask()), position)) continue;
            cardMapper.fillCell(memberId, topicId, 1 << position, now);
        }

        // 回读一次拿到真实的 filled_mask(并发下别人可能也填了格)
        PlayBingoCard fresh = cardMapper.selectCard(memberId, topicId);
        if (fresh == null) return card;

        int unlocked = BingoCard.newlyUnlocked(value(fresh.getFilledMask()), value(fresh.getRewardMask()));
        if (unlocked != 0) {
            // 条件更新:该档已记过就命中 0 行,奖不会重复发
            if (cardMapper.markRewarded(memberId, topicId, unlocked, now) > 0) {
                grantTiers(memberId, unlocked);
                grantCellRewards(memberId, unlocked, rule);
            }
            fresh = cardMapper.selectCard(memberId, topicId);
        }
        return fresh;
    }

    @Override
    public PlayBingoCard view(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) return null;
        return cardMapper.selectCard(memberId, topicId);
    }

    /**
     * 玩家那一屏的九宫格(运行期 {@code playKit.bingo} 段)。
     *
     * <p>键名与前端 cy-playkit-bingo 钉死,一个都不能改:
     * {@code title / labels / cellSpecs[{t,how}] / filledPositions / lineReward / fullReward}。
     * 少一个键 = 那一屏拿不到东西,而且不报错。
     *
     * <p>★ 只呈现、不判定:点亮发生在 {@link #onNodeDone}(节点完成时旁路刷),
     * 这里只是把那件事的结果端给人看。
     */
    @Override
    public Map<String, Object> playKitView(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) return null;
        String rule = topicRule(topicId);
        if (!topicRulePolicy.bingoEnabled(rule)) return null;   // 没开 = 没有这一段,不是九个空格子

        // 没有卡只说明他还没在这个主题点亮过任何一格,棋盘照样得给人看
        PlayBingoCard card = cardMapper.selectCard(memberId, topicId);
        List<String> names = cellNames(rule, card);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", topicRulePolicy.bingoText(rule, "title"));
        view.put("labels", names);
        view.put("cellSpecs", cellSpecs(names));
        view.put("filledPositions", card == null ? new ArrayList<Integer>()
                : BingoCard.filledPositions(value(card.getFilledMask())));
        view.put("lineReward", topicRulePolicy.bingoText(rule, "lineReward"));
        view.put("fullReward", topicRulePolicy.bingoText(rule, "fullReward"));
        return view;
    }

    /**
     * 九格的显示名,位序 0..8,一共三层覆盖:平台内置文案 → 商家现在配的名 → 这张卡开卡时冻住的名。
     *
     * <p>★ 卡上的快照压过主题当前配置是故意的:玩到一半换格子意思,玩家只会觉得系统在耍他
     * (与 {@link #cellsJson} 冻进快照列的那一份同一条口径)。快照读不动或少了格就退回上一层,
     * 九格一个都不能空。
     */
    private List<String> cellNames(String rule, PlayBingoCard card) {
        Map<Integer, String> conditions = conditionsByPosition();
        String[] names = new String[BingoCard.SIZE];
        for (int position = 0; position < names.length; position++) {
            names[position] = conditions.getOrDefault(position, "");
        }
        List<String> custom = rule == null ? new ArrayList<>() : topicRulePolicy.bingoCellLabels(rule);
        for (int position = 0; position < custom.size() && position < names.length; position++) {
            if (!custom.get(position).isEmpty()) names[position] = custom.get(position);
        }
        applyFrozenCells(card, names);
        return Arrays.asList(names);
    }

    /** 每格两件事:叫什么(t)、怎么点亮(how)。how 用平台内置那句 —— 商家改的是名字,不是条件。 */
    private List<Map<String, Object>> cellSpecs(List<String> names) {
        Map<Integer, String> conditions = conditionsByPosition();
        List<Map<String, Object>> specs = new ArrayList<>();
        for (int position = 0; position < BingoCard.SIZE; position++) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("t", names.get(position));
            spec.put("how", conditions.getOrDefault(position, ""));
            specs.add(spec);
        }
        return specs;
    }

    /** 位序 → 平台内置的点亮条件。九格的达成判定在 {@link BingoCells},这里只是把它说给人听。 */
    private Map<Integer, String> conditionsByPosition() {
        Map<Integer, String> conditions = new LinkedHashMap<>();
        for (BingoCells.Cell cell : BingoCells.cells()) {
            if (cell.getPosition() >= 0 && cell.getPosition() < BingoCard.SIZE) {
                conditions.put(cell.getPosition(), cell.getLabel());
            }
        }
        return conditions;
    }

    /** 把卡上冻住的快照盖进 names。读不动就当没冻结,回落上一层 —— 这一屏不能空。 */
    private void applyFrozenCells(PlayBingoCard card, String[] names) {
        if (card == null || card.getCellsJson() == null || card.getCellsJson().isEmpty()) return;
        try {
            JsonNode cells = objectMapper.readTree(card.getCellsJson());
            if (!cells.isArray()) return;
            int index = 0;
            for (JsonNode cell : cells) {
                int position = cell.path("pos").asInt(index);
                index++;
                String label = cell.path("label").asText("");
                if (position >= 0 && position < names.length && !label.isEmpty()) names[position] = label;
            }
        } catch (Exception e) {
            logger.warn("宾果格子快照读不动 member={} topic={},回落内置格子",
                    card.getMemberId(), card.getTopicId(), e);
        }
    }

    /** 主题那份 complete_rule_json。读不出来就当没配 —— 卡不能因为配置读不动就不开。 */
    private String topicRule(Long topicId) {
        try {
            CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
            return topic == null ? null : topic.getCompleteRuleJson();
        } catch (TransientDataAccessException failure) {
            throw failure;
        } catch (Exception e) {
            logger.warn("宾果配置读取失败 topic={},按未配置处理", topicId, e);
            return null;
        }
    }

    /**
     * 每格自己的奖:券 + 文字反馈。
     *
     * <p>幂等靠的是上面那句 markRewarded —— 该格的奖励位已记过就命中 0 行,整段不会再进来。
     * 券没落袋不能静默:商家配了奖、玩家该拿没拿到,得留一条能查的记录。
     */
    private void grantCellRewards(Long memberId, int unlocked, String rule) {
        if (rule == null || rule.isEmpty()) return;
        for (int position = 0; position < BingoCard.SIZE; position++) {
            if ((unlocked & BingoCard.cellRewardBit(position)) == 0) continue;
            long couponId = topicRulePolicy.bingoCellCouponId(rule, position);
            if (couponId <= 0) continue;
            try {
                // C-30:券已失效(3)/已停发(4) 不可发放 → 跳过发券,格子奖励位已记过不回退;
                // 只有券不存在/库存耗尽才是需要人工补发的失败
                if (couponHistoryService.isCouponNotIssuable(couponId)) {
                    logger.warn("COUPON_DISABLED_SKIP caller=bingoCell member={} coupon={} pos={}",
                            memberId, couponId, position);
                } else if (!couponHistoryService.grantCouponById(memberId, couponId)) {
                    logger.error("宾果格子发券失败(券不存在或库存耗尽) member={} coupon={} pos={},需人工补发",
                            memberId, couponId, position);
                }
            } catch (TransientDataAccessException failure) {
                throw failure;
            } catch (Exception e) {
                logger.error("宾果格子发券异常 member={} coupon={} pos={}", memberId, couponId, position, e);
            }
        }
    }

    /** 开卡:第一次玩这个主题时建。并发下撞唯一键就重查,不是错误。 */
    private PlayBingoCard ensureCard(Long memberId, Long topicId, String rule) {
        PlayBingoCard card = cardMapper.selectCard(memberId, topicId);
        if (card != null) return card;

        PlayBingoCard fresh = new PlayBingoCard();
        fresh.setMemberId(memberId);
        fresh.setTopicId(topicId);
        fresh.setCellsJson(cellsJson(rule));
        fresh.setFilledMask(0);
        fresh.setRewardMask(0);
        fresh.setCreateTime(new Date());
        try {
            cardMapper.insertCard(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            return cardMapper.selectCard(memberId, topicId);
        }
    }

    /**
     * 格子定义在开卡时冻结进这一列:之后改 BingoCells、或商家改了主题配置,
     * 都不该动已经开出去的卡 —— 玩到一半格子换意思,玩家只会觉得系统在耍他。
     *
     * <p>label 用商家配的;没配或配了空串就退回内置文案,不能让玩家看到九个空格子。
     */
    private String cellsJson(String rule) {
        List<String> custom = rule == null ? new ArrayList<>() : topicRulePolicy.bingoCellLabels(rule);
        ArrayNode array = objectMapper.createArrayNode();
        for (BingoCells.Cell cell : BingoCells.cells()) {
            ObjectNode node = array.addObject();
            int position = cell.getPosition();
            String label = position < custom.size() ? custom.get(position) : "";
            node.put("pos", position);
            node.put("code", cell.getCode());
            node.put("target", cell.getTarget());
            node.put("label", label.isEmpty() ? cell.getLabel() : label);
            if (rule != null) {
                String feedback = topicRulePolicy.bingoCellFeedback(rule, position);
                if (!feedback.isEmpty()) node.put("feedbackText", feedback);
            }
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            // 走不到:内容全是自家造的。真走到了也不能让开卡失败
            logger.error("宾果格子序列化失败", e);
            return "[]";
        }
    }

    private BingoCells.Progress loadProgress(Long memberId, Long topicId) {
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        probe.setStatus(1);
        List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(probe);
        List<Long> nodeIds = new ArrayList<>();
        List<Date> times = new ArrayList<>();
        if (rows != null) {
            for (PlayerNodeProgress row : rows) {
                // R10-09 收尾:只认 status==1 正式完成。mapper 不吃 probe.status,必须在 Java 侧过滤,
                // 否则到店/凭证 status=0 行会点亮格子并在下次节点完成时提前解锁宾果档位(发探索值)。
                if (!Integer.valueOf(1).equals(row.getStatus())) continue;
                if (row.getNodeId() != null) nodeIds.add(row.getNodeId());
                if (row.getCheckinTime() != null) times.add(row.getCheckinTime());
            }
        }
        return new BingoCells.Progress(nodeIds, times);
    }

    /** 只发探索值。券与勋章是商家配的东西,宾果的格子商家一个字没填,不该动他的券。 */
    private void grantTiers(Long memberId, int unlocked) {
        int xp = 0;
        if ((unlocked & BingoCard.REWARD_FIRST) != 0) xp += XP_FIRST;
        if ((unlocked & BingoCard.REWARD_LINE) != 0) xp += XP_LINE;
        if ((unlocked & BingoCard.REWARD_FULL) != 0) xp += XP_FULL;
        if (xp > 0) growthService.addExp(memberId, xp, "宾果任务格");
    }

    private int value(Integer number) { return number == null ? 0 : number; }
}
