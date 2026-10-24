package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Closed gameplay-judgement subsystem. No mapper, transaction, runtime callback, reward or charge capability.
 * Frozen config/raw input/current provider objects enter; proposed JSON/local score/choice/evidence leave.
 * "awarded" remains the original per-run JSON flag, not a member balance/coupon grant.
 */
public final class AdvancedActionJudgement {
    private final ObjectMapper objectMapper;
    private final AdvancedGameClock clock;
    private final AdvancedGameRandom random;
    private final LongSupplier attemptIdSource;

    AdvancedActionJudgement(ObjectMapper objectMapper, AdvancedGameClock clock,
                            AdvancedGameRandom random, LongSupplier attemptIdSource) {
        this.objectMapper = objectMapper; this.clock = clock;
        this.random = random; this.attemptIdSource = attemptIdSource;
    }

    enum Action {
        SUBMIT_BLIND_TASTE,
        SUBMIT_ESTIMATE,
        SUBMIT_PRICE_PAIR,
        SUBMIT_HIDDEN_OBJECT,
        DRAW,
        FLIP_COIN,
        ROLL_DICE,
        START_CHALLENGE,
        SUBMIT_REACTION,
        SUBMIT_BALL_SHAKE,
        SUBMIT_QUIET_HOLD,
        SUBMIT_COMPASS,
        SUBMIT_SHOUT,
        SUBMIT_COUNTDOWN,
        SUBMIT_STOPWATCH,
        SUBMIT_QA,
        SUBMIT_SORT,
        SUBMIT_MATCH,
        SUBMIT_CLASSIFY,
        SUBMIT_COMPARE,
        SUBMIT_TYPE_IN
    }
    record Input(JsonNode config, ObjectNode state, Map<String,Object> payload, Integer score, Long memberId) {}
    record ChoiceRef(String stepOrKit, String optionId) {}
    record PhotoEvidence(String imageUrl, Long memberId) {}
    record Plan(ObjectNode state, Integer score, boolean scoreAssigned, List<ChoiceRef> choices, PhotoEvidence photoEvidence) {}

    private static final class ScoreChange {
        private Integer score;
        private boolean scoreAssigned;
        private PhotoEvidence photoEvidence;
        private ScoreChange(Integer score) { this.score = score; }
        private Integer getScore() { return score; }
        private void setScore(Integer score) { this.score = score; this.scoreAssigned = true; }
    }

    static boolean handles(String action) {
        try { Action.valueOf(action); return true; }
        catch (IllegalArgumentException unsupported) { return false; }
    }

    Plan evaluate(Action action, Input input) {
        JsonNode config = input.config();
        ObjectNode state = input.state().deepCopy();
        Map<String,Object> payload = input.payload();
        ScoreChange score = new ScoreChange(input.score());
        List<ChoiceRef> choices = new ArrayList<>();
        switch (action) {
            case SUBMIT_BLIND_TASTE -> submitBlindTaste(config, state, payload, score);
            case SUBMIT_ESTIMATE -> submitEstimate(config, state, payload, score);
            case SUBMIT_PRICE_PAIR -> addIfPresent(choices, submitPricePair(config, state, payload, score));
            case SUBMIT_HIDDEN_OBJECT -> addIfPresent(choices, submitHiddenObject(config, state, payload, score));
            case DRAW -> draw(config, state);
            case FLIP_COIN -> flipCoin(config, state);
            case ROLL_DICE -> rollDice(config, state);
            case START_CHALLENGE -> startChallenge(config, state, payload, input.memberId());
            case SUBMIT_REACTION -> submitReaction(config, state, payload);
            case SUBMIT_BALL_SHAKE -> submitBallShake(config, state, payload);
            case SUBMIT_QUIET_HOLD -> submitQuietHold(config, state, payload);
            case SUBMIT_COMPASS -> submitCompass(config, state, payload, score);
            case SUBMIT_SHOUT -> submitShout(config, state, payload);
            case SUBMIT_COUNTDOWN -> submitCountdown(config, state);
            case SUBMIT_STOPWATCH -> submitStopwatch(config, state, payload);
            case SUBMIT_QA -> choices.addAll(submitQa(config, state, payload, score, input.memberId()));
            case SUBMIT_SORT -> addIfPresent(choices, submitSort(config, state, payload, score));
            case SUBMIT_MATCH -> addIfPresent(choices, submitMatch(config, state, payload, score));
            case SUBMIT_CLASSIFY -> addIfPresent(choices, submitClassify(config, state, payload, score));
            case SUBMIT_COMPARE -> submitCompare(config, state, payload, score);
            case SUBMIT_TYPE_IN -> submitTypeIn(config, state, payload, score);
        }
        return new Plan(state, score.getScore(), score.scoreAssigned, List.copyOf(choices), score.photoEvidence);
    }

    /* 五个「设备报数」玩法的反作弊常量。客户端报的毫秒数是设备时钟算的,而设备时钟玩家改得动,
       所以每一条成绩都要拿 START_CHALLENGE 记的**服务器时间**复核一遍。 */
    /** 人的反应极限。比这还快的只能是提前按下去的 */
    private static final long MIN_HUMAN_REACTION_MS = 120L;
    /** 摇一摇每秒最多能撞几下。再快就不是手在摇了 */
    private static final long MAX_SHAKE_HITS_PER_SECOND = 12L;
    /** 网络往返与两端时钟的宽容量。给太小会把正常玩家judged成作弊 */
    static final long CLOCK_SLACK_MS = 1500L;
    /** C1:一次挑战身份的有效期 = 配置时长 + 这段余量。过期的成绩不结算,只能重新开始 */
    private static final long ATTEMPT_TTL_SLACK_MS = 10L * 60L * 1000L;
    /** C1:挑战身份落在 JS 安全整数内,小程序按 Number 读不丢精度 */
    private static final long JS_SAFE_INTEGER_MASK = (1L << 53) - 1L;
    /** C1:带服务端挑战身份(attemptId)的提交动作 → 对应玩法段 */
    static final Map<String, String> ATTEMPT_BOUND_SUBMITS;
    static {
        Map<String, String> submits = new HashMap<>();
        submits.put("SUBMIT_REACTION", "reaction");
        submits.put("SUBMIT_BALL_SHAKE", "ballShake");
        submits.put("SUBMIT_QUIET_HOLD", "quietHold");
        submits.put("SUBMIT_SHOUT", "shout");
        submits.put("SUBMIT_STOPWATCH", "stopwatch");
        submits.put("SUBMIT_COMPASS", "compass");
        ATTEMPT_BOUND_SUBMITS = Collections.unmodifiableMap(submits);
    }
    /**
     * C1 传感器小游戏的可信边界。这六段的成绩全由本机传感器/时钟产出,服务端只能复核
     * 「没超过真实用时」「方位在容差内」—— 证明不了真做到了,改客户端就能伪造。
     * ⇒ 只算娱乐成绩:可以过关、发探索值与徽章,不许触发积分、券、掉落等资金权益(C1 选项 A)。
     */
    static final List<String> SELF_REPORTED_SECTIONS = Collections.unmodifiableList(
            Arrays.asList("reaction", "ballShake", "quietHold", "shout", "stopwatch", "compass"));
    private static final String TEAM_MEMBER_CHALLENGES = "teamMemberChallenges";

    /** 选中选项的“选中后的反馈”图片(每个选项最多 3 张,多选按选择顺序),写进本局状态供重入回读。 */
    private static void putFeedbackImages(ObjectNode node, List<JsonNode> picked) {
        ArrayNode images = node.putArray("lastFeedbackImages");
        for (JsonNode option : picked) {
            int count = 0;
            for (JsonNode image : option.path("fbImgs")) {
                if (count++ >= 3) break;
                if (image.isTextual() && !image.asText().trim().isEmpty()) images.add(image.asText().trim());
            }
        }
    }

    static String challengeGame(String action) {
        switch (action) {
            case "SUBMIT_BALL_SHAKE": return "ballShake";
            case "SUBMIT_REACTION": return "reaction";
            case "SUBMIT_QUIET_HOLD": return "quietHold";
            case "SUBMIT_SHOUT": return "shout";
            case "SUBMIT_COUNTDOWN": return "countdown";
            case "SUBMIT_STOPWATCH": return "stopwatch";
            default: return null;
        }
    }

    static boolean challengeObjectivesPassed(JsonNode config, ObjectNode state,String selected) {
        boolean found = false;
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (enabled(config.get(kind)) && (selected.isEmpty() || selected.equals(kind))) {
            found = true; if (!state.path(kind).path("passed").asBoolean()) return false;
        }
        return found;
    }

    static boolean sharedMechanicsReady(JsonNode config, ObjectNode state) {
        boolean objective=false;
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (enabled(config.get(kind))) {
            objective=true;
            if (!state.path(kind).path("passed").asBoolean()) return false;
        }
        if (objective) return true;
        if (enabled(config.get("scan"))) return state.path("scan").path("scanned").asBoolean();
        return mechanicsReady(config, state);
    }

    static boolean mechanicsReady(JsonNode config, ObjectNode state) {
        if (enabled(config.get("random"))
                && state.path("draws").size() < config.path("random").path("drawCount").asInt()) return false;
        if (enabled(config.get("branch"))) {
            JsonNode step = findById(config.path("branch").path("steps"),
                    state.path("branch").path("currentStepId").asText());
            if (step == null || !step.path("terminal").asBoolean(false)) return false;
        }
        if (enabled(config.get("multiplayer"))) {
            JsonNode multi = state.path("multiplayer");
            if (!rolesMeetMinimum(config.path("multiplayer"), multi.path("roles"))) return false;
            if (multi.path("roles").size() != multi.path("members").size()) return false;
            int requiredTurns = config.path("multiplayer").path("requiredTurns").asInt(1);
            if (multi.path("completedUnitIds").size() < requiredTurns) return false;
        }
        // ===== Figma v5.1 新玩法 =====
        // 少查一个 = 纯该玩法的模板开局即可通关(打卡前置的 completeGate 恒放行)。
        // musicCorner / silentOrder 按 #857 拍板不判定通关;timeWindow 的闸在 start() 已把守。
        if (enabled(config.get("blindTaste"))
                && !state.path("blindTaste").path("solved").asBoolean(false)) return false;
        if (enabled(config.get("diyName"))
                && state.path("diyName").path("name").asText("").isEmpty()) return false;
        if (enabled(config.get("steps"))
                && !state.path("steps").path("reached").asBoolean(false)) return false;
        if (enabled(config.get("dailySign"))
                && state.path("dailySign").path("claimedDate").asText("").isEmpty()) return false;
        if (enabled(config.get("slowTask"))
                && !state.path("slowTask").path("claimed").asBoolean(false)) return false;
        // 估数看的是"答过了"而不是"答对了":猜不中就卡住,玩家会退出而不是重猜(施工文档 §1.3)
        if (enabled(config.get("estimate"))
                && !state.path("estimate").path("submitted").asBoolean(false)) return false;
        // 比价同理:看的是"这一局走完了",答错收场也算走完
        if (enabled(config.get("pricePair"))
                && !state.path("pricePair").path("finished").asBoolean(false)) return false;
        // 竞猜:押了就算走完这一站。答案要等商家次日才给,不这样玩家会卡在一个
        // 当天根本无法判定的节点上,整条路线断在这里(施工文档 §1.3)。
        if (enabled(config.get("predict"))
                && state.path("predict").path("optionKey").asText("").isEmpty()) return false;
        // 找东西反过来:它有确定答案,必须全找齐才算过,可以无限点
        if (enabled(config.get("hiddenObject"))) {
            int need = config.path("hiddenObject").path("hotspots").size();
            if (state.path("hiddenObject").path("foundIds").size() < need) return false;
        }
        // 问答:答完就算走完(答错次数用完也算),与猜数字同一条口径
        if (enabled(config.get("qa"))
                && !state.path("qa").path("finished").asBoolean(false)) return false;
        // 扫码:扫了才算。这一条就是「进店」本身,没有别的判据
        if (enabled(config.get("scan"))
                && !state.path("scan").path("scanned").asBoolean(false)) return false;
        // R3 三个新玩法。排序有次数上限,排完(或次数用尽)就算走完,与猜图同口径;
        // 连线与分类有确定答案且可无限重试,必须全对才算过,与找东西同口径 ——
        // 没全对就放行等于「随便连一下就能打卡」,玩法的判定当场作废。
        if (enabled(config.get("sort"))
                && !state.path("sort").path("finished").asBoolean(false)) return false;
        if (enabled(config.get("match"))
                && !state.path("match").path("passed").asBoolean(false)) return false;
        if (enabled(config.get("classify"))
                && !state.path("classify").path("passed").asBoolean(false)) return false;
        // R16 对照题与排序同口径:有次数上限时交完(对或错)就算走完;
        // 没配上限时就得全标对才算过 —— 不这样,「随便标一下」的玩家会被永久卡在打卡闸前。
        if (enabled(config.get("compare"))
                && !state.path("compare").path("finished").asBoolean(false)) return false;
        /* 预制人生四个新玩法。建档 / 留言是「交了就完」;拍照审核看的是 passed
           (兜底 pass 时也在提交里落成 passed=true,所以这一条就是唯一判据);
           打字与挑战类同口径,看 submitted 而不是 passed(没过也放行,理由同上)。 */
        if (enabled(config.get("profile"))
                && !state.path("profile").path("done").asBoolean(false)) return false;
        if (enabled(config.get("photoCheck"))
                && !state.path("photoCheck").path("passed").asBoolean(false)) return false;
        if (enabled(config.get("note"))
                && !state.path("note").path("done").asBoolean(false)) return false;
        if (enabled(config.get("typeIn"))
                && !state.path("typeIn").path("submitted").asBoolean(false)) return false;
        /* 决定类两个:抛/掷过就算走完这一站 —— 它们没有对错,结果本身就是任务。 */
        if (enabled(config.get("coinFlip"))
                && !state.path("coinFlip").path("flipped").asBoolean(false)) return false;
        if (enabled(config.get("diceRoll"))
                && !state.path("diceRoll").path("rolled").asBoolean(false)) return false;
        /* 挑战类六个(2026-09-22 现场感契约加了喊一嗓子):看的是「这一局交了」而不是「过了」。没过还卡着不让走,
           玩家只会退出而不是重来 —— 与猜数字同一条口径(施工文档 §1.3)。 */
        for (String game : new String[] { "reaction", "ballShake", "quietHold", "shout", "countdown", "stopwatch" }) {
            if (enabled(config.get(game))
                    && !state.path(game).path("submitted").asBoolean(false)) return false;
        }
        // 罗盘:对准过一次就算走完 —— 与估数同口径,没有对错、可以一直转(现场感契约 §3.4)
        if (enabled(config.get("compass"))
                && !state.path("compass").path("done").asBoolean(false)) return false;
        return true;
    }

    private static boolean rolesMeetMinimum(JsonNode config, JsonNode assigned) {
        for (JsonNode role : config.path("roles")) {
            int count = 0;
            java.util.Iterator<JsonNode> values = assigned.elements();
            while (values.hasNext()) if (role.path("id").asText().equals(values.next().asText())) count++;
            if (count < role.path("min").asInt()) return false;
        }
        return true;
    }

    private static void addIfPresent(List<ChoiceRef> choices, ChoiceRef choice) {
        if (choice != null) choices.add(choice);
    }

    /**
     * 判定型玩法的成败(阶段 1 / 链路 D):返回 TRUE=判对、FALSE=判错、null=不属于判定型玩法
     * 或成败字段缺失。判据刻意不是「没判错=判对」—— 非判定动作(扫码 / 留言 / 建档)走同一个 act(),
     * 若按「非错即对」会凭空写 sys.passed=1。只有本清单内玩法且成败字段真有值时才写。
     *
     * <p>这也是判错扣血的汇合点(返回 FALSE 时 act() 调 recordMistake),字段缺失一律按「不写、不扣」处理,
     * 宁可不扣血也不误扣、更不给非判定动作凭空记成败。
     */
    ObjectNode serverGameplayDecision(String action, JsonNode config, ObjectNode state) {
        Boolean passed = judgedOutcome(action, state);
        if (passed == null) passed = passedFact(action, state);
        String kit = null;
        if ("SUBMIT_QA".equals(action)) kit = "qa";
        else if ("SUBMIT_PRICE_PAIR".equals(action)) kit = "pricePair";
        else if ("SUBMIT_BLIND_TASTE".equals(action)) { kit = "blindTaste"; passed = state.path(kit).path("solved").asBoolean(); }
        else if ("FLIP_COIN".equals(action)) { kit = "coinFlip"; passed = state.path(kit).path("flipped").asBoolean() ? Boolean.TRUE : null; }
        else if ("ROLL_DICE".equals(action)) { kit = "diceRoll"; passed = !state.path(kit).path("rolled").asBoolean() ? null : "d20".equals(config.path(kit).path("mode").asText()) ? state.path(kit).path("success").asBoolean() : Boolean.TRUE; }
        else if ("DRAW".equals(action)) { kit = "random"; passed = state.path("draws").size() >= config.path(kit).path("drawCount").asInt() ? Boolean.TRUE : null; }
        if("SUBMIT_CHECK".equals(action)){kit="check";passed=state.path("check").path("success").asBoolean();}
        if ("SUBMIT_ALBUM".equals(action)) passed=state.path("album").path("done").asBoolean()?Boolean.TRUE:null;
        if ("SUBMIT_PHOTO_CHECK".equals(action))passed="PASS".equals(state.path("photoCheck").path("verdict").asText())?Boolean.TRUE:"REJECT".equals(state.path("photoCheck").path("verdict").asText())?Boolean.FALSE:null;
        if ("SUBMIT_PROFILE".equals(action)) passed=state.path("profile").path("done").asBoolean()?Boolean.TRUE:null;
        if ("SUBMIT_NOTE".equals(action)) passed=state.path("note").path("done").asBoolean()?Boolean.TRUE:null;
        if ("CLAIM_DAILY_SIGN".equals(action)) passed=state.path("dailySign").path("claimedDate").asText().isEmpty()?null:Boolean.TRUE;
        if ("SUBMIT_HIDDEN_OBJECT".equals(action)) {
            JsonNode hidden=state.path("hiddenObject");
            passed=hidden.path("foundIds").size()>=config.path("hiddenObject").path("hotspots").size()?Boolean.TRUE:hidden.path("exhausted").asBoolean()?Boolean.FALSE:null;
        }
        if (passed == null) return null;
        ObjectNode decision = objectMapper.createObjectNode().put("outcome", passed ? "PASS" : "FAIL");
        if (kit != null) {
            ObjectNode selection = decision.putObject("selection").put("kit", kit);
            if ("check".equals(kit))selection.put("nat",state.path("check").path("nat").asText());
            else if ("qa".equals(kit) && !config.path(kit).path("multi").asBoolean() && "PICK".equals(config.path(kit).path("mode").asText())) selection.put("optionId", state.path(kit).path("lastOptionId").asText());
            else if ("pricePair".equals(kit)) selection.put("optionId", state.path(kit).path("lastPickId").asText());
            else if ("blindTaste".equals(kit)) selection.put("optionId", state.path(kit).path("lastKey").asText());
            else if ("coinFlip".equals(kit)) selection.put("side", "HEADS".equals(state.path(kit).path("side").asText()) ? "heads" : "tails");
            else if ("diceRoll".equals(kit)) selection.put("face", state.path(kit).path("sum").asInt());
            else if ("random".equals(kit)) selection.put("optionId", state.path("draws").path(state.path("draws").size() - 1).path("id").asText());
        }
        return decision;
    }

    static Boolean judgedOutcome(String action, ObjectNode state) {
        switch (action) {
            case "SUBMIT_QA": return lastCorrect(state, "qa");
            case "SUBMIT_SORT": return lastCorrect(state, "sort");
            case "SUBMIT_MATCH": return lastCorrect(state, "match");
            case "SUBMIT_CLASSIFY": return lastCorrect(state, "classify");
            case "SUBMIT_COMPARE": return lastCorrect(state, "compare");
            case "SUBMIT_PRICE_PAIR": return lastCorrect(state, "pricePair");
            case "SUBMIT_HIDDEN_OBJECT":
                JsonNode hit = state.path("hiddenObject").path("lastHit");
                return hit.isBoolean() ? Boolean.valueOf(hit.asBoolean()) : null;
            default: return null;
        }
    }

    /**
     * 扣血清单之外、服务端同样有权威成败的玩法:只写 sys.passed 事实,**不扣血**(扣血口径见
     * judgedOutcome,9-21 拍板不动)。一律读「本局已定」的字段 —— 没到终局(还有次数 / 还在走)
     * 返回 null 不写,免得把「还没完」记成失败。
     */
    static Boolean passedFact(String action, ObjectNode state) {
        switch (action) {
            case "SUBMIT_REACTION": return flag(state, "reaction", "passed");
            case "SUBMIT_BALL_SHAKE": return flag(state, "ballShake", "passed");
            case "SUBMIT_QUIET_HOLD": return flag(state, "quietHold", "passed");
            case "SUBMIT_SHOUT": return flag(state, "shout", "passed");
            case "SUBMIT_COUNTDOWN": return flag(state, "countdown", "passed");
            case "SUBMIT_STOPWATCH": return flag(state, "stopwatch", "passed");     // 次数没用完不写
            case "SUBMIT_TYPE_IN": return flag(state, "typeIn", "passed");          // 同上
            // 罗盘只有「对准了」这一个终局(没对准直接拒收),计步只有「达标」这一个终局。
            case "SUBMIT_COMPASS": return state.path("compass").path("done").asBoolean(false) ? Boolean.TRUE : null;
            case "SUBMIT_STEPS": return state.path("steps").path("reached").asBoolean(false) ? Boolean.TRUE : null;
            // 猜数字:猜中才算过;接近(CLOSE)有安慰分,但不是「猜中」。
            case "SUBMIT_ESTIMATE": {
                String tier = state.path("estimate").path("tier").asText("");
                return tier.isEmpty() ? null : Boolean.valueOf("HIT".equals(tier));
            }
            // 拍照审核:只认视觉模型当场真判的结论。通道降级不可信(9-21 口径);次数用完被「放过」算没过。
            case "SUBMIT_PHOTO_CHECK": {
                String verdict = state.path("photoCheck").path("verdict").asText("");
                if ("PASS".equals(verdict)) return Boolean.TRUE;
                if ("FALLBACK_PASS".equals(verdict)) return Boolean.FALSE;
                return null;
            }
            default: return null;
        }
    }

    private static Boolean flag(ObjectNode state, String kit, String field) {
        JsonNode value = state.path(kit).path(field);
        return value.isBoolean() ? Boolean.valueOf(value.asBoolean()) : null;
    }

    /** 随机类玩法本次动作的结果序号(1 起);不是随机动作或算不出来返回 0。 */
    static int randomOutcome(String action, JsonNode config, ObjectNode state) {
        switch (action) {
            case "FLIP_COIN": {
                String side = state.path("coinFlip").path("side").asText("");
                return "HEADS".equals(side) ? 1 : "TAILS".equals(side) ? 2 : 0;
            }
            case "ROLL_DICE": return state.path("diceRoll").path("sum").asInt(0);
            case "DRAW": {
                JsonNode draws = state.path("draws");
                if (!draws.isArray() || draws.size() == 0) return 0;
                String id = draws.get(draws.size() - 1).path("id").asText("");
                int index = 0;
                for (JsonNode item : config.path("random").path("items")) {
                    index++;
                    if (id.equals(item.path("id").asText())) return index;
                }
                return 0;
            }
            default: return 0;
        }
    }

    private static Boolean lastCorrect(ObjectNode state, String kit) {
        JsonNode flag = state.path(kit).path("lastCorrect");
        return flag.isBoolean() ? Boolean.valueOf(flag.asBoolean()) : null;
    }

    /**
     * 盲品作答。2026-08-26 用户拍板:**可重答,答对才给分**。
     * 比对发生在服务端(answerKey 只在 config_snapshot 里,从不下发),所以重答不是漏洞。
     * 已答对后再提交直接拒绝 —— 否则同一题的 xp 能反复领。
     */
    private static void submitBlindTaste(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("blindTaste");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启盲品挑战");
        ObjectNode node = state.with("blindTaste");
        if (node.path("solved").asBoolean(false)) {
            throw new IllegalStateException("这一题已经答对了");
        }
        String key = text(payload, "key");
        if (findOptionByKey(cfg.path("options"), key) == null) {
            throw new IllegalArgumentException("没有这个选项");
        }
        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastKey", key);
        boolean correct = key.equals(cfg.path("answerKey").asText(""));
        node.put("solved", correct);
        if (correct) {
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
        }
    }

    static JsonNode findOptionByKey(JsonNode options, String key) {
        if (key == null || key.isEmpty() || options == null || !options.isArray()) return null;
        for (JsonNode option : options) {
            if (key.equals(option.path("key").asText(""))) return option;
        }
        return null;
    }

    static String text(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /**
     * 估数作答。按偏差分三档:命中(≤容差)满分 · 接近(≤3 倍容差)半分 · 偏离 0 分。
     *
     * <p>三档都算通关,原因写在施工文档 §1.3:估数带运气,把人卡在猜不中的节点上
     * 他会退出而不是重试,整条路线断在这里。得分分档已经够提供动力。
     *
     * <p>**只能提交一次** —— 能重猜的估数题等于没有难度,试几次就滑到满分了。
     */
    private static void submitEstimate(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("estimate");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启估数题");
        ObjectNode node = state.with("estimate");
        if (node.path("submitted").asBoolean(false)) {
            throw new IllegalStateException("这一题已经答过了");
        }

        Object raw = payload.get("value");
        if (raw == null) throw new IllegalArgumentException("请先给出一个估数");
        double guess;
        try {
            guess = Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("估数必须是数字");
        }
        if(!Double.isFinite(guess))throw new IllegalArgumentException("估数必须是有限数字");
        double min = cfg.path("min").asDouble();
        double max = cfg.path("max").asDouble();
        if (guess < min || guess > max) {
            // 越界直接拒绝而不是夹取:夹取会把"手滑填了个天文数字"记成一次正经作答
            throw new IllegalArgumentException("估数必须落在 " + trimNumber(min) + " 到 " + trimNumber(max) + " 之间");
        }

        double answer = cfg.path("answer").asDouble();
        double tolerance = cfg.path("tolerance").asDouble();
        double deviation = Math.abs(guess - answer);
        int fullXp = cfg.path("xp").asInt(0);

        String tier;
        int awarded;
        if (deviation <= tolerance) {
            tier = "HIT";
            awarded = fullXp;
        } else if (deviation <= tolerance * 3) {
            tier = "CLOSE";
            awarded = fullXp / 2;
        } else {
            tier = "MISS";
            awarded = 0;
        }

        node.put("submitted", true);
        node.put("guess", guess);
        node.put("tier", tier);
        node.put("awardedXp", awarded);
        if (awarded > 0) {
            score.setScore(value(score.getScore()) + awarded);
        }
    }

    /**
     * 猜图:几张图里挑出正确的那一张。
     *
     * <p>★ 2026-09-10 改模型。这一段原来是**比价**(每件带价格、两两比谁贵、连对算 streak),
     * 而产品口径里「猜图」从来就是「几张图挑正确的那张」—— 两者不是同一个玩法。
     *
     * <p>哪一张是对的**只在服务端**:投影下发的 items 不带 correct,客户端手里只有图和名字。
     */
    private static ChoiceRef submitPricePair(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("pricePair");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启猜图");
        ObjectNode node = state.with("pricePair");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局猜图已经结束了");
        }

        String pickId = text(payload, "pickId");
        boolean known = false;
        String answerId = "";
        for (JsonNode item : cfg.path("items")) {
            String id = item.path("id").asText();
            if (id.equals(pickId)) known = true;
            if (item.path("correct").asBoolean(false)) answerId = id;
        }
        if (!known) throw new IllegalArgumentException("请从这几张图里选一张");

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastPickId", pickId);

        boolean correct = pickId.equals(answerId);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            node.put("answerId", answerId);
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("pricePair", null);
        }
        int allowed = cfg.path("maxTries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
            // 次数用完了才揭晓答案:还有次数时给出来,这一关就没了
            node.put("answerId", answerId);
        }
        return null;
    }

    /**
     * 找东西作答:玩家在图上点一下,服务端判这一点落没落进某个热区。
     *
     * <p>坐标是归一化的 0–1,与玩家把图缩放到多大无关 —— 客户端把屏幕坐标反变换回
     * 原图比例再报上来。热区坐标只进不出(投影里没有它们),所以这里的比对是这个
     * 玩法**唯一**的判定点,没有第二道防线。
     *
     * <p>点空不报错,只累计 missCount:点空是玩法的一部分,把它当异常会让客户端
     * 每点空一次弹一个错误提示。
     */
    private static ChoiceRef submitHiddenObject(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("hiddenObject");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启找东西");
        ObjectNode node = state.with("hiddenObject");
        ArrayNode found = node.withArray("foundIds");
        JsonNode spots = cfg.path("hotspots");
        if (found.size() >= spots.size()) {
            throw new IllegalStateException("已经全部找到了");
        }
        if (node.path("exhausted").asBoolean(false)) {
            throw new IllegalStateException("这一关的次数已经用完了");
        }

        Object rawX = payload.get("x");
        Object rawY = payload.get("y");
        if (rawX == null || rawY == null) throw new IllegalArgumentException("请给出点击位置");
        double x;
        double y;
        try {
            x = Double.parseDouble(String.valueOf(rawX).trim());
            y = Double.parseDouble(String.valueOf(rawY).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("点击位置必须是数字");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
            throw new IllegalArgumentException("点击位置必须是 0 到 1 之间的比例值");
        }

        String hitId = null;
        String hitLabel = null;
        for (JsonNode spot : spots) {
            String id = spot.path("id").asText("");
            if (containsText(found, id)) continue;          // 已经找到的不重复命中
            double dx = x - spot.path("x").asDouble();
            double dy = y - spot.path("y").asDouble();
            double r = spot.path("r").asDouble();
            if (dx * dx + dy * dy <= r * r) {
                hitId = id;
                hitLabel = spot.path("label").asText("");
                break;
            }
        }

        if (hitId == null) {
            int miss = node.path("missCount").asInt(0) + 1;
            node.put("missCount", miss);
            node.put("lastHit", false);
            node.putNull("lastLabel");
            /* 限次用完这一关就结束。★ 闸必须在服务端:客户端那圈点数只是画给人看的,
               不拦的话点完最后一次照样能接着点,「限定次数」就是句空话。 */
            int allowedMiss = cfg.path("maxTries").asInt(0);
            if (allowedMiss > 0 && miss >= allowedMiss) node.put("exhausted", true);
            return null;
        }
        found.add(hitId);
        node.put("lastHit", true);
        node.put("lastLabel", hitLabel);
        if (found.size() >= spots.size()) {
            // 分是找齐才给的:找到一半就发分,玩家没有找完的理由
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("hiddenObject", null);
        }
        return null;
    }

    /** 数轴边界报错时把 480.0 写成 480,报错文案里不该出现小数点尾巴。 */
    private static String trimNumber(double number) {
        if (number == Math.rint(number) && !Double.isInfinite(number)) {
            return String.valueOf((long) number);
        }
        return String.valueOf(number);
    }

    /**
     * v5.1 新玩法的运行期视图:公开配置 + 本局状态。
     * ★ 和 AdvancedGamePublicProjection 同规矩的白名单:blindTaste.answerKey 与 dailySign.poems
     *   在这里同样不出现 —— 它们只留在 config_snapshot 里供服务端比对。
     */
    /**
     * 问答三种模式。答案**只在服务端**:打字题的 answerText、选项题的 correct
     * 都不下发,客户端报的只是「我填了什么 / 我点了第几个」。
     */
    private static List<ChoiceRef> submitQa(JsonNode config,ObjectNode state,Map<String,Object> payload,ScoreChange score) {
        return submitQa(config,state,payload,score,null);
    }

    private static List<ChoiceRef> submitQa(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score,Long memberId) {
        List<ChoiceRef> choices = new ArrayList<>();
        JsonNode cfg = config.get("qa");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启问答");
        ObjectNode node = state.with("qa");
        if (node.path("finished").asBoolean(false)) throw new IllegalStateException("这一题已经答完了");

        String mode = cfg.path("mode").asText("");
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);

        boolean correct;
        if ("TYPE".equals(mode)) {
            String input = text(payload, "input");
            if (input.isEmpty()) throw new IllegalArgumentException("请先写下你的答案");
            if (input.length() > 200) throw new IllegalArgumentException("答案不能超过 200 字");
            correct = matchesAnswer(input, cfg.path("answerText").asText(""));
            node.put("lastInput", input);
        } else if ("PICK".equals(mode)) {
            boolean judge = cfg.path("judge").asBoolean(true);
            if (!judge) {
                // 不判断对错:单选,任一有效选项即完成;正确答案 / 多选 / 次数等判断设置不参与
                if (payload != null && payload.containsKey("optionIds")) throw new IllegalArgumentException("这道题只能选一项");
                String optionId = text(payload, "optionId");
                JsonNode picked = null;
                for (JsonNode option : cfg.path("options")) {
                    if (optionId.equals(option.path("id").asText())) picked = option;
                }
                if (picked == null) throw new IllegalArgumentException("请从这几个选项里选一个");
                node.put("lastOptionId", optionId);
                node.put("lastFeedback", picked.path("fb").asText(""));
                putFeedbackImages(node, java.util.Collections.singletonList(picked));
                choices.add(new ChoiceRef("qa", optionId));
                node.put("lastCorrect", true);
                node.put("finished", true);
                node.put("passed", true);
                score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
                return choices;
            }
            if (cfg.path("multi").asBoolean(false)) {
                // 多选:集合完全相等才过 —— 少选、多选、选错任何一个都不过
                correct = submitQaMulti(cfg, node, payload);
                // R14:多选里每个被选中的选项都是一个「选择」,各自的 effects 都写。
                for (JsonNode pickedId : node.path("lastOptionIds")) {
                    choices.add(new ChoiceRef("qa", pickedId.asText("")));
                }
            } else {
                String optionId = text(payload, "optionId");
                JsonNode picked = null;
                for (JsonNode option : cfg.path("options")) {
                    if (optionId.equals(option.path("id").asText())) picked = option;
                }
                if (picked == null) throw new IllegalArgumentException("请从这几个选项里选一个");
                correct = picked.path("correct").asBoolean(false);
                node.put("lastOptionId", optionId);
                // 这一条的反馈是商家写给「选了它的人」看的,判完才给
                node.put("lastFeedback", picked.path("fb").asText(""));
                putFeedbackImages(node, java.util.Collections.singletonList(picked));
                // R14:选了这个选项就写它的 effects(对错都算作者写下的结果)。
                choices.add(new ChoiceRef("qa", optionId));
            }
        } else {
            /* 拍照:判不了照片内容,交上来就算做到了。
               地址要留痕 —— 商家事后想看「玩家到底交了什么」时,这是唯一的证据。 */
            String imageUrl = text(payload, "imageUrl");
            if (imageUrl.isEmpty()) throw new IllegalArgumentException("请先拍一张");
            if(config.path("outcomeRules").path("version").asInt()==1)score.photoEvidence = new PhotoEvidence(imageUrl, memberId);
            node.put("lastImageUrl", imageUrl);
            correct = true;
        }

        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            revealQaAnswer(cfg, node);
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return choices;
        }
        int allowed = cfg.path("maxTries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
            // 次数用完才揭晓,而且要商家开了「答错亮答案」才给 —— 一次性的题亮了就没下次
            if (cfg.path("reveal").asBoolean(false)) revealQaAnswer(cfg, node);
        }
        return choices;
    }

    /**
     * 多选作答:payload.optionIds 是一个 id 数组,提交的集合必须与正确答案集合**完全相等**。
     * 与单选一样,correct 标记只在服务端(config_snapshot),客户端手里只有选项文案。
     */
    private static boolean submitQaMulti(JsonNode cfg, ObjectNode node, Map<String, Object> payload) {
        Object raw = payload.get("optionIds");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请从这几个选项里选");
        List<?> picked = (List<?>) raw;
        if (picked.isEmpty()) throw new IllegalArgumentException("请从这几个选项里选");
        Set<String> known = new HashSet<>();
        for (JsonNode option : cfg.path("options")) known.add(option.path("id").asText(""));
        Set<String> chosen = new LinkedHashSet<>();
        for (Object one : picked) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("选了不存在的选项");
            if (!chosen.add(id)) throw new IllegalArgumentException("同一个选项不能重复选");
        }

        Set<String> answers = new HashSet<>(correctOptionIds(cfg));
        ArrayNode lastIds = node.putArray("lastOptionIds");
        StringBuilder feedback = new StringBuilder();
        List<JsonNode> pickedOptions = new ArrayList<>();
        for (JsonNode option : cfg.path("options")) {
            String id = option.path("id").asText("");
            if (!chosen.contains(id)) continue;
            lastIds.add(id);
            pickedOptions.add(option);
            // 反馈与单选同源:把选中的每个选项的 fb 拼起来,判完才给
            String fb = option.path("fb").asText("");
            if (fb.isEmpty()) continue;
            if (feedback.length() > 0) feedback.append(' ');
            feedback.append(fb);
        }
        node.put("lastFeedback", feedback.toString());
        putFeedbackImages(node, pickedOptions);
        return chosen.equals(answers);
    }

    /** 揭晓答案:单选给 answerId,多选给 answerIds —— 没开启的玩法一个字段都不加。 */
    private static void revealQaAnswer(JsonNode cfg, ObjectNode node) {
        if (!"PICK".equals(cfg.path("mode").asText(""))) return;
        if (cfg.path("multi").asBoolean(false)) {
            ArrayNode ids = node.putArray("answerIds");
            for (String id : correctOptionIds(cfg)) ids.add(id);
        } else {
            node.put("answerId", answerOptionId(cfg));
        }
    }

    private static List<String> correctOptionIds(JsonNode cfg) {
        List<String> ids = new ArrayList<>();
        for (JsonNode option : cfg.path("options")) {
            if (option.path("correct").asBoolean(false)) ids.add(option.path("id").asText(""));
        }
        return ids;
    }

    private static String answerOptionId(JsonNode cfg) {
        for (JsonNode option : cfg.path("options")) {
            if (option.path("correct").asBoolean(false)) return option.path("id").asText("");
        }
        return "";
    }

    /** 打字题的比对:忽略大小写与首尾空格,多个答案用、分隔,命中任一即过。 */
    private static boolean matchesAnswer(String input, String answerText) {
        String got = input.trim().toLowerCase();
        if (got.isEmpty()) return false;
        for (String one : answerText.split("[、,,]")) {
            if (one.trim().toLowerCase().equals(got)) return true;
        }
        return false;
    }

    /* ==================== R3 三个新玩法:排序 / 连线 / 分类 ====================
       三者的答案都只在 config_snapshot 里:sort.answerOrder / match.pairs / classify.answer。
       投影与运行期视图只下发题面,客户端手里没有答案 —— 判定就是服务的这一层做的。 */

    /**
     * 排序作答:提交玩家排好的 id 序列,与服务端冻结的 answerOrder 逐位比对。
     *
     * <p>回执只给整体对错(lastCorrect),不给逐位置对错 —— 逐位提示配合可重排
     * 等于把答案一格格试出来。这是有意的口径,前端的「哪几个位置错了」提示需要
     * 后端另开字段时再评估(见不确定清单)。
     *
     * <p>maxAttempts > 0 时,次数用尽即收摊(finished 但 passed=false),与猜图同口径:
     * 卡在一个排不出来的题上,玩家会退出而不是重排,整条路线断了。
     */
    private static ChoiceRef submitSort(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("sort");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启排序题");
        ObjectNode node = state.with("sort");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局排序已经结束了");
        }

        Object raw = payload.get("order");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交排好的顺序");
        List<?> submitted = (List<?>) raw;
        JsonNode items = cfg.path("items");
        if (submitted.size() != items.size()) {
            throw new IllegalArgumentException("排好的顺序必须包含全部待排项");
        }
        Set<String> known = new HashSet<>();
        for (JsonNode item : items) known.add(item.path("id").asText(""));
        List<String> order = new ArrayList<>();
        for (Object one : submitted) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("排好的顺序里有不存在的待排项");
            if (order.contains(id)) throw new IllegalArgumentException("排好的顺序不能重复引用同一项");
            order.add(id);
        }

        JsonNode answerOrder = cfg.path("answerOrder");
        boolean correct = true;
        for (int i = 0; i < answerOrder.size(); i++) {
            if (!answerOrder.get(i).asText("").equals(order.get(i))) {
                correct = false;
                break;
            }
        }

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastCorrect", correct);
        ArrayNode lastOrder = node.putArray("lastOrder");
        for (String id : order) lastOrder.add(id);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 排对才给分,与猜图/问答同口径;错了不给,重排也不会攒分
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("sort", null);
        }
        int allowed = cfg.path("maxAttempts").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
        }
        return null;
    }

    /**
     * 连线作答:提交 [左 id, 右 id] 列表,与服务端冻结的 pairs 比对。
     *
     * <p>提交顺序无关紧要 —— 判的是每一条连线在不在答案里;由于两侧都不允许重复,
     * 「提交的每条都在答案里」等价于「两边一一对应且完全相等」。
     */
    private static ChoiceRef submitMatch(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("match");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启连线题");
        ObjectNode node = state.with("match");
        if (node.path("passed").asBoolean(false)) {
            throw new IllegalStateException("这一局连线已经结束了");
        }

        Object raw = payload.get("pairs");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交连好的线");
        List<?> submitted = (List<?>) raw;
        JsonNode left = cfg.path("left");
        if (submitted.size() != left.size()) {
            throw new IllegalArgumentException("连线必须覆盖左侧全部条目");
        }
        Set<String> leftIds = new HashSet<>();
        for (JsonNode item : left) leftIds.add(item.path("id").asText(""));
        Set<String> rightIds = new HashSet<>();
        for (JsonNode item : cfg.path("right")) rightIds.add(item.path("id").asText(""));

        Set<String> seenLeft = new HashSet<>();
        Set<String> seenRight = new HashSet<>();
        boolean correct = true;
        for (Object one : submitted) {
            if (!(one instanceof List) || ((List<?>) one).size() != 2) {
                throw new IllegalArgumentException("每条连线必须是 [左 id, 右 id] 两个值");
            }
            List<?> pair = (List<?>) one;
            String leftId = pair.get(0) == null ? "" : String.valueOf(pair.get(0)).trim();
            String rightId = pair.get(1) == null ? "" : String.valueOf(pair.get(1)).trim();
            if (!leftIds.contains(leftId)) throw new IllegalArgumentException("连线里有不存在的左侧条目");
            if (!rightIds.contains(rightId)) throw new IllegalArgumentException("连线里有不存在的右侧条目");
            if (!seenLeft.add(leftId)) throw new IllegalArgumentException("左侧条目不能重复连线");
            if (!seenRight.add(rightId)) throw new IllegalArgumentException("右侧条目不能重复连线");
            if (!hasPair(cfg.path("pairs"), leftId, rightId)) correct = false;
        }

        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全连对才给分;passed 一旦置位重复提交直接拒,不会重领
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("match", null);
        }
        return null;
    }

    private static boolean hasPair(JsonNode pairs, String leftId, String rightId) {
        for (JsonNode pair : pairs) {
            if (leftId.equals(pair.path(0).asText("")) && rightId.equals(pair.path(1).asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分类作答:提交 itemId → binId 的映射,与服务端冻结的 answer 比对。
     *
     * <p>提交的键必须恰好覆盖全部卡片(尺寸相等 + 逐张在册),多一张少一张都拒 ——
     * 少了那张永远分不对,多了说明客户端手里有已经删掉的卡片。
     */
    private static ChoiceRef submitClassify(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("classify");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启分类题");
        ObjectNode node = state.with("classify");
        if (node.path("passed").asBoolean(false)) {
            throw new IllegalStateException("这一局分类已经结束了");
        }

        Object raw = payload.get("placement");
        if (!(raw instanceof Map)) throw new IllegalArgumentException("请提交每张卡片放进哪个分类");
        Map<?, ?> placement = (Map<?, ?>) raw;
        JsonNode items = cfg.path("items");
        if (placement.size() != items.size()) {
            throw new IllegalArgumentException("每张卡片都要放进一个分类");
        }
        Set<String> binIds = new HashSet<>();
        for (JsonNode bin : cfg.path("bins")) binIds.add(bin.path("id").asText(""));

        JsonNode answer = cfg.path("answer");
        boolean correct = true;
        for (JsonNode item : items) {
            String itemId = item.path("id").asText("");
            if (!placement.containsKey(itemId)) {
                throw new IllegalArgumentException("每张卡片都要放进一个分类");
            }
            Object chosen = placement.get(itemId);
            String binId = chosen == null ? "" : String.valueOf(chosen).trim();
            if (!binIds.contains(binId)) throw new IllegalArgumentException("放进了不存在的分类");
            if (!binId.equals(answer.path(itemId).asText(""))) correct = false;
        }

        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastCorrect", correct);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全分对才给分;passed 一旦置位重复提交直接拒,不会重领
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return new ChoiceRef("classify", null);
        }
        return null;
    }

    /**
     * 对照作答:提交标出的条目 id 列表,与服务端冻结的 answer 做**集合相等**判定。
     *
     * <p>多标一条、少标一条都不过 —— 判的就是「缺失 / 被改动」的完整名单。
     * 提交顺序无关紧要,重复标记与不存在的 id 是坏输入直接拒,不算一次作答。
     *
     * <p>回执只给整体对错(lastCorrect)与次数,不给逐条对错 —— 逐条提示配合可重标
     * 等于把答案一条条试出来(排序题同一条口径)。
     *
     * <p>maxAttempts 配了才有上限(1 至 10):用尽即收摊(finished 但 passed=false),
     * 与排序题同口径;没配就是不限次,交对为止。
     */
    private static void submitCompare(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("compare");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启对照题");
        ObjectNode node = state.with("compare");
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一局对照已经结束了");
        }

        Object raw = payload.get("marked");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交标出的条目");
        List<?> submitted = (List<?>) raw;
        Set<String> known = new HashSet<>();
        for (JsonNode item : cfg.path("left").path("items")) known.add(item.path("id").asText(""));
        for (JsonNode item : cfg.path("right").path("items")) known.add(item.path("id").asText(""));
        // LinkedHashSet:集合判等用它,回显 lastMarked 时又保留玩家提交顺序
        Set<String> marked = new LinkedHashSet<>();
        for (Object one : submitted) {
            String id = one == null ? "" : String.valueOf(one).trim();
            if (!known.contains(id)) throw new IllegalArgumentException("标出的条目里有不存在的项");
            if (!marked.add(id)) throw new IllegalArgumentException("同一条目不能标两次");
        }

        Set<String> answer = new HashSet<>();
        for (JsonNode one : cfg.path("answer")) answer.add(one.asText(""));
        boolean correct = marked.equals(answer);

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastCorrect", correct);
        ArrayNode lastMarked = node.putArray("lastMarked");
        for (String id : marked) lastMarked.add(id);
        if (correct) {
            node.put("finished", true);
            node.put("passed", true);
            // 全标对才给分,与排序/连线/分类同口径;错了不给,重标也不会攒分
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return;
        }
        int allowed = cfg.path("maxAttempts").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("finished", true);
            node.put("passed", false);
        }
    }

    /**
     * 限时打字:必须先在 START_CHALLENGE 开表,服务端拿那一刻的服务器时间复核 ——
     * 设备时钟玩家改得动,不先开表的提交一律判「还没开始」。
     *
     * <p>判定 = 内容相等(按 caseSensitive)+ 用时 ≤ seconds。没过还有次数时把表清掉,
     * 下一次要重新开表(与精准停表同一条口径)。
     */
    private void submitTypeIn(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("typeIn");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启限时打字");
        ObjectNode node = state.with("typeIn");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long serverElapsed = serverElapsedMs(node, "限时打字");

        long elapsedMs = requireNonNegativeLong(payload, "elapsedMs", "用时必须是非负整数");
        long limitMs = cfg.path("seconds").asLong(0L) * 1000L;
        if (config.path("outcomeRules").path("version").asInt()!=1 && elapsedMs > limitMs + CLOCK_SLACK_MS) throw new IllegalStateException("已经超时了");
        // 报的用时比服务器记的还长 —— 这两个数只能来自同一段真实经过的时间
        if (elapsedMs > serverElapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("用时与记录对不上");
        String typed = text(payload, "text");
        if (typed.isEmpty()) throw new IllegalArgumentException("请先打出这行字");
        if (typed.length() > 40) throw new IllegalArgumentException("提交内容不能超过 40 字");

        String target = cfg.path("target").asText("");
        boolean same = cfg.path("caseSensitive").asBoolean(false)
                ? typed.equals(target) : typed.equalsIgnoreCase(target);
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.remove("startedAtMs");     // 重来要重新开表,老表不留给下一次
        node.put("lastText", typed);
        if (same) {
            node.put("submitted", true);
            node.put("passed", true);
            awardOnce(node);
            score.setScore(value(score.getScore()) + cfg.path("xp").asInt(0));
            return;
        }
        int allowed = cfg.path("tries").asInt(0);
        if (allowed > 0 && attempts >= allowed) {
            node.put("submitted", true);
            node.put("passed", false);
        }
    }

    /* ==================== 决定类:抛硬币 / 掷骰子 ====================
       秘密不在配置里 —— 朝哪面、几个点都是**服务端现掷**的。客户端只负责把那一下演出来。 */

    /** 抛一次,认结果。已经抛过就原样返回:能抛到满意为止的话,这一屏就不是「认」而是「挑」。 */
    private void flipCoin(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("coinFlip");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启抛硬币");
        ObjectNode node = state.with("coinFlip");
        if (node.path("flipped").asBoolean(false)) return;
        boolean heads = random.nextInt(2) == 0;
        JsonNode face = cfg.path(heads ? "heads" : "tails");
        node.put("flipped", true);
        node.put("side", heads ? "HEADS" : "TAILS");
        node.put("action", face.path("action").asText(""));
        awardOnce(node);
    }

    /** 掷一次。两颗只报点数和 —— 拿两颗的和去索引六个面本来就是这一屏的规则。 */
    private void rollDice(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("diceRoll");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启掷骰子");
        ObjectNode node = state.with("diceRoll");
        if (node.path("rolled").asBoolean(false)) return;
        if ("d20".equals(cfg.path("mode").asText())) {
            String rollMode = cfg.path("rollMode").asText();
            int first = random.nextInt(20) + 1;
            ArrayNode dice = node.putArray("pips").add(first);
            int kept = first;
            if (!"normal".equals(rollMode)) {
                int second = random.nextInt(20) + 1;
                dice.add(second);
                kept = "advantage".equals(rollMode) ? Math.max(first, second) : Math.min(first, second);
            }
            int total = kept + cfg.path("modifier").asInt();
            boolean success = total >= cfg.path("dc").asInt();
            node.put("rolled", true);
            node.put("kept", kept);
            node.put("total", total);
            node.put("success", success);
            node.put("action", cfg.path(success ? "successText" : "failText").asText());
            awardOnce(node);
            return;
        }
        int count = cfg.path("diceCount").asInt(1) == 2 ? 2 : 1;
        ArrayNode pips = node.putArray("pips");
        int sum = 0;
        for (int i = 0; i < count; i++) {
            int pip = random.nextInt(6) + 1;
            pips.add(pip);
            sum += pip;
        }
        node.put("rolled", true);
        node.put("sum", sum);
        JsonNode faces = cfg.path("faces");
        node.put("action", sum >= 1 && sum <= faces.size() ? (faces.get(sum - 1).isObject()?faces.get(sum - 1).path("action").asText(""):faces.get(sum - 1).asText("")) : "");
        awardOnce(node);
    }

    /* ==================== 挑战类五个:开表 → 报成绩 → 服务端复核 ====================
       ★ 客户端报的毫秒数是**设备时钟**算的,而设备时钟玩家改得动。所以每一条成绩都要
       拿 START_CHALLENGE 记下的服务器时间复核一遍,对不上就拒。 */

    private void startChallenge(JsonNode config, ObjectNode state, Map<String, Object> payload, Long memberId) {
        String game = challengeKey(payload);
        if (!enabled(config.path(game))) throw new IllegalArgumentException("该玩法未开启这一项");
        ObjectNode node = state.with(game);
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        node.put("startedAtMs", clock.now().getTime());
        node.put("attempts", node.path("attempts").asInt(0));
        if (SELF_REPORTED_SECTIONS.contains(game)) issueAttempt(node, config.path(game), memberId);
    }

    /**
     * C1:每次开表发一个服务端挑战身份,绑本人 + 本局(state 就在本局会话里)+ 本节点,单次消费、会过期。
     * 重开就换新的 —— 后台切走/中断前那次的成绩不能拿来结算。
     */
    private void issueAttempt(ObjectNode node, JsonNode cfg, Long memberId) {
        long now = clock.now().getTime();
        long previous = node.path("attempt").path("id").asLong(0L);
        long id = attemptIdSource.getAsLong() & JS_SAFE_INTEGER_MASK;
        if (id == 0L || id == previous) id = previous % JS_SAFE_INTEGER_MASK + 1L;
        double configuredSeconds = Math.max(cfg.path("seconds").asDouble(0d),
                Math.max(cfg.path("targetSeconds").asDouble(0d), cfg.path("holdSeconds").asDouble(0d)));
        ObjectNode attempt = node.putObject("attempt");
        attempt.put("id", id);
        attempt.put("memberId", memberId == null ? 0L : memberId);
        attempt.put("issuedAtMs", now);
        attempt.put("expiresAtMs", now + (long) Math.ceil(configuredSeconds * 1000d) + ATTEMPT_TTL_SLACK_MS);
        attempt.put("used", false);
    }

    /**
     * 提交带了 attemptId 就必须是本人当前这一次开表、没交过、没过期;对不上一律不结算。
     * 没带(存量客户端)按原复核口径走 —— 奖励上限在完成关卡那一步统一封顶,不靠这里。
     */
    void requireCurrentAttempt(JsonNode node, Map<String, Object> payload, Long memberId) {
        Object raw = payload.get("attemptId");
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return;
        long claimed;
        try {
            claimed = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("挑战身份不对，请重新开始");
        }
        JsonNode attempt = node.path("attempt");
        if (!attempt.isObject() || claimed != attempt.path("id").asLong(0L)
                || memberId == null || memberId.longValue() != attempt.path("memberId").asLong(0L)) {
            throw new IllegalStateException("这一次挑战已经作废，请重新开始");
        }
        if (attempt.path("used").asBoolean(false)) throw new IllegalStateException("这一次挑战已经交过了，请重新开始");
        if (clock.now().getTime() > attempt.path("expiresAtMs").asLong(0L)) throw new IllegalStateException("这一次挑战已经过期，请重新开始");
    }

    /** 把本人那份开表现场摆回共享段;本人没有(升级前开的表)就按原共享口径,返回 false */
    static boolean restoreTeamMemberChallenge(ObjectNode state, String game, Long memberId) {
        JsonNode own = state.path(TEAM_MEMBER_CHALLENGES).path(String.valueOf(memberId)).path(game);
        if (!own.isObject()) return false;
        ObjectNode mechanic = state.with(game);
        if (own.path("startedAtMs").isNumber()) mechanic.put("startedAtMs", own.path("startedAtMs").asLong()); else mechanic.remove("startedAtMs");
        if (own.path("attempt").isObject()) mechanic.set("attempt", own.path("attempt").deepCopy()); else mechanic.remove("attempt");
        return true;
    }

    /** 共享段上本人这次开表/提交后的现场存回本人名下;别人的挑战身份不会被存进来 */
    static void saveTeamMemberChallenge(ObjectNode state, String game, Long memberId) {
        JsonNode mechanic = state.path(game);
        ObjectNode own = state.with(TEAM_MEMBER_CHALLENGES).with(String.valueOf(memberId)).putObject(game);
        if (mechanic.path("startedAtMs").isNumber()) own.put("startedAtMs", mechanic.path("startedAtMs").asLong());
        JsonNode attempt = mechanic.path("attempt");
        if (attempt.isObject() && memberId != null && memberId.longValue() == attempt.path("memberId").asLong(0L)) own.set("attempt", attempt.deepCopy());
    }

    static void consumeAttempt(JsonNode node) {
        JsonNode attempt = node.path("attempt");
        if (attempt.isObject()) ((ObjectNode) attempt).put("used", true);
    }

    /**
     * 本人、未用、未过期的挑战身份才下发;别人的(队伍共享局)、交过的、过期的一律不给。
     * 按成员各存的那份(队伍共享局 / 归零挑战)优先,共享段上可能是队友刚开的那份。
     */
    void putAttempt(Map<String, Object> value, JsonNode state, String game, Long actorMemberId) {
        String member = String.valueOf(actorMemberId);
        JsonNode attempt = state.path(TEAM_MEMBER_CHALLENGES).path(member).path(game).path("attempt");
        if (!attempt.isObject()) attempt = state.path("zeroChallengeAttempts").path(member).path(game);
        if (!attempt.isObject()) attempt = state.path(game).path("attempt");
        if (!attempt.isObject() || attempt.path("used").asBoolean(false) || actorMemberId == null
                || actorMemberId.longValue() != attempt.path("memberId").asLong(0L)
                || clock.now().getTime() > attempt.path("expiresAtMs").asLong(0L)) return;
        value.put("attemptId", attempt.path("id").asLong());
        value.put("attemptExpiresAtMs", attempt.path("expiresAtMs").asLong());
    }

    static String challengeKey(Map<String, Object> payload) {
        String game = StringUtils.defaultString(String.valueOf(payload.get("game"))).trim();
        switch (game) {
            case "reaction": case "ballShake": case "quietHold":
            // 喊一嗓子(现场感契约 §4):与 quietHold 同路,先开表再按服务器时间复核
            case "shout":
            case "countdown": case "stopwatch":
            // 罗盘没有计时,开表只为领 C1 挑战身份;不开表的存量客户端照旧能交
            case "compass":
            // 预制人生限时打字:和上面五个同路,先开表再提交
            case "typeIn":
                return game;
            default:
                throw new IllegalArgumentException("这一项不支持开始计时");
        }
    }

    /** 从 START_CHALLENGE 到现在,服务端这边真的过去了多少毫秒。没开始过就报错。 */
    private long serverElapsedMs(ObjectNode node, String label) {
        long startedAt = node.path("startedAtMs").asLong(0L);
        if (startedAt <= 0L) throw new IllegalStateException(label + "还没开始");
        long elapsed = clock.now().getTime() - startedAt;
        if (elapsed < 0L) throw new IllegalStateException("时间不对");
        return elapsed;
    }

    private void submitReaction(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("reaction");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启变色就点");
        ObjectNode node = state.with("reaction");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "变色就点");

        Object raw = payload.get("roundsMs");
        if (!(raw instanceof List)) throw new IllegalArgumentException("请提交每一轮的反应毫秒");
        List<?> rounds = (List<?>) raw;
        if (rounds.isEmpty()) throw new IllegalArgumentException("请提交每一轮的反应毫秒");
        if (rounds.size() != cfg.path("rounds").asInt(0)) {
            throw new IllegalArgumentException("请完成配置的全部轮次后提交");
        }
        long sum = 0L;
        long best = Long.MAX_VALUE;
        for (Object one : rounds) {
            long ms;
            try {
                ms = Long.parseLong(String.valueOf(one).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("反应毫秒必须是整数");
            }
            // 比人的极限还快的那一轮不是反应,是提前按下去的
            if (ms < MIN_HUMAN_REACTION_MS) throw new IllegalArgumentException("有一轮抢跑了");
            sum += ms;
            best = Math.min(best, ms);
        }
        // 几轮加起来比这一局真实用时还长 —— 这些数不可能是这一局产生的
        if (sum > elapsed) throw new IllegalArgumentException("成绩与用时对不上");

        long average = Math.round((double) sum / rounds.size());
        node.put("averageMs", average);
        node.put("bestMs", best);
        node.put("submitted", true);
        boolean passed = best <= cfg.path("goalMs").asLong(Long.MAX_VALUE);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    private void submitBallShake(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("ballShake");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启弹球");
        ObjectNode node = state.with("ballShake");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "弹球");

        long hits = requireNonNegativeLong(payload, "hits", "撞击次数必须是非负整数");
        long ceiling = Math.max(1L, elapsed * MAX_SHAKE_HITS_PER_SECOND / 1000L);
        if (hits > ceiling) throw new IllegalArgumentException("撞击次数与用时对不上");
        if (cfg.path("timed").asBoolean(false)) {
            long limitMs = cfg.path("seconds").asLong(0L) * 1000L;
            if (limitMs > 0L && elapsed > limitMs + CLOCK_SLACK_MS) {
                throw new IllegalStateException("已经超时了");
            }
        }
        boolean passed = hits >= cfg.path("goal").asLong(Long.MAX_VALUE);
        node.put("hits", hits);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    private void submitQuietHold(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("quietHold");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启安静挑战");
        ObjectNode node = state.with("quietHold");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "安静挑战");

        long heldMs = requireNonNegativeLong(payload, "heldMs", "安静时长必须是非负整数");
        if (heldMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("安静时长与用时对不上");
        boolean passed = heldMs >= cfg.path("seconds").asLong(Long.MAX_VALUE) * 1000L;
        node.put("heldMs", heldMs);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    /**
     * 罗盘指向:客户端报它对准后保持住了,服务端只复核「报来的方位确实在容差内」这一条。
     *
     * <p>★ 这层是**防误发,不是防作弊**(现场感契约 §3.3):改客户端代码就能伪造方位角,
     * 伪造成本与计步同档 —— 所以这一段的奖励只许是 xp,禁止挂到店凭证或任何有金额的完成条件。
     * 没有次数上限、没有对错:对不准就一直转,这一屏的完成口径是「对准过一次」。
     */
    private static void submitCompass(JsonNode config, ObjectNode state, Map<String, Object> payload,
            ScoreChange score) {
        JsonNode cfg = config.get("compass");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启罗盘指向");
        ObjectNode node = state.with("compass");
        if (node.path("done").asBoolean(false)) return;   // 对准过一次就不再收,也不重复发分
        long bearing = requireNonNegativeLong(payload, "bearing", "方位角须为 0 至 359 的整数");
        if (bearing > 359) throw new IllegalArgumentException("方位角须为 0 至 359 的整数");
        int tolerance = cfg.path("tolerance").asInt(0);
        if (angleDiff(bearing, cfg.path("bearing").asInt(0)) > tolerance) {
            throw new IllegalArgumentException("还没对准方向,再转一转");
        }
        node.put("bearing", bearing);
        node.put("done", true);
        int xp = cfg.path("xp").asInt(0);
        if (xp > 0 && !node.path("awarded").asBoolean(false)) {
            node.put("awarded", true);
            score.setScore(value(score.getScore()) + xp);
        }
    }

    /** 两方位角的最小夹角:罗盘绕 360,359° 与 1° 相差 2° 而不是 358°(契约 §6.2 钉死的口径)。 */
    private static int angleDiff(long a, int b) {
        long raw = Math.abs(a - (long) b) % 360;
        return (int) Math.min(raw, 360 - raw);
    }

    /**
     * 喊一嗓子(现场感契约 §4):与安静挑战同一条复核线 —— 客户端只交持续毫秒数,
     * 服务端拿 START_CHALLENGE 记的服务器时间验它没超发,够不够长由配置秒数判。
     * 录音不上传、不落盘:这一屏要的只有每帧振幅的累计结果,不要内容。
     */
    private void submitShout(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("shout");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启喊一嗓子");
        ObjectNode node = state.with("shout");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "喊一嗓子");

        long heldMs = requireNonNegativeLong(payload, "heldMs", "喊的时长必须是非负整数");
        if (heldMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("喊的时长与用时对不上");
        boolean passed = heldMs >= cfg.path("seconds").asLong(Long.MAX_VALUE) * 1000L;
        node.put("heldMs", heldMs);
        node.put("submitted", true);
        node.put("passed", passed);
        if (passed) awardOnce(node);
    }

    /** 倒计时不收客户端报的数:等没等够由服务端自己算 —— 报数这件事在这一屏上没有意义。 */
    private void submitCountdown(JsonNode config, ObjectNode state) {
        JsonNode cfg = config.get("countdown");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启倒计时");
        ObjectNode node = state.with("countdown");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "倒计时");
        long needMs = cfg.path("seconds").asLong(0L) * 1000L;
        if (elapsed + CLOCK_SLACK_MS < needMs) throw new IllegalStateException("还没到点");
        node.put("submitted", true);
        node.put("passed", true);
        awardOnce(node);
    }

    private void submitStopwatch(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.get("stopwatch");
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启精准停表");
        ObjectNode node = state.with("stopwatch");
        if (node.path("submitted").asBoolean(false)) throw new IllegalStateException("这一局已经交过了");
        long elapsed = serverElapsedMs(node, "精准停表");

        long stoppedMs = requireNonNegativeLong(payload, "stoppedMs", "停表毫秒必须是非负整数");
        if (stoppedMs > elapsed + CLOCK_SLACK_MS) throw new IllegalArgumentException("停表成绩与用时对不上");

        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        long targetMs = Math.round(cfg.path("targetSeconds").asDouble(0d) * 1000d);
        long diff = Math.abs(stoppedMs - targetMs);
        boolean hit = diff <= cfg.path("toleranceMs").asLong(0L);
        node.put("lastDiffMs", diff);
        long best = node.path("bestDiffMs").asLong(Long.MAX_VALUE);
        if (diff < best) node.put("bestDiffMs", diff);

        int allowed = cfg.path("tries").asInt(0);
        if (hit) {
            node.put("submitted", true);
            node.put("passed", true);
            awardOnce(node);
        } else if (allowed > 0 && attempts >= allowed) {
            node.put("submitted", true);
            node.put("passed", false);
        } else {
            // 还有次数就不收摊,但重新开始要再报一次 START_CHALLENGE
            node.remove("startedAtMs");
        }
    }

    /** 一局只记一次「拿到了」。重复提交不重复发 —— 发奖的真动作在完成关卡那一步。 */
    static void awardOnce(ObjectNode node) {
        if (node.path("awarded").asBoolean(false)) return;
        node.put("awarded", true);
    }

    private static long requireNonNegativeLong(Map<String, Object> payload, String key, String message) {
        Object raw = payload.get(key);
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
        if (value < 0L) throw new IllegalArgumentException(message);
        return value;
    }

    static boolean enabled(JsonNode node) { return node != null && node.path("enabled").asBoolean(false); }

    static JsonNode findById(JsonNode array, String id) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode item : array) if (id.equals(item.path("id").asText())) return item;
        return null;
    }

    static boolean containsText(ArrayNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    static int value(Integer number) { return number == null ? 0 : number; }

    /** 引导/终局题组复用真实判题，只返回事实，不写余额、奖励或尝试记录。 */
    public static boolean verifyObjectiveAnswers(JsonNode config, JsonNode answers) {
        if (config == null || !config.isObject() || answers == null || !answers.isObject()) throw new IllegalArgumentException("请提交完整题组答案");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode state = mapper.createObjectNode();
        ScoreChange score = new ScoreChange(0);
        boolean found = false;
        Iterator<Map.Entry<String, JsonNode>> fields = config.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!enabled(field.getValue())) continue;
            String kind = field.getKey();
            if (Arrays.asList("timer", "leaderboard", "multiplayer", "timeWindow", "blindTaste", "silentOrder", "diyName", "musicCorner", "dailySign", "slowTask", "random", "branch").contains(kind)) continue;
            if (!Arrays.asList("qa", "sort", "match", "classify").contains(kind)) throw new IllegalArgumentException("引导只支持客观题组");
            JsonNode answer = answers.get(kind);
            if (answer == null || !answer.isObject() || answer.has("passed")) throw new IllegalArgumentException("请提交完整题组答案，不接受客户端通过标记");
            Map<String, Object> payload = mapper.convertValue(answer, new TypeReference<Map<String,Object>>(){});
            if ("qa".equals(kind)) {
                if (!Arrays.asList("TYPE", "PICK").contains(field.getValue().path("mode").asText())) throw new IllegalArgumentException("引导问答须有服务端答案");
                submitQa(config, state, payload, score);
            } else if ("sort".equals(kind)) submitSort(config, state, payload, score);
            else if ("match".equals(kind)) submitMatch(config, state, payload, score);
            else submitClassify(config, state, payload, score);
            found = true;
        }
        if (answers.has("passed")) throw new IllegalArgumentException("不接受客户端通过标记");
        if (!found) throw new IllegalArgumentException("节点没有客观题组");
        for (String kind : Arrays.asList("qa", "sort", "match", "classify")) if (enabled(config.get(kind)) && !state.path(kind).path("passed").asBoolean()) return false;
        return true;
    }

    public static List<String[]> verifiedObjectiveChoices(JsonNode config,JsonNode answers) {
        if(!verifyObjectiveAnswers(config,answers))throw new IllegalArgumentException("题组尚未通过可信判定");
        ObjectMapper mapper = new ObjectMapper();List<String[]> choices=new ArrayList<>();
        for(String kind:Arrays.asList("qa","sort","match","classify"))if(enabled(config.get(kind))) {
            if("qa".equals(kind)) {ObjectNode state=mapper.createObjectNode();ScoreChange score = new ScoreChange(0);
                for(ChoiceRef ref:submitQa(config,state,mapper.convertValue(answers.path("qa"),new TypeReference<Map<String,Object>>(){}),score))if(ref!=null)choices.add(new String[]{ref.stepOrKit,ref.optionId});
            } else choices.add(new String[]{kind,null});
        }
        return choices;
    }

    private void draw(JsonNode config, ObjectNode state) {
        JsonNode randomConfig = config.get("random");
        if (!enabled(randomConfig)) throw new IllegalArgumentException("该玩法未开启盲盒");
        ArrayNode draws = state.withArray("draws");
        int drawCount = randomConfig.path("drawCount").asInt();
        if (draws.size() >= drawCount) throw new IllegalStateException("盲盒已经抽完");
        List<JsonNode> remaining = new ArrayList<>();
        int totalWeight = 0;
        for (JsonNode item : randomConfig.path("items")) {
            if (!containsId(draws, item.path("id").asText())) {
                remaining.add(item);
                totalWeight += item.path("weight").asInt();
            }
        }
        int hit = random.nextInt(totalWeight);
        JsonNode selected = remaining.get(remaining.size() - 1);
        int cursor = 0;
        for (JsonNode item : remaining) {
            cursor += item.path("weight").asInt();
            if (hit < cursor) { selected = item; break; }
        }
        draws.add(selected.deepCopy());
    }

    private static boolean containsId(ArrayNode array, String id) {
        for (JsonNode item : array) if (id.equals(item.path("id").asText())) return true;
        return false;
    }
}
