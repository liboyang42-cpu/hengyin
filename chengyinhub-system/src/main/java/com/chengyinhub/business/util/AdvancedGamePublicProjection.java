package com.chengyinhub.business.util;

import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 玩家列表页只读玩法能力摘要；绝不下发物品权重、分支跳转/得分或运行期角色分配。 */
public final class AdvancedGamePublicProjection {

    private static final Logger LOG = LoggerFactory.getLogger(AdvancedGamePublicProjection.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdvancedGamePublicProjection() {
    }

    public static String safeConfigJson(String source) {
        if (StringUtils.isBlank(source)) return null;
        try {
            JsonNode config = MAPPER.readTree(source);
            if (!config.isObject()) return null;
            ObjectNode safe = MAPPER.createObjectNode();
            safe.put("schemaVersion", config.path("schemaVersion").asInt(1));
            // 呈现方式(施工契约 §1.5):下发的必须是**最终生效值**(显式配的或默认表推出来的),
            // 这样客户端铺故事流时不用自己存一份默认表。会话视图同源调同一个 resolvePresent。
            safe.put("present", AdvancedGameConfigValidator.resolvePresent(config));
            copyTimer(safe, config.path("timer"));
            copyRandom(safe, config.path("random"));
            copyBranch(safe, config.path("branch"));
            copyLeaderboard(safe, config.path("leaderboard"));
            copyMultiplayer(safe, config.path("multiplayer"));
            // Figma v5.1 新玩法。白名单式逐字段 copy,不是"整段搬过去" ——
            // 这一层是玩家能看到什么的唯一闸门,盲品答案与签文池都卡在这里。
            copyTimeWindow(safe, config.path("timeWindow"));
            copyBlindTaste(safe, config.path("blindTaste"));
            copySilentOrder(safe, config.path("silentOrder"));
            copyDiyName(safe, config.path("diyName"));
            copyMusicCorner(safe, config.path("musicCorner"));
            copySteps(safe, config.path("steps"));
            copyDailySign(safe, config.path("dailySign"));
            copySlowTask(safe, config.path("slowTask"));
            copyEstimate(safe, config.path("estimate"));
            copyPricePair(safe, config.path("pricePair"));
            copyHiddenObject(safe, config.path("hiddenObject"));
            copyPredict(safe, config.path("predict"));
            // 决定类与挑战类八个(2026-09-22 现场感契约加了罗盘指向)。它们的秘密不在配置里(硬币面朝哪边、骰子几点、
            // 每轮等多久,都是运行时由服务端现生成的),所以整段可下发 ——
            // 但**仍然逐字段 copy**:这样以后往配置里加字段,默认是不下发,
            // 而不是跟着整段漏出去。白名单的价值就在这个默认值上。
            copyCoinFlip(safe, config.path("coinFlip"));
            copyDiceRoll(safe, config.path("diceRoll"));
            copyReaction(safe, config.path("reaction"));
            copyBallShake(safe, config.path("ballShake"));
            copyQuietHold(safe, config.path("quietHold"));
            copyCompass(safe, config.path("compass"));
            copyShout(safe, config.path("shout"));
            copyCountdown(safe, config.path("countdown"));
            copyStopwatch(safe, config.path("stopwatch"));
            copyQa(safe, config.path("qa"));
            copyScan(safe, config.path("scan"));
            // R3 三个新玩法:题面下发,答案(排序顺序 / 连线对 / 分类映射)全留在服务端。
            copySort(safe, config.path("sort"));
            copyMatch(safe, config.path("match"));
            copyClassify(safe, config.path("classify"));
            // R16 时间轴对照:两版题面原样下发(时间轴顺序就是题面),answer(标出哪几条)留在服务端。
            copyCompare(safe, config.path("compare"));
            // 预制人生四个新玩法。建档的加成(effects)、拍照审核的判定、留言的跨玩家内容
            // 都不在这一层的下发范围里 —— 白名单逐字段 copy,没写进来的默认不下发。
            copyProfile(safe, config.path("profile"));
            copyPhotoCheck(safe, config.path("photoCheck"));
            copyAlbum(safe, config.path("album"));
            copyNote(safe, config.path("note"));
            copyTypeIn(safe, config.path("typeIn"));
            // 旅程体验模组化:requires / effects 两段整段不下发(R12 裁决),check 只留
            // 非条件字段。条件键名(clue. / relation. / tag. / counter. / sys.)是剧透;
            // 客户端不做条件判定,locked / missingKeys / held / advantage 由 Encounter
            // 接口按本局状态给出。**仍然逐字段 copy**保证「没写进 copyXxx 的字段一律不下发」。
            copyCheck(safe, config.path("check"));
            // R22-SEC C2:模板上的老叙事段(enter / outcome / deliver / unlock / revisit / brief / route)
            // 不再投影 —— R18 后叙事真源是章节故事流,投影它们等于继续推销一份运行期没人读的死配置,
            // 还会误导「模板详情 = 叙事真源」。故事流叙事由 Encounter 按本局状态下发。
            return MAPPER.writeValueAsString(safe);
        } catch (Exception unusableConfig) {
            // R22-SEC S4:方向仍是 fail-closed(不下发),但不能再静默 —— 投影失败要么是数据坏了,
            // 要么是白名单 copy 有 bug,两者都需要一条可检索的信号。
            LOG.warn("高级玩法公开投影失败,该配置不下发:{}", unusableConfig.toString());
            return null;
        }
    }

    private static void copyAlbum(ObjectNode target, JsonNode source) {
        if (!source.path("enabled").asBoolean(false)) return;
        ObjectNode value = target.putObject("album");
        value.put("enabled", true);
        ArrayNode images = value.putArray("images");
        for (JsonNode image : source.path("images")) {
            ObjectNode row = images.addObject();
            row.put("url", image.path("url").asText(""));
            row.put("line", image.path("line").asText(""));
        }
    }

    private static void copyTimeWindow(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("timeWindow");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        value.put("openFrom", source.path("openFrom").asText(""));
        value.put("openTo", source.path("openTo").asText(""));
        putIfText(value, "eyebrow", source);
        putIfText(value, "title", source);
        putIfText(value, "subscribeTmplId", source);
    }

    /** ★ answerKey 绝不出现在这里 —— 下发即等于把答案给了客户端,这道题当场作废。 */
    private static void copyBlindTaste(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("blindTaste");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "steps", source);
        putIfText(value, "hint", source);
        // 配图是题面的一部分,必须下发;答案 answerKey 仍然留在服务端
        putIfText(value, "imageUrl", source);
        value.put("xp", source.path("xp").asInt(0));
        ArrayNode options = value.putArray("options");
        for (JsonNode option : source.path("options")) {
            ObjectNode safeOption = options.addObject();
            safeOption.put("key", option.path("key").asText(""));
            safeOption.put("label", option.path("label").asText(""));
        }
    }

    private static void copySilentOrder(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("silentOrder");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "rule", source);
        value.put("limitSeconds", source.path("limitSeconds").asInt(0));
    }

    private static void copyDiyName(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("diyName");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        value.put("maxLength", source.path("maxLength").asInt(16));
        ArrayNode suggestions = value.putArray("suggestions");
        for (JsonNode item : source.path("suggestions")) {
            if (item.isTextual()) suggestions.add(item.asText());
        }
    }

    private static void copyMusicCorner(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("musicCorner");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "trackName", source);
        putIfText(value, "audioUrl", source);
        value.put("durationSeconds", source.path("durationSeconds").asInt(0));
    }

    private static void copySteps(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("steps");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        value.put("goal", source.path("goal").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
        putIfText(value, "eyebrow", source);
    }

    /** ★ poems(签文池)绝不出现在这里 —— 一次领一条由服务端当天选,整池下发等于把明天的签提前给了。 */
    private static void copyDailySign(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("dailySign");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "signer", source);
        putIfText(value, "sealText", source);
    }

    /** ★ unlockText(隔天才该看到的那段发现)绝不出现在货架投影里 —— 商家在模板市场
     *  就能看到全文是没关系的?不:模板投影同样会下发给玩家端的列表页。 */
    private static void copySlowTask(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("slowTask");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "startLabel", source);
        putIfText(value, "waitHint", source);
        putIfText(value, "unlockLabel", source);
        value.put("waitDays", source.path("waitDays").asInt(1));
    }

    /**
     * 估数题的公开面:题干、单位、数轴量程。
     *
     * <p>★ {@code answer}、{@code tolerance} 与 {@code reveal} 一律不出服务端 ——
     * answer 是答案本身;tolerance 能反推出满分区间有多宽,配合几次试探就能定位答案;
     * reveal 文案里通常直接写着真值("一共 480 颗")。三者都只在判定后随结果返回。
     */
    private static void copyEstimate(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("estimate");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "unit", source);
        value.put("min", source.path("min").asDouble());
        value.put("max", source.path("max").asDouble());
        value.put("xp", source.path("xp").asInt(0));
    }

    /**
     * 比价的公开面:标题 + 每件商品的名字与配图。
     *
     * <p>★ {@code price} 一件都不下发。整个玩法就是"猜哪个贵",价格下发了它就只剩点击。
     * 已经判过的那一对的真实价格由运行时视图随结果返回,不走这里。
     */
    private static void copyPricePair(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("pricePair");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        value.put("xp", source.path("xp").asInt(0));
        value.put("maxTries", source.path("maxTries").asInt(0));
        ArrayNode items = value.putArray("items");
        for (JsonNode item : source.path("items")) {
            ObjectNode safeItem = items.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("name", item.path("name").asText(""));
            putIfText(safeItem, "imageUrl", item);
            // ★ correct 一个字都不下发:哪一张是对的正是这一屏要猜的东西
        }
    }

    /**
     * 找东西的公开面:图、要找几样、每样叫什么。
     *
     * <p>★ 热区的 x / y / r **一个都不下发**。这是本玩法唯一的防线 ——
     * 坐标到了客户端,抓包就知道东西藏在哪,题当场作废。
     * 下发 label 是有意的:玩家要知道自己在找什么(底部那排待找清单)。
     */
    private static void copyHiddenObject(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("hiddenObject");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "hint", source);
        putIfText(value, "imageUrl", source);
        value.put("xp", source.path("xp").asInt(0));
        value.put("maxTries", source.path("maxTries").asInt(0));
        value.put("total", source.path("hotspots").size());
        ArrayNode labels = value.putArray("targets");
        for (JsonNode spot : source.path("hotspots")) {
            ObjectNode safeSpot = labels.addObject();
            safeSpot.put("id", spot.path("id").asText(""));
            safeSpot.put("label", spot.path("label").asText(""));
        }
    }

    /**
     * 竞猜的公开面:题目、选项、截止小时,**整个下发**。
     *
     * <p>这一段与其他玩法的口径相反,但不是破例:竞猜的答案在玩家提交时
     * 还不存在(商家次日才给),所以配置里根本没有可藏的东西。
     * 真正的秘密是结算结果,它在 play_predict_result 里,不走这条投影。
     */
    /**
     * 问答的公开面:题干、附件、选项文案。
     *
     * <p>★ 打字题的 answerText 与选项题的 correct **一个字都不下发** ——
     * 那是这一题唯一的秘密,漏出去玩法就只剩点击。
     */
    private static void copyQa(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("qa");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        value.put("mode", source.path("mode").asText("TYPE"));
        putIfText(value, "title", source);
        putIfText(value, "lead", source);
        putIfText(value, "shotNote", source);
        putIfText(value, "imageUrl", source);
        putIfText(value, "audioUrl", source);
        value.put("maxTries", source.path("maxTries").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
        // 多选标记只在开启时给:老单选配置的投影形状保持一字不变,客户端缺省按单选渲染
        if (source.path("multi").asBoolean(false)) value.put("multi", true);
        ArrayNode options = value.putArray("options");
        for (JsonNode option : source.path("options")) {
            ObjectNode safeOption = options.addObject();
            safeOption.put("id", option.path("id").asText(""));
            safeOption.put("label", option.path("label").asText(""));
            // fb 也不下发:那句话是判完之后才该看到的,提前给等于提示答案
        }
    }

    /**
     * 排序题的公开面:题干 + 待排项(顺序见 displayOrder)。
     *
     * <p>★ {@code answerOrder} 一个字都不下发 —— 下发就等于把排好的答案交给客户端。
     */
    private static void copySort(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("sort");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "prompt", source);
        value.put("maxAttempts", source.path("maxAttempts").asInt(0));
        ArrayNode items = value.putArray("items");
        for (JsonNode item : displayOrder(source.path("items"))) {
            ObjectNode safeItem = items.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("label", item.path("label").asText(""));
            putIfText(safeItem, "img", item);
        }
    }

    /**
     * 连线题的公开面:题干 + 左右两组条目,右侧按 displayOrder 打乱。
     *
     * <p>★ {@code pairs} 一个字都不下发 —— 哪条连哪条正是这一屏要连的东西。
     */
    private static void copyMatch(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("match");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "prompt", source);
        ArrayNode left = value.putArray("left");
        for (JsonNode item : source.path("left")) {
            ObjectNode safeItem = left.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("label", item.path("label").asText(""));
        }
        ArrayNode right = value.putArray("right");
        for (JsonNode item : displayOrder(source.path("right"))) {
            ObjectNode safeItem = right.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("label", item.path("label").asText(""));
        }
    }

    /**
     * 分类题的公开面:题干 + 分类箱 + 待分类卡片。
     *
     * <p>★ {@code answer}(每张卡片进哪个箱)一个字都不下发 —— 那是这一题唯一的秘密。
     */
    private static void copyClassify(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("classify");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "prompt", source);
        ArrayNode bins = value.putArray("bins");
        for (JsonNode bin : source.path("bins")) {
            ObjectNode safeBin = bins.addObject();
            safeBin.put("id", bin.path("id").asText(""));
            safeBin.put("label", bin.path("label").asText(""));
        }
        ArrayNode items = value.putArray("items");
        for (JsonNode item : source.path("items")) {
            ObjectNode safeItem = items.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("label", item.path("label").asText(""));
        }
    }

    /**
     * 对照题的公开面:题干 + 左右两版,items 照**原顺序**下发。
     *
     * <p>与排序/连线不同,这里不打乱:时间轴顺序本身就是题面(两版逐条并排),
     * 打乱等于把题目毁了。
     *
     * <p>★ {@code answer}(该标出哪几条)一个字都不下发 —— 那是这一屏唯一的秘密。
     */
    private static void copyCompare(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("compare");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "prompt", source);
        value.put("maxAttempts", source.path("maxAttempts").asInt(0));
        copyCompareSide(value, "left", source.path("left"));
        copyCompareSide(value, "right", source.path("right"));
        // ★ answer 一个字都不下发
    }

    /** 对照题单侧:标题 + 时间轴条目(id / 时间 / 内容),顺序原样,不洗牌。 */
    private static void copyCompareSide(ObjectNode target, String field, JsonNode source) {
        ObjectNode side = target.putObject(field);
        putIfText(side, "label", source);
        ArrayNode items = side.putArray("items");
        for (JsonNode item : source.path("items")) {
            ObjectNode safeItem = items.addObject();
            safeItem.put("id", item.path("id").asText(""));
            safeItem.put("time", item.path("time").asText(""));
            safeItem.put("text", item.path("text").asText(""));
        }
    }

    /**
     * 排序 / 连线题面的展示顺序:固定种子洗牌。
     *
     * <p>种子只由 id 集合算出,与 answerOrder / pairs 无关 —— 同一个 id 集合每次得到
     * 同一个顺序,而布局与答案顺序无关。
     *
     * <p>为什么不按 id 字典序:商家用 1、2、3 这类按答案排好的 id 命名时,字典序恰好
     * 就是答案顺序,下发出去等于把答案印在题面上。固定种子洗牌让"顺序"变成 id 的函数,
     * 商家无从用它表达答案。
     *
     * <p>公开投影与运行期视图共用这个函数,保证玩家在列表页与游玩页看到的顺序一致。
     */
    public static java.util.List<JsonNode> displayOrder(JsonNode items) {
        java.util.List<JsonNode> ordered = new java.util.ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) ordered.add(item);
        }
        int seed = 17;
        for (JsonNode item : ordered) {
            seed = seed * 31 + item.path("id").asText("").hashCode();
        }
        java.util.Collections.shuffle(ordered, new java.util.Random(seed));
        return ordered;
    }

    /**
     * 扫码的公开面:只有「回什么形态」。
     *
     * <p>★ 回复内容本身不在这里下发 —— 它是扫完之后的奖励,扫之前就能看见的话,
     * 这一站的「到店」就没有意义了。真正的内容由运行时在扫完那一刻给。
     */
    private static void copyScan(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("scan");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        value.put("kind", source.path("kind").asText("TEXT"));
        value.put("xp", source.path("xp").asInt(0));
    }

    /**
     * check 的公开面:判定标识、技能与难度,以及 mods 的数值与说明。
     *
     * <p>★ mods[].when、advantageIf、disadvantageIf、failCostTag **不下发** ——
     * 条件里带 clue. / relation. / tag. / counter. / sys. 键名,这一层会进模板详情等
     * 无局场景,照拷等于把还没拿到的线索与关系提前剧透;客户端不做条件判定,
     * locked / missingKeys / held / advantage 由 Encounter 按本局状态给出。
     *
     * <p>★ successText / failText / successEffects / failEffects 同样不下发 ——
     * 那是判定之后才该知道的结果,结算后由服务端回执给出。
     */
    private static void copyCheck(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("check");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "checkId", source);
        putIfText(value, "skill", source);
        putIfText(value, "tier", source);
        ArrayNode mods = value.putArray("mods");
        for (JsonNode mod : source.path("mods")) {
            ObjectNode safeMod = mods.addObject();
            safeMod.put("value", mod.path("value").asInt(0));
            putIfText(safeMod, "label", mod);
        }
    }

    private static void copyPredict(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("predict");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "question", source);
        putIfText(value, "hint", source);
        value.put("closeAtHour", source.path("closeAtHour").asInt(0));
        value.put("revealDays", source.path("revealDays").asInt(1));
        value.put("revealHour", source.path("revealHour").asInt(20));
        value.put("xp", source.path("xp").asInt(0));
        ArrayNode options = value.putArray("options");
        for (JsonNode option : source.path("options")) {
            ObjectNode safeOption = options.addObject();
            safeOption.put("key", option.path("key").asText(""));
            safeOption.put("label", option.path("label").asText(""));
        }
    }

    /**
     * 抛硬币的公开面:标题 + 正反两面各叫什么、各要做什么。
     *
     * <p>两面的约定<b>必须</b>在抛之前就让人看见 —— 这个玩法就是"先讲好赌注,再抛"。
     * 藏起来反而是另一个游戏。真正的秘密是本次朝上的那一面,它由服务端在
     * {@code FLIP_COIN} 时生成,不在配置里。
     */
    private static void copyCoinFlip(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("coinFlip");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("xp", source.path("xp").asInt(0));
        copyCoinFace(value, "heads", source.path("heads"));
        copyCoinFace(value, "tails", source.path("tails"));
    }

    private static void copyCoinFace(ObjectNode target, String field, JsonNode source) {
        ObjectNode face = target.putObject(field);
        putIfText(face, "label", source);
        putIfText(face, "action", source);
    }

    /**
     * 掷骰子的公开面:标题、几颗、六个面各写着什么事。
     *
     * <p>六个面同样必须先看见 —— 不然玩家不知道自己在赌什么。
     * 本次点数由服务端在 {@code ROLL_DICE} 时生成。
     */
    private static void copyDiceRoll(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("diceRoll");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        if ("d20".equals(source.path("mode").asText())) {
            value.put("mode", "d20");
            value.put("dc", source.path("dc").asInt());
            value.put("modifier", source.path("modifier").asInt());
            putIfText(value, "rollMode", source);
            // 成功和失败文案仅在结算后，由对应的服务端回执揭示。
            return;
        }
        value.put("diceCount", source.path("diceCount").asInt(1));
        value.put("xp", source.path("xp").asInt(0));
        ArrayNode faces = value.putArray("faces");
        for (JsonNode face : source.path("faces")) {
            if (face.isTextual()) faces.add(face.asText());
        }
    }

    /**
     * 变色就点的公开面:轮数与达标毫秒。
     *
     * <p>★ 每轮的等待时长<b>不在配置里,也绝不下发</b> —— 它由服务端每轮现随机
     * (1.4–4.2 秒)。下发了就等于告诉客户端什么时候会变色,那这一屏测的
     * 不再是反应,是定时器。
     */
    private static void copyReaction(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("reaction");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("rounds", source.path("rounds").asInt(0));
        value.put("goalMs", source.path("goalMs").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /** 弹球的公开面:撞几次、限不限时、限多久。没有秘密字段。 */
    private static void copyBallShake(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("ballShake");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("goal", source.path("goal").asInt(0));
        boolean timed = source.path("timed").asBoolean(false);
        value.put("timed", timed);
        // 不限时那档不给 seconds:给了客户端会照着画一条倒计时条,而这一档本来不催
        if (timed) value.put("seconds", source.path("seconds").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /** 安静挑战的公开面:安静多久。阈值由客户端开局前现场校准,不由配置定。 */
    private static void copyQuietHold(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("quietHold");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        putIfText(value, "sub", source);
        value.put("seconds", source.path("seconds").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /** 罗盘指向的公开面(现场感契约 §5.1,注册表第五张):目标方位就是题面,与
     *  运行时下发同源;判定的「对准」由客户端现场做,配置里没有秘密可藏。 */
    private static void copyCompass(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("compass");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("bearing", source.path("bearing").asInt(0));
        value.put("tolerance", source.path("tolerance").asInt(15));
        value.put("holdSeconds", source.path("holdSeconds").asInt(3));
        putIfText(value, "hint", source);
        value.put("xp", source.path("xp").asInt(0));
    }

    /** 喊一嗓子的公开面(现场感契约 §5.1,注册表第六张):与 quietHold 同形但无 sub;
     *  阈值不在配置里(开局现场校准),所以白名单里没有可漏的秘密。 */
    private static void copyShout(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("shout");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("seconds", source.path("seconds").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /** 倒计时的公开面:倒多久、到点说什么。它不附加判定,整段都是给人看的。 */
    private static void copyCountdown(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("countdown");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        putIfText(value, "doneText", source);
        value.put("seconds", source.path("seconds").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /**
     * 精准停表的公开面:目标秒数、容差、能停几次。
     *
     * <p>容差在这个玩法里<b>不是</b>秘密:它是规则的一部分("差 0.3 秒以内算准"),
     * 玩家得先知道才谈得上瞄准。这一点与估数相反 —— 那边容差能反推出答案区间。
     */
    private static void copyStopwatch(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("stopwatch");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "kicker", source);
        value.put("targetSeconds", source.path("targetSeconds").asDouble(0d));
        value.put("toleranceMs", source.path("toleranceMs").asInt(0));
        value.put("tries", source.path("tries").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
    }

    /**
     * 建档的公开面:问题与选项文案。
     *
     * <p>★ {@code options[].effects} 一个字都不下发 —— 数值加成是暗的,露出来就成了攻略表。
     * 加成由服务端在建档提交时按旅程 effects 执行,玩家只看到「选了什么」。
     */
    private static void copyProfile(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("profile");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "lead", source);
        JsonNode avatar = source.get("avatar");
        if (avatar != null && avatar.isObject()) {
            ObjectNode avatarView = value.putObject("avatar");
            boolean avatarOn = avatar.path("enabled").asBoolean(false);
            avatarView.put("enabled", avatarOn);
            avatarView.put("required", avatarOn && avatar.path("required").asBoolean(false));
        }
        ArrayNode questions = value.putArray("questions");
        for (JsonNode question : source.path("questions")) {
            ObjectNode safeQuestion = questions.addObject();
            safeQuestion.put("key", question.path("key").asText(""));
            safeQuestion.put("label", question.path("label").asText(""));
            String kind = question.path("kind").asText("");
            safeQuestion.put("kind", kind);
            safeQuestion.put("required", true);
            if ("text".equals(kind)) safeQuestion.put("maxLength", question.path("maxLength").asInt(12));
            JsonNode options = question.get("options");
            if (options == null || !options.isArray()) continue;
            ArrayNode safeOptions = safeQuestion.putArray("options");
            for (JsonNode option : options) {
                ObjectNode safeOption = safeOptions.addObject();
                safeOption.put("key", option.path("key").asText(""));
                safeOption.put("label", option.path("label").asText(""));
                // ★ effects 不下发
            }
        }
    }

    /**
     * 拍照审核的公开面:题面 + requirement(要拍到什么,玩家得知道)+ 置信度阈值、次数与兜底。
     * 判定全在服务端视觉模型,旧版客户端启发式的 rule 已不存在。
     */
    private static void copyPhotoCheck(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("photoCheck");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "shotNote", source);
        putIfText(value, "requirement", source);
        // 取景轮廓属于题面（要拍到什么的形状），与 requirement 同档、没配就不下发这个键。
        // 不补这一行的后果：同一份配置在公开投影里没框、在到点投影里有框，两条投影各说一套。
        putIfText(value, "frameUrl", source);
        // ⚠️ 这里必须自己夹一次 0–100:validator 的 normalizeFrameOpacity 遇到非数字节点
        // 直接 return，于是 {"frameOpacity":"9999"} 这种能原样落库；到点投影与前端都夹过，
        // 只有这条公开投影不夹的话，两条投影就会各吐一套值。
        if (value.has("frameUrl")) {
            value.put("frameOpacity", Math.max(0, Math.min(100, source.path("frameOpacity").asInt(40))));
        }
        value.put("minConfidence", source.path("minConfidence").asInt(60));
        value.put("maxTries", source.path("maxTries").asInt(0));
        value.put("fallback", source.path("fallback").asText("retake"));
        /* 拍物成卡(立体藏品卡契约 §2.1):这里是**白名单逐字段 copy**,漏一行就等于
           mode / 卡名 / 样式在这条链路上不存在 —— 采用别人模板时商家配的是藏品卡,
           落到自己店里成了普通拍照审核,而且两边都不报错。 */
        putIfText(value, "mode", source);
        putIfText(value, "cardTitle", source);
        putIfText(value, "cardStyle", source);
        value.put("xp", source.path("xp").asInt(0));
    }

    /**
     * 留言的公开面:题面与预设短句。**别人的留言不在这里** ——
     * 那是运行时按本局节点现查 play_note 才给的,货架投影里没有可给的内容。
     */
    private static void copyNote(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("note");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        putIfText(value, "prompt", source);
        value.put("maxLength", source.path("maxLength").asInt(40));
        value.put("showPrevious", source.path("showPrevious").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
        ArrayNode presets = value.putArray("presets");
        for (JsonNode preset : source.path("presets")) {
            if (preset.isTextual()) presets.add(preset.asText());
        }
    }

    /** 限时打字的公开面:target 就是题面,判定(内容相等 + 用时)在服务端。 */
    private static void copyTypeIn(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("typeIn");
        boolean on = source.path("enabled").asBoolean(false);
        value.put("enabled", on);
        if (!on) return;
        putIfText(value, "title", source);
        value.put("target", source.path("target").asText(""));
        value.put("seconds", source.path("seconds").asInt(0));
        value.put("caseSensitive", source.path("caseSensitive").asBoolean(false));
        value.put("tries", source.path("tries").asInt(0));
        value.put("xp", source.path("xp").asInt(0));
        // 终端样式(原型 hello world 开机):纯外观,不参与判定
        value.put("terminalSkin", source.path("terminalSkin").asBoolean(false));
    }

    private static void putIfText(ObjectNode target, String field, JsonNode source) {
        JsonNode node = source.path(field);
        if (node.isTextual() && !node.asText().isEmpty()) target.put(field, node.asText());
    }

    private static void copyTimer(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("timer");
        value.put("enabled", source.path("enabled").asBoolean(false));
        if (source.path("enabled").asBoolean(false)) value.put("durationSeconds", source.path("durationSeconds").asInt());
    }

    private static void copyRandom(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("random");
        value.put("enabled", source.path("enabled").asBoolean(false));
        if (source.path("enabled").asBoolean(false)) {
            // 卡池名是给玩家看的,不是秘密;牌堆内容(items)才一件都不投影
            value.put("deckName", source.path("deckName").asText(""));
            value.put("drawCount", source.path("drawCount").asInt());
        }
    }

    private static void copyBranch(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("branch");
        value.put("enabled", source.path("enabled").asBoolean(false));
    }

    private static void copyLeaderboard(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("leaderboard");
        value.put("enabled", source.path("enabled").asBoolean(false));
        if (source.path("enabled").asBoolean(false)) value.put("metric", source.path("metric").asText("SCORE"));
    }

    private static void copyMultiplayer(ObjectNode target, JsonNode source) {
        ObjectNode value = target.putObject("multiplayer");
        value.put("enabled", source.path("enabled").asBoolean(false));
        if (!source.path("enabled").asBoolean(false)) return;
        value.put("mode", source.path("mode").asText("SEQUENTIAL"));
        value.put("assignment", source.path("assignment").asText("AUTO"));
        value.put("minPlayers", source.path("minPlayers").asInt());
        value.put("maxPlayers", source.path("maxPlayers").asInt());
        value.put("requiredTurns", source.path("requiredTurns").asInt(1));
    }
}
