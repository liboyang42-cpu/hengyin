package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chengyinhub.common.utils.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 高级玩法 schemaVersion=1 的单一配置校验入口。 */
public class AdvancedGameConfigValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 64 * 1024;

    private static final Set<String> TIMEOUT_RESULTS = setOf("FAILED", "EXPIRED");
    private static final Set<String> LEADERBOARD_METRICS = setOf(
            "ELAPSED_TIME", "SCORE", "COMPLETED_UNITS");
    private static final Set<String> LEADERBOARD_SCOPES = setOf("ACTIVITY", "TOPIC");
    private static final Set<String> MULTIPLAYER_MODES = setOf("SEQUENTIAL", "ROLE_BASED");
    private static final Set<String> ASSIGNMENTS = setOf("AUTO", "LEADER");
    /** HH:mm,24 小时制;跨夜(openFrom > openTo)是合法的,午夜电台就是这种 */
    private static final Pattern CLOCK = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final int MIN_BLIND_OPTIONS = 2;
    private static final int MAX_BLIND_OPTIONS = 6;
    /** 估数数轴的最大跨度。再宽滑块一格就跳几百,玩家没法瞄准 */
    private static final double MAX_ESTIMATE_SPAN = 1000000d;
    /** 比价:3 件起(2 件只有一种组合,连不成击);8 件封顶,再多商家填不动 */
    private static final int MIN_PRICE_ITEMS = 3;
    private static final int MAX_PRICE_ITEMS = 8;
    /** 价格单位是分,上限 9999.99 元 */
    private static final long MAX_PRICE_CENTS = 999999L;
    private static final int MAX_PRICE_XP_PER_HIT = 200;
    /** 问答三种模式。三种差的只是中间那一块,所以是一段带 mode,不是三段 */
    private static final Set<String> QA_MODES = new HashSet<>(Arrays.asList("TYPE", "PICK", "SHOT"));
    /** 扫码回什么。OVERLAY 是第四档:页内取景扫中后把图叠在镜头里(AR 契约 §2.2) */
    // NONE = 只扫码进场、不回任何内容(2026-10-22 主人:扫码参与可以不回复)。
    private static final Set<String> SCAN_KINDS = new HashSet<>(Arrays.asList("TEXT", "VOICE", "IMAGE", "OVERLAY", "NONE"));
    /** 显形档的真 AR(2026-09-22 用户定):不开 / 平面放置 / 图像识别。xr-frame 不支持运行中切换模式,所以是配置期二选一。 */
    private static final Set<String> SCAN_AR_MODES = new HashSet<>(Arrays.asList("NONE", "PLANE", "MARKER"));
    /** 找东西:3 个起(少于 3 个没有"还差几样"的张力),5 个封顶(商家标不动,玩家也找烦了) */
    private static final int MIN_HOTSPOTS = 3;
    private static final int MAX_HOTSPOTS = 5;
    /** 热区半径按图宽的比例算。0.03 已接近手指精度下限,0.15 再大就等于半张图都是答案 */
    private static final double MIN_HOTSPOT_RADIUS = 0.03d;
    private static final double MAX_HOTSPOT_RADIUS = 0.15d;
    /** 竞猜:2 个起(只有一个选项不叫猜),4 个封顶(手机上一屏排得下) */
    /** 人的反应极限。比这还快的门槛谁也过不去,比这还快的成绩也不可能是真按出来的 */
    private static final int MIN_HUMAN_REACTION_MS = 120;
    private static final int MAX_REACTION_MS = 3000;
    private static final int MAX_REACTION_ROUNDS = 10;
    private static final int MAX_SHAKE_GOAL = 200;
    /** 挑战类的时长上限。再长人就走开了,而会话本身也有超时 */
    private static final int MAX_CHALLENGE_SECONDS = 300;
    private static final int MAX_COUNTDOWN_SECONDS = 3600;
    private static final double MAX_STOPWATCH_TARGET_SECONDS = 120d;
    /** 骰子六个面,一面都不能少 —— 缺一条就有一面掷出来没内容 */
    private static final int DICE_FACES = 6;
    private static final int MIN_PREDICT_OPTIONS = 2;
    private static final int MAX_PREDICT_OPTIONS = 4;
    /** 旅程体验模组化:具名状态键。前缀即类型(键类型只由前缀表达),点号后须小写字母开头 */
    private static final Pattern STATE_VAR =
            Pattern.compile("^(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}$");
    private static final Pattern STATE_TAG = Pattern.compile("^tag\\.[a-z][a-z0-9_]{0,47}$");
    /** 条件 op:六种数值比较、HAS_TAG 问 tag 在不在、NODE_COMPLETED 问节点做过没有 */
    private static final Set<String> COMPARISON_OPS = setOf("EQ", "NE", "GT", "GTE", "LT", "LTE");
    /** 效果 op:SET 置位、INC 加减、ADD_TAG 挂 tag */
    private static final Set<String> EFFECT_OPS = setOf("SET", "INC", "ADD_TAG");
    /** 判定难度只有三档。禁止任意 DC 数字 —— 数字 DC 会把平衡问题摊给每个商家,三档才是产品能调的 */
    private static final Set<String> CHECK_TIERS = setOf("easy", "medium", "hard");
    private static final Pattern CHECK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * 阶段 4 自适应难度:variants[].relax 允许触碰的字段白名单。
     *
     * <p>只收**看不见的容错面**(时限、可试次数)。题面(题目数量 / 内容 / 答案 / 奖励分)一个字都不许碰
     * —— 城瘾是线下真人体验,玩家会互相对比「你那关怎么只有 3 道题」,题面不同当场穿帮;
     * 容错面不可见,所以调了也不伤体验。
     *
     * <p>值语义分两类,因为**次数类字段 0 = 不限**,大小比较会把方向判反:
     * <ul>
     *   <li>{@code SECONDS} 时长:只接受 {@code +N}(变长)。{@code =N} 一律拒 —— 发布期不知道基准值,
     *       证明不了它是放宽。</li>
     *   <li>{@code TOLERANCE} 判定误差 / 达标线(猜数字 / 罗盘 / 停表 / 变色就点):只接受 {@code +N}(放大)。</li>
     *   <li>{@code LOWER} 越小越容易(坚持几秒 / 等几秒 / 撞几次):只接受 {@code -N}。减到 kit 下界以下由
     *       requireRelaxedStillValid 整份复核拒掉。</li>
     *   <li>{@code ATTEMPTS} 次数:接受 {@code +N}(次数变多)与 {@code =0}(改成不限)。
     *       {@code =K}(K≠0)拒 —— 把「不限」改成有限次数是收紧,而它的数字反而更大。</li>
     * </ul>
     */
    private static final Map<String, String> RELAX_FIELDS = new LinkedHashMap<String, String>();
    static {
        RELAX_FIELDS.put("timer.durationSeconds", "SECONDS");
        RELAX_FIELDS.put("typeIn.seconds", "SECONDS");
        // musicCorner.durationSeconds 不在此列:它只是播放器进度条上的曲目时长,音乐角不判定通关,
        // 「放宽」它只会让进度条走不满,难度一点没变(2026-09-23 走查 W-01)。
        RELAX_FIELDS.put("sort.maxAttempts", "ATTEMPTS");
        RELAX_FIELDS.put("pricePair.maxTries", "ATTEMPTS");
        RELAX_FIELDS.put("typeIn.tries", "ATTEMPTS");
        RELAX_FIELDS.put("qa.maxTries", "ATTEMPTS");
        RELAX_FIELDS.put("hiddenObject.maxTries", "ATTEMPTS");
        RELAX_FIELDS.put("stopwatch.tries", "ATTEMPTS");
        // photoCheck.maxTries 不在此列:它是「次数用完后放不放过」的触发点,不是容错面 ——
        // 放过他时多给次数只是推迟放行,重拍时次数根本不生效(2026-09-23)。
        RELAX_FIELDS.put("estimate.tolerance", "TOLERANCE");
        RELAX_FIELDS.put("compass.tolerance", "TOLERANCE");
        RELAX_FIELDS.put("stopwatch.toleranceMs", "TOLERANCE");
        // 2026-09-24 补齐挑战类:越大越容易的走 SECONDS / TOLERANCE,越小越容易的走 LOWER(只能 -N)
        RELAX_FIELDS.put("ballShake.seconds", "SECONDS");        // 限时模式的时长
        RELAX_FIELDS.put("reaction.goalMs", "TOLERANCE");        // 达标反应毫秒:放大 = 更容易达标
        RELAX_FIELDS.put("quietHold.seconds", "LOWER");          // 要安静坚持几秒
        RELAX_FIELDS.put("shout.seconds", "LOWER");              // 要连续喊几秒
        RELAX_FIELDS.put("countdown.seconds", "LOWER");          // 要等几秒
        RELAX_FIELDS.put("compass.holdSeconds", "LOWER");        // 对准后要稳住几秒
        RELAX_FIELDS.put("ballShake.goal", "LOWER");             // 要撞几次
    }
    /**
     * 放宽的封顶值 —— 运行期加完要夹在这里,否则 {@code +99999} 会产出一份各 kit 校验器
     * 自己都不接受的配置。⚠️ 这些数字与 validateTimer / validateSort / validatePricePair /
     * validateTypeIn / validateMusicCorner 里的上界是**两处记录**,改那边要同步改这里;
     * {@code relaxBoundsMatchKitValidators} 这条测试就是为此而存在的见证人。
     */
    // 放宽的上界**不在这里再抄一份** —— 各 kit 校验器(validateTimer / validateSort / …)里那份
    // 才是真源。越界与否由 requireRelaxedStillValid 把放宽后的配置整份重跑一遍来判,
    // 抄第二份表就意味着改那边不改这边时谁也不会红。
    public static Map<String, String> relaxFields() { return RELAX_FIELDS; }

    /**
     * 把一条已过闸的 relax 值落到段上:{@code =N} 直接设、{@code +N} 在原值上加。发布期复核
     * (requireRelaxedStillValid)与运行期开局(applyOneRelax)共用这一处 —— 两边各写一份时,
     * 小数误差(猜数字 2.5)在两边都被 asInt 截成了 2,算出来的与作者看到的不是同一个数。
     */
    public static void applyRelaxValue(ObjectNode section, String field, String raw) {
        int amount = Integer.parseInt(raw.substring(1));
        if (raw.startsWith("-")) amount = -amount;              // LOWER:往小里减
        if (raw.startsWith("=")) {
            section.put(field, amount);
            return;
        }
        JsonNode current = section.path(field);
        if (current.isFloatingPointNumber()) section.put(field, current.asDouble() + amount);
        else section.put(field, current.asLong(0L) + amount);
    }
    private static final Pattern RELAX_DELTA = Pattern.compile("^([+=-])(\\d{1,5})$");
    /**
     * 创作者效果里唯一放行的服务端保留变量(共享契约 v4):sys.luck,只能 INC 1 至 3,
     * 执行时封顶 journeyRules.luck.max、luck.enabled=false 时忽略。
     */
    private static final String SYS_LUCK_VAR = "sys.luck";
    /**
     * R14 只读系统变量(共享契约 v4):作者条件里可以引用,效果里仍只放行 sys.luck。
     * sys.asked.{nodeId}.{questionId} / sys.mistakes.{nodeId} / sys.outcome.{nodeId} /
     * sys.passed.{nodeId} 的
     * nodeId 是不是本主题的节点要连库,由发布期闸核对(见 CmsTopicServiceImpl);本校验器
     * 只保证形状合法。
     */
    private static final Pattern SYS_ASKED_VAR =
            Pattern.compile("^sys\\.asked\\.[1-9][0-9]*\\.[A-Za-z0-9_-]{1,32}$");
    private static final Pattern SYS_NODE_VAR =
            Pattern.compile("^sys\\.(mistakes|outcome|passed)\\.[1-9][0-9]*$");
    /** requires / effects / mods / advantageIf / disadvantageIf 各段条数上限 */
    private static final int MAX_STATE_REFS = 16;
    private static final int MIN_STATE_VALUE = -99;
    private static final int MAX_STATE_VALUE = 99;
    private static final int MAX_CHECK_TEXT = 200;
    /** 排序题:2 个起才有"顺序"可言,8 个封顶 —— 再多一屏排不下 */
    private static final int MIN_SORT_ITEMS = 2;
    private static final int MAX_SORT_ITEMS = 8;
    /** 连线题:两边各 2 至 6 条,再多小屏上连线会缠成一团 */
    private static final int MIN_MATCH_SIDE = 2;
    private static final int MAX_MATCH_SIDE = 6;
    /** 分类题:2 至 4 个分类箱、2 至 10 张卡片 */
    private static final int MIN_CLASSIFY_BINS = 2;
    private static final int MAX_CLASSIFY_BINS = 4;
    private static final int MIN_CLASSIFY_ITEMS = 2;
    private static final int MAX_CLASSIFY_ITEMS = 10;
    /**
     * 排序 / 连线 / 分类三个新玩法共用的 id 形状:1 至 32 位字母、数字、下划线或短横线。
     * 比老玩法的 64 位短 —— 它们要摆在卡片和连线点上,32 位足够且不会撑破布局。
     */
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_GAME_LABEL = 40;
    private static final int MAX_GAME_PROMPT = 200;
    /** 时间轴对照题:每侧 2 至 20 条 —— 2 条起才有「两版对照」可言,20 条封顶,再多一屏放不下 */
    private static final int MIN_COMPARE_SIDE = 2;
    private static final int MAX_COMPARE_SIDE = 20;
    private static final int MAX_COMPARE_TIME = 16;
    private static final int MAX_COMPARE_TEXT = 200;
    private static final int MAX_COMPARE_LABEL = 20;
    /** 对照题可以交几次(可选):1 至 10;缺省 = 不限,与排序题的 0 同一条口径 */
    private static final int MIN_COMPARE_ATTEMPTS = 1;
    private static final int MAX_COMPARE_ATTEMPTS = 10;
    /** 双人视角(roleViews):恰好 A、B 两个角色,每个角色恰好一个 view,items 有上限。 */
    private static final Set<String> ROLE_VIEW_IDS = setOf("A", "B");
    private static final int MAX_ROLE_VIEW_ITEMS = 8;
    private static final int MAX_ROLE_VIEW_TEXT = 200;
    /** 预制人生建档:问题 key 以字母开头,1 至 16 位字母、数字或下划线(契约 §2.1) */
    private static final Pattern PROFILE_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,15}$");
    private static final Set<String> PROFILE_KINDS = setOf("text", "pick");
    private static final int MIN_PROFILE_QUESTIONS = 1;
    private static final int MAX_PROFILE_QUESTIONS = 8;
    private static final int MIN_PROFILE_OPTIONS = 2;
    private static final int MAX_PROFILE_OPTIONS = 6;
    /** 拍照审核:作者一句人话写清「要拍到什么」,服务端交给视觉模型判(契约 §2.2) */
    private static final int MAX_PHOTO_REQUIREMENT = 60;
    private static final Set<String> PHOTO_CHECK_FALLBACKS = setOf("retake", "pass");
    /** 拍照审核·取景轮廓(2026-09-22 现场感三件套 S1):叠加在取景器上的半透明轮廓,只辅助取景 */
    private static final int MAX_PHOTO_FRAME_URL = 512;
    private static final int MAX_PHOTO_FRAME_OPACITY = 100;
    /** 拍物成卡(立体藏品卡契约 §2.1):与拍照审核共用 photoCheck 段,靠 mode 分玩法。
     *  mode 为空 = 老的拍照审核,行为逐字不变;'CARD' = 判过之后把那张照片铸成藏品卡。
     *  ⚠️ 这两份名单与小程序 advanced-game-config.js 的 PHOTO_CHECK_MODES /
     *  PHOTO_CARD_STYLES 互为镜像,改动要同 PR(钉在 object-card-config-contract)。 */
    private static final Set<String> PHOTO_CHECK_MODES = setOf("CARD");
    private static final Set<String> PHOTO_CARD_STYLES = setOf("foil", "plain");
    /** 卡名落 player_object_card.title(varchar 64),超长要在发布时就拦住 */
    private static final int MAX_CARD_TITLE = 64;
    /** 留言:预设短句与「给后来的人看前几条」的条数上限(契约 §2.4) */
    private static final int MAX_NOTE_PRESETS = 6;
    private static final int MAX_NOTE_SHOW_PREVIOUS = 5;

    /**
     * 呈现方式(施工契约 §1.5,2026-09-18 拍板):根层 present,取值 inline / fullscreen,
     * 作者在模板配置里选,不由 kit 类型定死。不配 = 按下面三档默认表推导。
     * <p>⚠️ 默认表的推导只在 {@link #resolvePresent} 一处实现,验证器与运行期投影都调它,
     * 前后端不许各存一份。
     */
    public static final String PRESENT_INLINE = "inline";
    public static final String PRESENT_FULLSCREEN = "fullscreen";
    private static final Set<String> PRESENT_VALUES = setOf(PRESENT_INLINE, PRESENT_FULLSCREEN);
    /**
     * §1.5 呈现段(2026-10-30 业主裁决「所有玩法都内嵌」):原来的三档(默认内嵌 / 默认全屏 / 只能全屏)合成一档 ——
     * 任何玩法都可以、也默认嵌进故事流;作者仍可在模板里显式配 present=fullscreen。
     * 原「只能全屏」那批(弹球、倒计时、扫码、找东西、罗盘……)的判定区改为内嵌块自身的边界,
     * 由小程序端各 kit 的内嵌档负责(见 pages/play/components/playkit-* 的 inline)。
     */
    private static final Set<String> PRESENT_SECTIONS = setOf(
            "qa", "branch", "predict", "pricePair", "random", "profile", "note", "check", "quietHold",
            "estimate", "diyName", "typeIn", "dailySign", "slowTask", "musicCorner", "timeWindow", "diceRoll",
            "reaction", "ballShake", "countdown", "stopwatch", "coinFlip", "hiddenObject", "scan", "steps",
            "compass", "shout", "blindTaste", "silentOrder", "sort", "match", "classify", "compare", "photoCheck", "album");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 空配置代表未启用；非空配置经校验后返回紧凑、稳定的 JSON。 */
    /** 「线索」工具的名称表：clue.h_ 标识 → 线索名称（同名同键，保存时自动登记为有/无标记）。 */
    private void validateClues(JsonNode clues) {
        if (clues == null) return;
        if (!clues.isObject() || clues.size() > 60) throw new IllegalArgumentException("线索名称表格式不正确");
        clues.fields().forEachRemaining(row -> {
            if (!row.getKey().matches("clue\\.h_[0-9a-f]{12}")) throw new IllegalArgumentException("线索标识不合法");
            if (!row.getValue().isTextual() || row.getValue().asText().trim().isEmpty() || row.getValue().asText().length() > 20) throw new IllegalArgumentException("线索名称须为 1 至 20 字");
        });
    }

    public String validateAndNormalize(String rawJson) {
        if (StringUtils.isBlank(rawJson)) {
            return null;
        }
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("高级玩法配置不能超过 64 KiB");
        }
        final JsonNode parsed;
        try {
            parsed = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
        if (!(parsed instanceof ObjectNode)) {
            throw new IllegalArgumentException("高级玩法配置必须是对象");
        }
        ObjectNode root = (ObjectNode) parsed;
        GameplayOutcomeRules.validateBlock(root);
        if (root.path("interactionRules").has("resourceSlots")) {
            ObjectNode validation = TemplateResourceSlots.validationBlock(root, "模板");
            String checked = new AdvancedGameConfigValidator().validateAndNormalize(validation.toString());
            return TemplateResourceSlots.restoreBlock(root, (ObjectNode) readNormalized(checked)).toString();
        }
        if (root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("高级玩法配置版本不支持");
        }
        // 呈现方式在根层(施工契约 §1.5):一个模板只挂一个 kit,根层不歧义。
        validatePresent(root);
        // 阶段 2:判错扣血吃主题难度表的哪一档,同样住根层。
        validateMistakeTier(root);
        if (root.has("interactionRules")) root.set("interactionRules", GameplayTemplateRules.normalize(root.get("interactionRules"), root.get("interactionTools")));
        if (root.has("interactionTools")) root.set("interactionTools", GameplayTemplateRules.normalizeTools(root.get("interactionTools"), root.path("interactionRules").get("resourceSlots")));
        GameplayTemplateRules.assertCompatible(root);
        validateClues(root.get("clues"));
        // 阶段 4:自适应难度,住根层,与 present / check 同级。
        validateVariants(root);
        validateTimer(root.get("timer"), root.path("outcomeRules").path("version").asInt() == 1);
        validateRandom(root.get("random"));
        validateBranch(root.get("branch"));
        validateLeaderboard(root.get("leaderboard"));
        validateMultiplayer(root.get("multiplayer"));
        // Figma 组件库 v5.1 新玩法(node 45:248)。与上面五个机制平级:一个节点可以同时
        // 挂时间窗 + 盲品,所以不做成"一个 playkit 段带 type"的互斥形状。
        validateTimeWindow(root.get("timeWindow"));
        validateBlindTaste(root.get("blindTaste"));
        validateSilentOrder(root.get("silentOrder"));
        validateDiyName(root.get("diyName"));
        validateMusicCorner(root.get("musicCorner"));
        validateBackgroundMusic(root.get("backgroundMusic"));
        validateSteps(root.get("steps"));
        validateDailySign(root.get("dailySign"));
        validateSlowTask(root.get("slowTask"));
        validateEstimate(root.get("estimate"));
        retireLegacyPricePair(root);
        validatePricePair(root.get("pricePair"));
        validateHiddenObject(root.get("hiddenObject"));
        validatePredict(root.get("predict"));
        validateCoinFlip(root.get("coinFlip"));
        validateDiceRoll(root.get("diceRoll"));
        validateReaction(root.get("reaction"));
        validateBallShake(root.get("ballShake"));
        validateQuietHold(root.get("quietHold"));
        validateCompass(root.get("compass"));
        validateShout(root.get("shout"));
        validateCountdown(root.get("countdown"));
        validateStopwatch(root.get("stopwatch"));
        validateQa(root.get("qa"));
        validateScan(root.get("scan"));
        // R3 三个新玩法。它们与老玩法同一套形状:答案留在服务端,投影只下发题面。
        validateSort(root.get("sort"));
        validateMatch(root.get("match"));
        validateClassify(root.get("classify"));
        // R16 时间轴对照:同一套形状的第四段,答案(标出哪几条)留在服务端。
        validateCompare(root.get("compare"));
        // 预制人生 5 个新玩法(施工契约 20260917)。check 段不在这里新增校验 ——
        // 它是 R14 旅程检定的现成段(checkId/tier/mods),下面 validateCheck 照旧。
        validateProfile(root.get("profile"));
        validatePhotoCheck(root.get("photoCheck"));
        validateAlbum(root.get("album"),root.path("outcomeRules").path("version").asInt()==1);
        validateNote(root.get("note"));
        validateTypeIn(root.get("typeIn"));
        // 旅程体验模组化:三个阶段共用的三个**可选**顶层字段。缺省 = 老配置,照旧通过,
        // 所以 schemaVersion 保持 1 不升版。这里只查配置形状,**不查键注册表** ——
        // 键有没有被主题注册由发布期对账,运行期只保证每处引用都是合法键名 + 合法 op。
        validateConditionList(root.get("requires"), "requires");
        validateEffects(root.get("effects"), "effects");
        validateCheck(root.get("check"));
        // R18:七段节点叙事(brief / route / enter / outcome / deliver / unlock / revisit)已迁往
        // 章节故事流,不再属于模板契约 —— 这里不再校验;模板里残留的老叙事键原样透传(只读兼容),
        // 运行期也不再从模板读。故事流侧的校验见 ChapterNarrativeBlocks。
        // 双人视角(R16-DUO):按角色分半的只读内容。**不投影**(AdvancedGamePublicProjection
        // 是白名单,copy 里没有这一段),由 Encounter 在运行期按本人角色下发。
        validateRoleViews(root.get("roleViews"));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置无法序列化");
        }
    }

    /**
     * 根层 present 的形状闸(施工契约 §1.5)。不配 = 合法(按默认表推导);配了不认识的值 = 拒;
     * 「只能全屏」那批段配 inline = 拒并说明理由。
     *
     * <p>⚠️ 不静默改回 fullscreen:作者以为配成了内嵌、玩家看到的是全屏,两边都不知道哪错了。
     */
    private void validatePresent(JsonNode root) {
        JsonNode configured = root.get("present");
        if (configured == null || configured.isNull()) return;
        if (!configured.isTextual() || !PRESENT_VALUES.contains(configured.asText())) {
            throw new IllegalArgumentException("present 只能是 inline 或 fullscreen");
        }
    }

    /**
     * 根层 mistakeTier 的形状闸(阶段 2:判错伤害并入主题难度表)。不配 = 合法,运行期按 easy 走;
     * 配了必须正好是 easy / medium / hard 三个字面量之一。
     *
     * <p>⚠️ 坏值直接拒、不在运行期悄悄落回 easy:作者填了 nightmare 却按 easy 扣分,
     * 前后两边都不会发现档位没生效 —— 与 present 那条同一个道理。
     */
    private void validateMistakeTier(JsonNode root) {
        JsonNode configured = root.get("mistakeTier");
        if (configured == null || configured.isNull()) return;
        if (!configured.isTextual() || !CHECK_TIERS.contains(configured.asText())) {
            throw new IllegalArgumentException("mistakeTier 只能是 easy、medium 或 hard");
        }
    }

    /**
     * 最终生效的呈现方式(施工契约 §1.5):作者显式配的优先;否则只要启用了任一玩法段就内嵌;
     * 一个玩法段都没有(存量只配 timer 这类无呈现段 / 没配玩法)回退 fullscreen,与改动前一致。
     * 这是全链路唯一一份实现 —— 运行期会话视图与公开投影都调这里,客户端不重推。
     */
    public static String resolvePresent(JsonNode configRoot) {
        if (configRoot != null && configRoot.isObject()) {
            JsonNode configured = configRoot.get("present");
            if (configured != null && configured.isTextual()
                    && PRESENT_VALUES.contains(configured.asText())) {
                return configured.asText();
            }
            for (String section : PRESENT_SECTIONS) {
                if (enabled(configRoot.get(section))) return PRESENT_INLINE;
            }
        }
        return PRESENT_FULLSCREEN;
    }

    /**
     * R22-SEC S16:NODE_COMPLETED 条件引用的 nodeId 必须属于本主题(发布期闸用)。
     *
     * <p>这里只查归属,重验形状由 {@link #validateAndNormalize} 负责;JSON 解不开照旧抛。
     * nodeId 写错的条件永远不成立且零报错,只有发布期能看见 —— 调用方在拿到本主题节点集合时
     * 必须调用本方法(见 CmsTopicServiceImpl 的块配置发布闸)。
     */
    public void assertCompletedNodeIdsOwned(String rawJson, Set<Long> topicNodeIds) {
        if (StringUtils.isBlank(rawJson) || topicNodeIds == null) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
        List<Long> foreign = new ArrayList<Long>();
        collectForeignCompletedNodeIds(root, topicNodeIds, foreign);
        if (!foreign.isEmpty()) {
            throw new IllegalArgumentException("NODE_COMPLETED 引用的节点不属于本主题:nodeId=" + foreign);
        }
    }

    private static void collectForeignCompletedNodeIds(JsonNode node, Set<Long> topicNodeIds, List<Long> foreign) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            if ("NODE_COMPLETED".equals(node.path("op").asText(""))) {
                JsonNode nodeId = node.get("nodeId");
                if (nodeId != null && nodeId.canConvertToLong()) {
                    Long referenced = Long.valueOf(nodeId.asLong());
                    if (!topicNodeIds.contains(referenced) && !foreign.contains(referenced)) {
                        foreign.add(referenced);
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collectForeignCompletedNodeIds(fields.next().getValue(), topicNodeIds, foreign);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectForeignCompletedNodeIds(item, topicNodeIds, foreign);
            }
        }
    }

    public boolean hasEnabledMechanic(String rawJson) {
        String normalized = validateAndNormalize(rawJson);
        if (normalized == null) return false;
        try {
            JsonNode root = objectMapper.readTree(normalized);
            return root.has("outcomeRules") && enabled(root.get("check")) || enabled(root.get("timer")) || enabled(root.get("random"))
                    || enabled(root.get("branch")) || enabled(root.get("leaderboard"))
                    || enabled(root.get("multiplayer"))
                    // 漏了这一行 = 配了新玩法但 hasAdvanced 仍为 false,玩家走到点直接打卡,
                    // 玩法静默不存在(migration_20260825 那批 vm=0 模板正是这个病)
                    || enabled(root.get("timeWindow")) || enabled(root.get("blindTaste"))
                    || enabled(root.get("silentOrder")) || enabled(root.get("diyName"))
                    || enabled(root.get("musicCorner")) || enabled(root.get("steps"))
                    || enabled(root.get("dailySign")) || enabled(root.get("slowTask"))
                    // 三个新完成方式同理:漏了它们,配了估数的节点 hasAdvanced 仍是 false,
                    // 玩家到点直接打卡,估数题静默不存在。
                    || enabled(root.get("estimate")) || enabled(root.get("pricePair"))
                    || enabled(root.get("hiddenObject")) || enabled(root.get("predict"))
                    /* ★ 决定类、挑战类与问答/扫码也必须在册。漏了它们,只配了这些玩法的节点
                       hasAdvanced 仍是 false —— 开局时直接被判成「该模板未配置高级玩法」,
                       玩家到点就打卡,那个玩法静默地不存在。 */
                    || enabled(root.get("coinFlip")) || enabled(root.get("diceRoll"))
                    || enabled(root.get("reaction")) || enabled(root.get("ballShake"))
                    || enabled(root.get("quietHold")) || enabled(root.get("countdown"))
                    || enabled(root.get("stopwatch"))
                    // 罗盘指向同一条前科口径:漏这一行 = 只配罗盘的节点 hasAdvanced 仍是 false,
                    // 玩家到点直接打卡,玩法静默不存在(现场感契约 §5.1)。
                    || enabled(root.get("compass"))
                    // 喊一嗓子同一条前科口径:漏这一行 = 只配它的节点 hasAdvanced 仍是 false,
                    // 玩家到点直接打卡,玩法静默不存在(现场感契约 §5.1)。
                    || enabled(root.get("shout"))
                    || enabled(root.get("qa")) || enabled(root.get("scan"))
                    /* R3 三个新玩法同样必须在册。漏了它们,只配了排序/连线/分类的节点
                       hasAdvanced 仍是 false,玩家到点直接打卡,玩法静默不存在。 */
                    || enabled(root.get("sort")) || enabled(root.get("match"))
                    || enabled(root.get("classify"))
                    /* R16 时间轴对照同样必须在册。漏了它,只配对照题的节点 hasAdvanced 仍是 false,
                       玩家到点直接打卡,对照玩法静默不存在。 */
                    || enabled(root.get("compare"))
                    /* 预制人生四个新玩法同样必须在册。漏一行 = 只配了建档/拍照/留言/打字的节点
                       hasAdvanced 仍是 false,玩家到点直接打卡,玩法静默不存在
                       (现码注释里已有两次前科,别成为第三次)。 */
                    || enabled(root.get("profile")) || enabled(root.get("photoCheck"))
                    || enabled(root.get("note")) || enabled(root.get("typeIn"))
                    || root.path("outcomeRules").path("version").asInt()==1 && enabled(root.get("album"));
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
    }

    private void validateTimer(JsonNode timer, boolean r5) {
        if (!enabled(timer)) return;
        requireObject(timer, "计时配置格式不正确");
        int seconds = timer.path("durationSeconds").asInt(0);
        if (seconds < 10 || seconds > 24 * 60 * 60) {
            throw new IllegalArgumentException("计时时长须为 10 秒至 24 小时");
        }
        if (!TIMEOUT_RESULTS.contains(timer.path("timeoutResult").asText(""))
                && !(r5 && "COMPLETED".equals(timer.path("timeoutResult").asText()))) {
            throw new IllegalArgumentException("计时结束结果不支持");
        }
    }

    private void validateRandom(JsonNode random) {
        if (!enabled(random)) return;
        requireObject(random, "盲盒配置格式不正确");
        JsonNode items = random.get("items");
        if (!(items instanceof ArrayNode) || items.size() < 1 || items.size() > 50) {
            throw new IllegalArgumentException("盲盒须配置 1 至 50 个物品");
        }
        // 卡池名(原型「这副卡叫什么」):可以不填,填了就限长;它会下发给玩家那一屏
        optionalText(random, "deckName", 20, "卡池名称最多 20 个字");
        int drawCount = random.path("drawCount").asInt(0);
        if (drawCount < 1) throw new IllegalArgumentException("抽取数量至少为 1");
        if (drawCount > items.size()) throw new IllegalArgumentException("抽取数量不能超过物品数量");
        Set<String> ids = new HashSet<>();
        for (JsonNode item : items) {
            requireObject(item, "盲盒物品格式不正确");
            String id = requiredId(item, "id", "盲盒物品 ID 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("盲盒物品 ID 不能重复");
            requiredText(item, "label", 40, "盲盒物品名称不能为空", "盲盒物品名称最多 40 个字");
            int weight = item.path("weight").asInt(0);
            if (weight < 1 || weight > 100000) {
                throw new IllegalArgumentException("盲盒权重须为 1 至 100000");
            }
            optionalText(item, "content", 500, "盲盒内容最多 500 个字");
            optionalMediaUrl(item, "audioUrl", 500, "盲盒音频地址最多 500 个字", "盲盒音频地址格式不正确");
        }
    }

    /** 旧配置与冻结会话共用补码规则；只补缺失/null，不覆盖作者显式编号。 */
    public static String resolveBranchOutcomeCode(JsonNode branch, JsonNode step) {
        if (step.hasNonNull("outcomeCode")) return step.path("outcomeCode").asText("").trim();
        // ponytail: 配置上限50步，仅缺码时扫描；放开上限再预计算终点数。
        int terminals = 0;
        for (JsonNode candidate : branch.path("steps")) {
            if (candidate.path("terminal").asBoolean(false)) terminals++;
        }
        return terminals == 1 ? "COMPLETED" : step.path("id").asText("").trim();
    }

    private void validateBranch(JsonNode branch) {
        if (!enabled(branch)) return;
        requireObject(branch, "分支剧情配置格式不正确");
        JsonNode steps = branch.get("steps");
        if (!(steps instanceof ArrayNode) || steps.size() < 1 || steps.size() > 50) {
            throw new IllegalArgumentException("分支剧情须配置 1 至 50 个步骤");
        }
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode step : steps) {
            requireObject(step, "分支步骤格式不正确");
            String id = requiredId(step, "id", "分支步骤 ID 不能为空");
            if (byId.put(id, step) != null) throw new IllegalArgumentException("分支步骤 ID 不能重复");
            optionalText(step, "title", 80, "分支步骤标题最多 80 个字");
            optionalText(step, "body", 1000, "分支步骤正文最多 1000 个字");
        }
        String start = requiredId(branch, "startStepId", "分支起点不能为空");
        if (!byId.containsKey(start)) throw new IllegalArgumentException("分支起点不存在");

        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> outcomeCodes = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : byId.entrySet()) {
            JsonNode step = entry.getValue();
            boolean terminal = step.path("terminal").asBoolean(false);
            if (terminal) {
                // 早期故事模板未配置结果码：单结局沿用 COMPLETED，多结局按稳定步骤 ID 补齐。
                if (!step.hasNonNull("outcomeCode")) {
                    ((ObjectNode) step).put("outcomeCode", resolveBranchOutcomeCode(branch, step));
                }
                String outcomeCode = requiredId(step, "outcomeCode", "终点步骤必须配置 outcomeCode");
                if (!outcomeCode.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("终点 outcomeCode 仅支持字母、数字、下划线和短横线");
                }
                if (!outcomeCodes.add(outcomeCode)) throw new IllegalArgumentException("分支终点 outcomeCode 不能重复");
                optionalText(step, "outcomeLabel", 80, "终点结果名称最多 80 个字");
            }
            JsonNode options = step.get("options");
            if (!(options instanceof ArrayNode)) throw new IllegalArgumentException("分支选项必须是数组");
            if (!terminal && options.size() < 1) throw new IllegalArgumentException("非终点步骤至少配置一个选项");
            if (terminal && options.size() > 0) throw new IllegalArgumentException("终点步骤不能继续配置选项");
            if (options.size() > 12) throw new IllegalArgumentException("每个步骤最多配置 12 个选项");
            Set<String> optionIds = new HashSet<>();
            Set<String> targets = new LinkedHashSet<>();
            for (JsonNode option : options) {
                requireObject(option, "分支选项格式不正确");
                String optionId = requiredId(option, "id", "分支选项 ID 不能为空");
                if (!optionIds.add(optionId)) throw new IllegalArgumentException("同一步骤的选项 ID 不能重复");
                requiredText(option, "label", 80, "分支选项名称不能为空", "分支选项名称最多 80 个字");
                String target = requiredId(option, "nextStepId", "分支下一步骤不能为空");
                if (!byId.containsKey(target)) throw new IllegalArgumentException("分支选项指向了不存在的步骤");
                if (option.has("score") && (!option.get("score").canConvertToInt()
                        || Math.abs(option.get("score").asInt()) > 100000)) {
                    throw new IllegalArgumentException("分支得分须在 -100000 至 100000 之间");
                }
                // R14:选项结果当场写本局状态(路线图效果写法);不配 = 老配置照旧。
                validateEffects(option.get("effects"),
                        "分支步骤 " + entry.getKey() + " 选项 " + optionId + " 的 effects");
                targets.add(target);
            }
            edges.put(entry.getKey(), targets);
        }
        if (!hasReachableTerminal(start, byId, edges)) {
            throw new IllegalArgumentException("分支剧情必须存在可到达的终点");
        }
    }

    private boolean hasReachableTerminal(String start, Map<String, JsonNode> byId,
            Map<String, Set<String>> edges) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) continue;
            if (byId.get(current).path("terminal").asBoolean(false)) return true;
            for (String next : edges.get(current)) queue.add(next);
        }
        return false;
    }

    private void validateLeaderboard(JsonNode leaderboard) {
        if (!enabled(leaderboard)) return;
        requireObject(leaderboard, "排行榜配置格式不正确");
        if (!LEADERBOARD_METRICS.contains(leaderboard.path("metric").asText(""))) {
            throw new IllegalArgumentException("排行榜指标不支持");
        }
        if (!LEADERBOARD_SCOPES.contains(leaderboard.path("scope").asText(""))) {
            throw new IllegalArgumentException("排行榜范围不支持");
        }
        int limit = leaderboard.path("limit").asInt(0);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("排行榜人数须为 1 至 100");
    }

    private void validateMultiplayer(JsonNode multiplayer) {
        if (!enabled(multiplayer)) return;
        requireObject(multiplayer, "多人玩法配置格式不正确");
        String mode = multiplayer.path("mode").asText("");
        if (!MULTIPLAYER_MODES.contains(mode)) throw new IllegalArgumentException("多人玩法模式不支持");
        int minPlayers = multiplayer.path("minPlayers").asInt(0);
        int maxPlayers = multiplayer.path("maxPlayers").asInt(0);
        if (minPlayers < 2 || maxPlayers > 20 || minPlayers > maxPlayers) {
            throw new IllegalArgumentException("多人玩法人数须为 2 至 20 人");
        }
        String assignment = multiplayer.path("assignment").asText("AUTO");
        if (!ASSIGNMENTS.contains(assignment)) throw new IllegalArgumentException("角色分配方式不支持");
        JsonNode roles = multiplayer.get("roles");
        if (!(roles instanceof ArrayNode) || roles.size() < 1 || roles.size() > 20) {
            throw new IllegalArgumentException("多人玩法须配置 1 至 20 个角色");
        }
        Set<String> roleIds = new HashSet<>();
        Map<String, Integer> roleMin = new HashMap<>();
        Map<String, Integer> roleMax = new HashMap<>();
        int totalMin = 0;
        int totalMax = 0;
        for (JsonNode role : roles) {
            requireObject(role, "多人角色格式不正确");
            String id = requiredId(role, "id", "多人角色 ID 不能为空");
            if (!roleIds.add(id)) throw new IllegalArgumentException("多人角色 ID 不能重复");
            requiredText(role, "label", 40, "多人角色名称不能为空", "多人角色名称最多 40 个字");
            int min = role.path("min").asInt(0);
            int max = role.path("max").asInt(0);
            if (min < 0 || max < 1 || min > max || max > maxPlayers) {
                throw new IllegalArgumentException("多人角色人数范围不正确");
            }
            roleMin.put(id, min);
            roleMax.put(id, max);
            totalMin += min;
            totalMax += max;
        }
        if (totalMin > minPlayers || totalMax < maxPlayers) {
            throw new IllegalArgumentException("角色人数范围无法覆盖玩法人数");
        }
        JsonNode turnOrder = multiplayer.get("turnOrder");
        if (!(turnOrder instanceof ArrayNode) || turnOrder.size() < 1 || turnOrder.size() > 100) {
            throw new IllegalArgumentException("多人玩法须配置轮次顺序");
        }
        for (JsonNode roleId : turnOrder) {
            if (!roleId.isTextual() || !roleIds.contains(roleId.asText())) {
                throw new IllegalArgumentException("轮次顺序引用了不存在的角色");
            }
        }
        if ("AUTO".equals(assignment)) {
            for (int players = minPlayers; players <= maxPlayers; players++) {
                Map<String, Integer> assigned = new HashMap<>();
                for (int i = 0; i < players; i++) {
                    String roleId = turnOrder.get(i % turnOrder.size()).asText();
                    assigned.put(roleId, assigned.getOrDefault(roleId, 0) + 1);
                }
                for (String roleId : roleIds) {
                    int count = assigned.getOrDefault(roleId, 0);
                    if (count < roleMin.get(roleId) || count > roleMax.get(roleId)) {
                        throw new IllegalArgumentException("自动分配顺序无法覆盖全部允许人数");
                    }
                }
            }
        }
        int requiredTurns = multiplayer.path("requiredTurns").asInt(1);
        if (requiredTurns < 1 || requiredTurns > 1000) {
            throw new IllegalArgumentException("多人玩法完成轮数须为 1 至 1000");
        }
        int unitScore = multiplayer.path("unitScore").asInt(0);
        if (unitScore < 0 || unitScore > 100000) {
            throw new IllegalArgumentException("多人玩法单轮得分须为 0 至 100000");
        }
    }

    // ==================== Figma v5.1 新玩法(node 45:248)====================

    /** 时段限定:只在开放时段内允许开局。判定用服务端时钟,客户端倒计时到 0 只是回来问一次。 */
    private void validateTimeWindow(JsonNode timeWindow) {
        if (!enabled(timeWindow)) return;
        requireObject(timeWindow, "时段限定配置格式不正确");
        String from = timeWindow.path("openFrom").asText("").trim();
        String to = timeWindow.path("openTo").asText("").trim();
        if (!CLOCK.matcher(from).matches() || !CLOCK.matcher(to).matches()) {
            throw new IllegalArgumentException("开放时段须为 HH:mm 的 24 小时制");
        }
        if (from.equals(to)) {
            // 相等既可以读成"全天开放"也可以读成"永不开放",歧义比不合法更危险
            throw new IllegalArgumentException("开放时段的起止不能相同");
        }
        optionalText(timeWindow, "eyebrow", 32, "时段限定眉标不能超过 32 字");
        optionalText(timeWindow, "title", 32, "时段限定标题不能超过 32 字");
        optionalText(timeWindow, "subscribeTmplId", 64, "订阅消息模板 id 不能超过 64 字");
    }

    /**
     * 盲品:选项 + 正确答案。
     * ★ answerKey 存在配置里,但 AdvancedGamePublicProjection 绝不投影它 ——
     *   下发了就等于把答案发给客户端,这道题当场作废。
     */
    private void validateBlindTaste(JsonNode blindTaste) {
        if (!enabled(blindTaste)) return;
        requireObject(blindTaste, "盲品配置格式不正确");
        requiredText(blindTaste, "title", 64, "盲品标题不能为空", "盲品标题不能超过 64 字");
        optionalText(blindTaste, "steps", 120, "盲品步骤说明不能超过 120 字");
        optionalText(blindTaste, "hint", 60, "盲品旁白不能超过 60 字");
        // 配图把这一段从"闭眼盲品"扩成通用看图题:局部放大猜商品 / 这是店里哪个角落 / 老照片猜年份。
        // 判定、答案保护、xp 只发一次都与盲品同一套,不值得为一张图再开一个 section。
        optionalMediaUrl(blindTaste, "imageUrl", 512,
                "盲品配图地址不能超过 512 字", "盲品配图地址必须是 https 链接或站内路径");

        JsonNode options = blindTaste.get("options");
        if (options == null || !options.isArray()
                || options.size() < MIN_BLIND_OPTIONS || options.size() > MAX_BLIND_OPTIONS) {
            throw new IllegalArgumentException("盲品选项须为 2 至 6 项");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode option : options) {
            requireObject(option, "盲品选项格式不正确");
            String key = requiredId(option, "key", "盲品选项 key 不能为空");
            if (!keys.add(key)) throw new IllegalArgumentException("盲品选项 key 不能重复");
            requiredText(option, "label", 32, "盲品选项文案不能为空", "盲品选项文案不能超过 32 字");
        }
        String answerKey = blindTaste.path("answerKey").asText("").trim();
        if (!keys.contains(answerKey)) {
            // 答案不在选项里 = 这题永远答不对,而且不报错
            throw new IllegalArgumentException("盲品正确答案必须是其中一个选项");
        }
        validateXp(blindTaste, "盲品");
    }

    /** 沉默点单:核销仍走既有的"商家扫玩家码",这里只管展示规则与表演时限。 */
    private void validateSilentOrder(JsonNode silentOrder) {
        if (!enabled(silentOrder)) return;
        requireObject(silentOrder, "沉默点单配置格式不正确");
        requiredText(silentOrder, "title", 64, "沉默点单标题不能为空", "沉默点单标题不能超过 64 字");
        optionalText(silentOrder, "rule", 200, "沉默点单规则说明不能超过 200 字");
        int limit = silentOrder.path("limitSeconds").asInt(0);
        if (limit != 0 && (limit < 60 || limit > 3600)) {
            throw new IllegalArgumentException("沉默点单时限须为 60 秒至 1 小时,或 0 表示不限时");
        }
    }

    /** DIY 作品命名:纯文本提交,商家审核在既有链路里,这里只管长度与备选。 */
    private void validateDiyName(JsonNode diyName) {
        if (!enabled(diyName)) return;
        requireObject(diyName, "作品命名配置格式不正确");
        requiredText(diyName, "title", 64, "作品命名标题不能为空", "作品命名标题不能超过 64 字");
        int maxLength = diyName.path("maxLength").asInt(16);
        if (maxLength < 2 || maxLength > 40) {
            throw new IllegalArgumentException("作品名长度上限须为 2 至 40 字");
        }
        JsonNode suggestions = diyName.get("suggestions");
        if (suggestions != null && !suggestions.isNull()) {
            if (!suggestions.isArray() || suggestions.size() > 6) {
                throw new IllegalArgumentException("作品名备选最多 6 个");
            }
            for (JsonNode item : suggestions) {
                if (!item.isTextual() || item.asText().trim().isEmpty()
                        || item.asText().length() > maxLength) {
                    throw new IllegalArgumentException("作品名备选不能为空且不能超过名称长度上限");
                }
            }
        }
    }

    /**
     * 治愈音乐角:**不判定通关**(2026-08-26 用户拍板,与赛博木鱼同档)。
     * 所以这里没有"听完"的校验字段 —— 前端上报"我听完了"本来就可以伪造,
     * 与其做一个挡不住的判定,不如明确它只是氛围件。
     */
    private void validateMusicCorner(JsonNode musicCorner) {
        if (!enabled(musicCorner)) return;
        requireObject(musicCorner, "音乐角配置格式不正确");
        requiredText(musicCorner, "title", 64, "音乐角标题不能为空", "音乐角标题不能超过 64 字");
        optionalText(musicCorner, "trackName", 40, "曲目名不能超过 40 字");
        optionalMediaUrl(musicCorner, "audioUrl", 512,
                "曲目地址不能超过 512 字", "曲目地址必须是 https 链接或站内路径");
        int duration = musicCorner.path("durationSeconds").asInt(0);
        if (duration != 0 && (duration < 10 || duration > 3600)) {
            throw new IllegalArgumentException("曲目时长须为 10 秒至 1 小时");
        }
    }

    /**
     * 背景音乐(互动工具):玩这一站时播放的一段音乐。只是氛围件,不参与判定。
     * 有这一段就算开着,**不带 enabled**:带了的话支持矩阵会把它当成第二个玩法。
     */
    private void validateBackgroundMusic(JsonNode music) {
        if (music == null || music.isNull()) return;
        requireObject(music, "背景音乐配置格式不正确");
        if (music.has("enabled")) throw new IllegalArgumentException("背景音乐不使用 enabled 字段");
        optionalText(music, "title", 40, "背景音乐名称不能超过 40 字");
        if (!music.path("audioUrl").isTextual() || music.path("audioUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("请上传背景音乐");
        }
        optionalMediaUrl(music, "audioUrl", 512,
                "背景音乐地址不能超过 512 字", "背景音乐地址必须是 https 链接或站内路径");
        if (music.has("loop") && !music.get("loop").isBoolean()) {
            throw new IllegalArgumentException("背景音乐循环设置格式不正确");
        }
    }

    /**
     * 计步挑战:目标步数。真实步数只能由服务端解密微信运动拿到(见 WeRunDecryptor),
     * 配置里不放任何客户端可自证的字段。
     */
    private void validateSteps(JsonNode steps) {
        if (!enabled(steps)) return;
        requireObject(steps, "计步配置格式不正确");
        int goal = steps.path("goal").asInt(0);
        if (goal < 100 || goal > 100000) {
            throw new IllegalArgumentException("计步目标须为 100 至 100000 步");
        }
        optionalText(steps, "eyebrow", 32, "计步眉标不能超过 32 字");
        validateXp(steps, "计步");
    }

    /**
     * 今日城市签:签文池。
     * ★ 池子不投影给客户端 —— 一次领一条、由服务端当天选,下发整池等于把"明天的签"提前给了。
     */
    private void validateDailySign(JsonNode dailySign) {
        if (!enabled(dailySign)) return;
        requireObject(dailySign, "城市签配置格式不正确");
        JsonNode poems = dailySign.get("poems");
        if (poems == null || !poems.isArray() || poems.size() == 0 || poems.size() > 60) {
            throw new IllegalArgumentException("城市签签文须为 1 至 60 条");
        }
        for (JsonNode poem : poems) {
            if (!poem.isArray() || poem.size() == 0 || poem.size() > 4) {
                throw new IllegalArgumentException("每条签文须为 1 至 4 行");
            }
            for (JsonNode line : poem) {
                if (!line.isTextual() || line.asText().trim().isEmpty() || line.asText().length() > 24) {
                    throw new IllegalArgumentException("签文每行不能为空且不能超过 24 字");
                }
            }
        }
        optionalText(dailySign, "signer", 16, "签文落款不能超过 16 字");
        optionalText(dailySign, "sealText", 8, "印文不能超过 8 字");
    }

    /**
     * 跨日慢任务(Figma「🌙 低压游戏循环 UX」← Pokémon Sleep / Focus Friend / Waterllama)。
     * 稿:「城瘾跨日慢任务:低摩擦开始 → 隔天解锁地点/角色发现 → 周尺度回顾规律与变化」。
     *
     * ★ 它和 timer 是相反的两件事,别混:
     *   timer 是「限时,快点」;slowTask 是「慢慢来,明天再回来」。
     *   前者制造压力,后者刻意不制造压力 —— 这一整块的名字就叫「低压游戏循环」。
     *
     * ★ waitDays 只允许 1–7:超过一周的等待不是低压是遗忘,玩家不会回来,
     *   而「回不来的任务」比没有这个任务更伤(它会一直挂在未完成列表里)。
     */
    private void validateSlowTask(JsonNode slowTask) {
        if (!enabled(slowTask)) return;
        requireObject(slowTask, "跨日任务配置格式不正确");
        requiredText(slowTask, "title", 64, "跨日任务标题不能为空", "跨日任务标题不能超过 64 字");
        optionalText(slowTask, "startLabel", 24, "开始按钮文案不能超过 24 字");
        optionalText(slowTask, "waitHint", 60, "等待期提示不能超过 60 字");
        optionalText(slowTask, "unlockLabel", 24, "解锁按钮文案不能超过 24 字");
        requiredText(slowTask, "unlockText", 200,
                "跨日任务解锁内容不能为空", "解锁后揭示的内容不能超过 200 字");
        int waitDays = slowTask.path("waitDays").asInt(1);
        if (waitDays < 1 || waitDays > 7) {
            throw new IllegalArgumentException("跨日任务的等待天数须为 1 至 7 天");
        }
        validateXp(slowTask, "跨日任务");
    }

    /**
     * 估数题:商家给一个正确值与一条容差,玩家在数轴上猜。
     *
     * <p>为什么按容差分档而不是判对错:估数天然带运气,非黑即白会让绝大多数人得 0 分,
     * 玩一次就不玩了。分档让"差一点"也有回报。三档的边界在
     * {@link com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl} 里判,
     * 这里只保证配置本身自洽。
     *
     * <p>⚠️ {@code answer} 绝不进公开投影(见 AdvancedGamePublicProjection#copyEstimate),
     * 下发了就等于把答案印在题面上。
     */
    private void validateEstimate(JsonNode estimate) {
        if (!enabled(estimate)) return;
        requireObject(estimate, "估数配置格式不正确");
        requiredText(estimate, "title", 64, "估数题干不能为空", "估数题干不能超过 64 字");
        optionalText(estimate, "unit", 8, "估数单位不能超过 8 字");
        optionalText(estimate, "reveal", 200, "估数揭示文案不能超过 200 字");

        if (!estimate.path("min").isNumber() || !estimate.path("max").isNumber()
                || !estimate.path("answer").isNumber() || !estimate.path("tolerance").isNumber()) {
            throw new IllegalArgumentException("估数的量程、答案与容差都必须是数字");
        }
        double min = estimate.path("min").asDouble();
        double max = estimate.path("max").asDouble();
        double answer = estimate.path("answer").asDouble();
        double tolerance = estimate.path("tolerance").asDouble();

        if (min >= max) {
            throw new IllegalArgumentException("估数量程的下限必须小于上限");
        }
        if (max - min > MAX_ESTIMATE_SPAN) {
            throw new IllegalArgumentException("估数量程跨度不能超过 " + (long) MAX_ESTIMATE_SPAN);
        }
        if (answer < min || answer > max) {
            // 答案在数轴之外 = 这题永远拿不到满分,而且不报错
            throw new IllegalArgumentException("估数答案必须落在量程之内");
        }
        if (tolerance <= 0) {
            throw new IllegalArgumentException("估数容差必须大于 0");
        }
        if (tolerance > (max - min) / 2) {
            // 容差过半 = 数轴上随便点都算命中,题目失去意义
            throw new IllegalArgumentException("估数容差不能超过量程的一半");
        }
        validateXp(estimate, "估数");
    }

    /**
     * 价格连击:两两比价,答对继续、答错本局结束、连击数计分。
     *
     * <p>⚠️ 所有 {@code price} 都是秘密 —— 公开投影只给 name 与配图
     * (见 AdvancedGamePublicProjection#copyPricePair)。价格一旦下发,这题就只剩点击。
     *
     * <p>项数下限 3 是有意的:2 项只有一种组合,答一次就到头,连击无从谈起。
     */
    /**
     * 猜图:几张图里挑出正确的那一张。
     *
     * <p>★ 2026-09-10 改模型。这一段原来是**比价**(每件带价格、两件一对比谁贵、连对算 streak),
     * 而产品口径里「猜图」从来就是「几张图挑正确的那张」—— 两者不是同一个玩法。
     * 用户 2026-09-10 拍板:猜图不是比价格。
     *
     * <p>老配置(带 price、没有 correct)不报错,在 {@link #retireLegacyPricePair} 里
     * 整段关掉:报错会让**已经发布**的节点在开局校验时直接打不开,而那一段本来就没人玩过。
     */
    private void validatePricePair(JsonNode pricePair) {
        if (!enabled(pricePair)) return;
        requireObject(pricePair, "猜图配置格式不正确");
        requiredText(pricePair, "title", 64, "猜图题目不能为空", "猜图题目不能超过 64 字");

        JsonNode items = pricePair.get("items");
        if (items == null || !items.isArray()
                || items.size() < MIN_PRICE_ITEMS || items.size() > MAX_PRICE_ITEMS) {
            throw new IllegalArgumentException(
                    "猜图的图片须为 " + MIN_PRICE_ITEMS + " 至 " + MAX_PRICE_ITEMS + " 张");
        }
        Set<String> ids = new HashSet<>();
        int correct = 0;
        for (JsonNode item : items) {
            requireObject(item, "猜图图片格式不正确");
            String id = requiredId(item, "id", "猜图图片 id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("猜图图片 id 不能重复");
            requiredText(item, "name", 32, "猜图图片说明不能为空", "猜图图片说明不能超过 32 字");
            optionalMediaUrl(item, "imageUrl", 512,
                    "猜图图片地址不能超过 512 字", "猜图图片地址必须是 https 链接或站内路径");
            if (item.path("correct").asBoolean(false)) correct++;
        }
        // 正好一张:零张无解,多张等于「随便点都对」,两种都让玩家在屏前白站着
        if (correct != 1) throw new IllegalArgumentException("猜图必须指定且只指定一张正确答案");

        int xp = pricePair.path("xp").asInt(0);
        if (xp < 0 || xp > MAX_PRICE_XP_PER_HIT) {
            throw new IllegalArgumentException("猜图答对的奖励分须为 0 至 " + MAX_PRICE_XP_PER_HIT);
        }
        int maxTries = pricePair.path("maxTries").asInt(0);
        if (maxTries < 0 || maxTries > 10) {
            throw new IllegalArgumentException("猜图可以猜几次须为 0 至 10,0 表示不限");
        }
        // R14:挑对时当场写本局状态。
        validateEffects(pricePair.get("effects"), "猜图通过 effects");
    }

    /**
     * 老的比价配置整段关掉。
     *
     * <p>判据是「带着 price、又没有任何一张被标成正确答案」。两条缺一不可:
     * 只看「没有正确答案」的话,新模型里忘了指定答案的配置会被**静默关掉**,
     * 而那应该当场报错让商家去补 —— 老配置与漏填,长得像但要区别对待。
     * 关掉而不是报错:这些配置多半从没被玩过(这批玩法此前在客户端根本打不开),
     * 而报错会让已经发布的节点在开局时打不开,拿一个没人玩过的玩法去换一个能用的节点,不划算。
     * 商家下次编辑时会看到这个玩法是关着的,那是可见的,不是静默的。
     */
    private void retireLegacyPricePair(ObjectNode root) {
        JsonNode pricePair = root.get("pricePair");
        if (!enabled(pricePair)) return;
        boolean anyPrice = false;
        for (JsonNode item : pricePair.path("items")) {
            if (item.path("correct").asBoolean(false)) return;      // 新配置,放行
            if (item.has("price")) anyPrice = true;
        }
        if (!anyPrice) return;          // 新模型漏填答案 —— 交给 validatePricePair 去报错
        ((ObjectNode) pricePair).put("enabled", false);
        ((ObjectNode) pricePair).remove("items");
    }

    private void validateHiddenObject(JsonNode hiddenObject) {
        if (!enabled(hiddenObject)) return;
        requireObject(hiddenObject, "找东西配置格式不正确");
        requiredText(hiddenObject, "title", 64, "找东西标题不能为空", "找东西标题不能超过 64 字");
        optionalText(hiddenObject, "hint", 60, "找东西提示不能超过 60 字");
        // 限定次数(原型 limit() 的第二个开关):0 = 不限,与问答/猜图同量程
        int missTries = hiddenObject.path("maxTries").asInt(0);
        if (missTries < 0 || missTries > 10) {
            throw new IllegalArgumentException("找东西可以错几次须为 0 至 10,0 表示不限");
        }
        if (!hiddenObject.path("imageUrl").isTextual()
                || hiddenObject.path("imageUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("找东西必须有一张图");
        }
        optionalMediaUrl(hiddenObject, "imageUrl", 512,
                "找东西图片地址不能超过 512 字", "找东西图片地址必须是 https 链接或站内路径");

        JsonNode spots = hiddenObject.get("hotspots");
        if (spots == null || !spots.isArray()
                || spots.size() < MIN_HOTSPOTS || spots.size() > MAX_HOTSPOTS) {
            throw new IllegalArgumentException(
                    "找东西要标 " + MIN_HOTSPOTS + " 至 " + MAX_HOTSPOTS + " 个目标");
        }
        Set<String> ids = new HashSet<>();
        List<double[]> placed = new ArrayList<>();
        for (JsonNode spot : spots) {
            requireObject(spot, "找东西目标格式不正确");
            String id = requiredId(spot, "id", "找东西目标 id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("找东西目标 id 不能重复");
            requiredText(spot, "label", 24, "找东西目标名不能为空", "找东西目标名不能超过 24 字");

            if (!spot.path("x").isNumber() || !spot.path("y").isNumber() || !spot.path("r").isNumber()) {
                throw new IllegalArgumentException("找东西目标的坐标与半径必须是数字");
            }
            double x = spot.path("x").asDouble();
            double y = spot.path("y").asDouble();
            double r = spot.path("r").asDouble();
            if (x < 0 || x > 1 || y < 0 || y > 1) {
                throw new IllegalArgumentException("找东西目标的坐标须为 0 到 1 之间的比例值");
            }
            if (r < MIN_HOTSPOT_RADIUS || r > MAX_HOTSPOT_RADIUS) {
                // 太小点不中(手指比它还大),太大等于整张图都是答案
                throw new IllegalArgumentException("找东西目标的半径须为 "
                        + MIN_HOTSPOT_RADIUS + " 到 " + MAX_HOTSPOT_RADIUS + " 之间");
            }
            // 两个目标挨太近,玩家点一下会同时落在两个圈里,谁也说不清点中了哪个
            for (double[] other : placed) {
                double dx = x - other[0];
                double dy = y - other[1];
                if (Math.sqrt(dx * dx + dy * dy) < r + other[2]) {
                    throw new IllegalArgumentException("找东西的两个目标挨得太近,请把它们分开一些");
                }
            }
            placed.add(new double[] { x, y, r });
        }
        // R14:全部找齐时当场写本局状态。
        validateEffects(hiddenObject.get("effects"), "找东西通过 effects");
        validateXp(hiddenObject, "找东西");
    }

    /**
     * 竞猜:商家出一道「今天哪款卖最多」,玩家押一个,次日商家给出答案再发奖。
     *
     * <p>它与另外八个玩法有一处结构差异:**答案在玩家提交时还不存在**。
     * 所以配置里没有 answerKey —— 没什么可藏的,选项可以整个下发。
     * 秘密在结算之后才产生,那时它已经不是秘密了。
     *
     * <p>{@code closeAtHour} 是当天的截止小时(0–23),按 Asia/Shanghai 算。
     * 用相对小时而不是绝对时间戳:模板会被复制、被跨天复用,存死时间等于
     * 复制出来的副本第二天就全部过期。
     */
    /** 竞猜揭晓时间的默认值。与 AdvancedGameRuntimeServiceImpl 的下发默认、
     *  小程序 advanced-game-config 的模型默认同值 —— 三处必须一起改。 */
    private static final int DEFAULT_PREDICT_REVEAL_DAYS = 1;
    private static final int DEFAULT_PREDICT_REVEAL_HOUR = 20;

    private void validatePredict(JsonNode predict) {
        if (!enabled(predict)) return;
        requireObject(predict, "竞猜配置格式不正确");
        requiredText(predict, "question", 120, "竞猜问题不能为空", "竞猜问题不能超过 120 字");
        optionalText(predict, "hint", 60, "竞猜说明不能超过 60 字");

        JsonNode options = predict.get("options");
        if (options == null || !options.isArray()
                || options.size() < MIN_PREDICT_OPTIONS || options.size() > MAX_PREDICT_OPTIONS) {
            throw new IllegalArgumentException(
                    "竞猜选项须为 " + MIN_PREDICT_OPTIONS + " 至 " + MAX_PREDICT_OPTIONS + " 个");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode option : options) {
            requireObject(option, "竞猜选项格式不正确");
            String key = requiredId(option, "key", "竞猜选项 key 不能为空");
            if (!keys.add(key)) throw new IllegalArgumentException("竞猜选项 key 不能重复");
            requiredText(option, "label", 32, "竞猜选项文案不能为空", "竞猜选项文案不能超过 32 字");
        }

        int closeAtHour = predict.path("closeAtHour").asInt(-1);
        if (closeAtHour < 0 || closeAtHour > 23) {
            throw new IllegalArgumentException("竞猜截止时间须为 0 到 23 点之间的整点");
        }
        /* 揭晓时间(原型「什么时候揭晓」)。存相对天数不存绝对时间戳 ——
           模板会被复制、跨天复用,存死时间等于副本第二天全部过期。
           ⚠️ 这两个字段是 2026-09-16 才加的,**存量模板里没有** ——
           所以「缺字段」必须是合法的(按默认值走),只有写了值才校验范围。
           做成必填的话,所有老模板一提交就报一句它们读不懂的错。 */
        int revealDays = predict.path("revealDays").asInt(DEFAULT_PREDICT_REVEAL_DAYS);
        if (revealDays < 0 || revealDays > 30) {
            throw new IllegalArgumentException("竞猜揭晓天数须为 0 至 30 天,0 表示当天");
        }
        int revealHour = predict.path("revealHour").asInt(DEFAULT_PREDICT_REVEAL_HOUR);
        if (revealHour < 0 || revealHour > 23) {
            throw new IllegalArgumentException("竞猜揭晓时间须为 0 到 23 点之间的整点");
        }
        // 当天揭晓的话必须晚于收注截止:先公布答案再收注,那就不是猜了
        if (revealDays == 0 && revealHour < closeAtHour) {
            throw new IllegalArgumentException("当天揭晓的话,揭晓时间要晚于收注截止时间");
        }
        validateXp(predict, "竞猜");
    }

    /**
     * 问答三种模式:打字作答 / 选项作答 / 拍一张交上来。
     *
     * <p>★ 2026-09-10 并入高级玩法。此前它走的是节点的 validationMethod 老链路 ——
     * 跟其余十八个玩法不在同一套配置里,也不在同一条游玩通道上,
     * 结果是这批新屏在生产根本到不了。用户拍板:放到同一个通道。
     *
     * <p>三种模式差的只是中间那一块,所以是一段带 mode,不是三段 —— 三段的话
     * 同一处改动要改三遍,而它们的题面、附件、次数、奖励是同一套。
     */
    private void validateQa(JsonNode qa) {
        if (!enabled(qa)) return;
        requireObject(qa, "问答配置格式不正确");
        String mode = qa.path("mode").asText("");
        if (!QA_MODES.contains(mode)) throw new IllegalArgumentException("问答模式只能是打字、选项或拍照");
        requiredText(qa, "title", 120, "问答题干不能为空", "问答题干不能超过 120 字");
        optionalText(qa, "lead", 60, "问答前置说明不能超过 60 字");
        // 2026-09-24 C-01:已发出去的小程序会把没配的配图 / 音频送成 ""。空串不存库(与取景轮廓同口径),
        // 但这里不拒 —— 整键摘掉当「没填」,否则不配图的问答整份发不出去;不必等微信上传新前端。
        dropBlankText((ObjectNode) qa, "imageUrl", "audioUrl");
        optionalMediaUrl(qa, "imageUrl", 512, "问答配图地址不能超过 512 字", "问答配图地址必须是 https 链接或站内路径");
        optionalMediaUrl(qa, "audioUrl", 512, "问答音频地址不能超过 512 字", "问答音频地址必须是 https 链接或站内路径");

        if ("TYPE".equals(mode)) {
            // 答案可以填多个,用、分隔;比对忽略大小写与首尾空格(与客户端同一条规则)
            requiredText(qa, "answerText", 200, "打字问答必须填正确答案", "问答答案不能超过 200 字");
        } else if ("PICK".equals(mode)) {
            // 判断答案对错:缺省 = 开启(旧配置原样);关闭时每个选项都有效,不要求正确答案
            if (qa.has("judge") && !qa.get("judge").isNull() && !qa.get("judge").isBoolean()) {
                throw new IllegalArgumentException("判断答案对错格式不正确");
            }
            boolean judge = qa.path("judge").asBoolean(true);
            JsonNode options = qa.get("options");
            if (options == null || !options.isArray() || options.size() < 2 || options.size() > 4) {
                throw new IllegalArgumentException("选项问答须为 2 至 4 个选项");
            }
            Set<String> ids = new HashSet<>();
            int correct = 0;
            for (JsonNode option : options) {
                requireObject(option, "问答选项格式不正确");
                String id = requiredId(option, "id", "问答选项 id 不能为空");
                if (!ids.add(id)) throw new IllegalArgumentException("问答选项 id 不能重复");
                requiredText(option, "label", 32, "问答选项文案不能为空", "问答选项文案不能超过 32 字");
                optionalText(option, "fb", 120, "问答选项反馈不能超过 120 字");
                validateFeedbackImages(option.get("fbImgs"));
                // R14:玩家提交这个选项时当场写本局状态(无论对错 —— 代价也可以挂在错项上)。
                validateEffects(option.get("effects"), "问答选项 " + id + " 的 effects");
                if (option.path("correct").asBoolean(false)) correct++;
            }
            // 单选(默认)正好一个:零个无解,多个等于随便点都对。
            // 多选(multi=true)要求至少两个正确答案 —— 一个正确答案不是多选;
            // 也不能全部正确 —— 全选就过等于随便点。老配置不带 multi,行为不变。
            if (!judge) {
                // 不判断对错:正确答案、多选、可答次数等判断设置原样保留,不参与校验
            } else if (qa.path("multi").asBoolean(false)) {
                if (correct < 2) throw new IllegalArgumentException("多选问答至少要指定两个正确答案");
                if (correct >= options.size()) {
                    throw new IllegalArgumentException("多选问答不能把全部选项都设为正确答案");
                }
            } else if (correct != 1) {
                throw new IllegalArgumentException("选项问答必须指定且只指定一个正确答案");
            }
        }
        // 拍照模式没有正确答案 —— 交上来就算做到了,判不了照片内容

        // 拍照打卡的补充说明:选填,与提示词同量程
        optionalText(qa, "shotNote", 56, "拍照补充说明不能超过 56 字");
        int maxTries = qa.path("maxTries").asInt(0);
        if (maxTries < 0 || maxTries > 10) throw new IllegalArgumentException("问答可以答几次须为 0 至 10,0 表示不限");
        validateXp(qa, "问答");
    }

    /**
     * 扫码参与:最轻的一种,扫完立刻回一条,不出题也不判定。
     *
     * <p>★ 二维码内容**不在这儿配** —— 它绑在路线层(节点的 checkin_code)。
     * 在玩法配置里再存一份,两处迟早对不上,而对不上的表现是「扫了说码不对」。
     */
    /** 选项“选中后的反馈”图片:0 至 3 张,每张与其他作者图片同一条地址规则(https 或站内路径)。 */
    private void validateFeedbackImages(JsonNode images) {
        if (images == null || images.isNull()) return;
        if (!images.isArray()) throw new IllegalArgumentException("问答选项反馈图片格式不正确");
        if (images.size() > 3) throw new IllegalArgumentException("每个选项最多 3 张反馈图片");
        for (JsonNode image : images) {
            if (!image.isTextual()) throw new IllegalArgumentException("问答选项反馈图片格式不正确");
            String value = image.asText().trim();
            boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
            if (value.length() > 512) throw new IllegalArgumentException("问答选项反馈图片地址不能超过 512 字");
            if (value.isEmpty() || (!localPath && !value.startsWith("https://") && !value.matches("media-asset:[1-9][0-9]{0,18}"))) {
                throw new IllegalArgumentException("问答选项反馈图片地址必须是 https 链接或站内路径");
            }
        }
    }

    private void validateScan(JsonNode scan) {
        if (!enabled(scan)) return;
        requireObject(scan, "扫码配置格式不正确");
        String kind = scan.path("kind").asText("");
        if (!SCAN_KINDS.contains(kind)) throw new IllegalArgumentException("扫码回复只能是文字、语音、图片、显形或不回复");
        optionalText(scan, "reply", 200, "扫码回复不能超过 200 字");
        // 同上(C-01);显形图 / 识别图 / 3D 模型同理 —— 不配 AR 的扫码段也会带着空串来
        dropBlankText((ObjectNode) scan, "audioUrl", "imageUrl", "overlayUrl", "markerUrl", "modelUrl");
        optionalMediaUrl(scan, "audioUrl", 512, "扫码语音地址不能超过 512 字", "扫码语音地址必须是 https 链接或站内路径");
        optionalMediaUrl(scan, "imageUrl", 512, "扫码图片地址不能超过 512 字", "扫码图片地址必须是 https 链接或站内路径");
        optionalMediaUrl(scan, "overlayUrl", 512, "扫码显形图地址不能超过 512 字", "扫码显形图地址必须是 https 链接或站内路径");
        JsonNode overlayScale = scan.get("overlayScale");
        if (overlayScale != null && !overlayScale.isNull()
                && (!overlayScale.isNumber() || overlayScale.asDouble() < 20d || overlayScale.asDouble() > 100d)) {
            throw new IllegalArgumentException("扫码显形图占比须为 20 至 100");
        }
        if ("VOICE".equals(kind) && scan.path("audioUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("扫码回语音就得配一段语音");
        }
        if ("IMAGE".equals(kind) && scan.path("imageUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("扫码回图片就得配一张图");
        }
        if ("OVERLAY".equals(kind) && scan.path("overlayUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("扫码要显形就得配一张叠加图");
        }
        if ("TEXT".equals(kind) && scan.path("reply").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("扫码回文字就得写一句话");
        }
        /* 显形档的真 AR:扫码仍是到店凭证(服务端比对这一站的码),AR 只决定扫中之后东西怎么出来 ——
           平面放置 = 识别地面/桌面,点一下放在那儿;图像识别 = 认出商家那张实物照片,贴着它长出来。
           AR 识别在手机上算、可伪造,所以它永远不参与完成判定。 */
        String arMode = scan.path("arMode").asText("");
        if (!arMode.isEmpty() && !SCAN_AR_MODES.contains(arMode)) {
            throw new IllegalArgumentException("显形的 AR 方式只能是不开、平面放置或图像识别");
        }
        if (!arMode.isEmpty() && !"NONE".equals(arMode) && !"OVERLAY".equals(kind)) {
            throw new IllegalArgumentException("只有显形档能开 AR");
        }
        optionalMediaUrl(scan, "markerUrl", 512, "识别图地址不能超过 512 字", "识别图地址必须是 https 链接或站内路径");
        if ("MARKER".equals(arMode) && scan.path("markerUrl").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("图像识别要配一张识别图");
        }
        /* 3D 模型(2026-09-23 用户定:有模型用模型,没模型退回立牌)。只在开了 AR 时有三维空间可放;
           OSS 签名地址带查询串,认 .glb 看路径。长度放到 1024:签名地址比普通图链长。 */
        optionalMediaUrl(scan, "modelUrl", 1024, "3D 模型地址不能超过 1024 字", "3D 模型地址必须是 https 链接或站内路径");
        String modelUrl = scan.path("modelUrl").asText("").trim();
        if (!modelUrl.isEmpty()) {
            if (arMode.isEmpty() || "NONE".equals(arMode)) {
                throw new IllegalArgumentException("开了 AR 才能放 3D 模型");
            }
            if (!modelUrl.split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT).endsWith(".glb")) {
                throw new IllegalArgumentException("3D 模型只支持 .glb");
            }
        }
        validateXp(scan, "扫码");
    }

    // ==================== R3 三个新玩法:排序 / 连线 / 分类 ====================

    /**
     * 排序题:玩家把 items 排成 answerOrder 的顺序。
     *
     * <p>★ {@code answerOrder} 绝不进公开投影(见 AdvancedGamePublicProjection#copySort),
     *   而且投影下发的 items 顺序也**必须与 answerOrder 无关** —— 若照配置顺序下发,
     *   商家按答案顺序录入时等于把答案印在题面上。
     *
     * <p>{@code maxAttempts} 是可选次数上限(0 = 不限):有上限时排完就用完这一局,
     *   与猜图同一条口径 —— 没上限的题卡死一个玩家,整条路线断在他身上。
     */
    private void validateSort(JsonNode sort) {
        if (!enabled(sort)) return;
        requireObject(sort, "排序题配置格式不正确");
        requiredText(sort, "prompt", MAX_GAME_PROMPT,
                "排序题题干不能为空", "排序题题干不能超过 " + MAX_GAME_PROMPT + " 字");

        JsonNode items = sort.get("items");
        if (items == null || !items.isArray()
                || items.size() < MIN_SORT_ITEMS || items.size() > MAX_SORT_ITEMS) {
            throw new IllegalArgumentException(
                    "排序题须配置 " + MIN_SORT_ITEMS + " 至 " + MAX_SORT_ITEMS + " 个待排项");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode item : items) {
            requireObject(item, "排序项格式不正确");
            String id = requiredGameId(item, "id", "排序项 id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("排序项 id 不能重复");
            requiredText(item, "label", MAX_GAME_LABEL,
                    "排序项名称不能为空", "排序项名称不能超过 " + MAX_GAME_LABEL + " 字");
            optionalMediaUrl(item, "img", 512,
                    "排序项配图地址不能超过 512 字", "排序项配图地址必须是 https 链接或站内路径");
        }

        JsonNode order = sort.get("answerOrder");
        if (!(order instanceof ArrayNode) || order.size() != ids.size()) {
            // 少了任何一项 = 那个位置永远排不对,而且不报错
            throw new IllegalArgumentException("排序答案必须覆盖全部待排项");
        }
        Set<String> ordered = new HashSet<>();
        for (JsonNode one : order) {
            String id = one.asText("");
            if (!ids.contains(id)) throw new IllegalArgumentException("排序答案引用了不存在的待排项");
            if (!ordered.add(id)) throw new IllegalArgumentException("排序答案不能重复引用同一个待排项");
        }

        int maxAttempts = sort.path("maxAttempts").asInt(0);
        if (maxAttempts < 0 || maxAttempts > 10) {
            throw new IllegalArgumentException("排序题可以排几次须为 0 至 10,0 表示不限");
        }
        // R14:排对时当场写本局状态(路线图效果写法);不配 = 老配置照旧。
        validateEffects(sort.get("effects"), "排序通过 effects");
        validateXp(sort, "排序");
    }

    /**
     * 连线题:左右各一组,玩家把左边每一条连到右边一条。
     *
     * <p>★ {@code pairs} 绝不进公开投影(见 AdvancedGamePublicProjection#copyMatch):
     *   哪条连哪条正是这一屏要连的东西。投影同时打乱右侧顺序,理由与排序题相同。
     *
     * <p>左、右条目数必须相同:连线是**一一对应**,两边不等时必有一条连不上,
     *   那不是"忘了填",是这道题本身没解。
     */
    private void validateMatch(JsonNode match) {
        if (!enabled(match)) return;
        requireObject(match, "连线题配置格式不正确");
        requiredText(match, "prompt", MAX_GAME_PROMPT,
                "连线题题干不能为空", "连线题题干不能超过 " + MAX_GAME_PROMPT + " 字");

        Set<String> leftIds = validateMatchSide(match.get("left"), "左侧");
        Set<String> rightIds = validateMatchSide(match.get("right"), "右侧");
        if (leftIds.size() != rightIds.size()) {
            throw new IllegalArgumentException("连线题两侧的条目数必须相同");
        }

        JsonNode pairs = match.get("pairs");
        if (!(pairs instanceof ArrayNode) || pairs.size() != leftIds.size()) {
            throw new IllegalArgumentException("连线答案必须让每一侧都恰好连到对面一条");
        }
        Set<String> pairedLeft = new HashSet<>();
        Set<String> pairedRight = new HashSet<>();
        for (JsonNode pair : pairs) {
            if (!pair.isArray() || pair.size() != 2
                    || !pair.get(0).isTextual() || !pair.get(1).isTextual()) {
                throw new IllegalArgumentException("连线答案的每一条必须是 [左 id, 右 id] 两个值");
            }
            String left = pair.get(0).asText();
            String right = pair.get(1).asText();
            if (!leftIds.contains(left)) throw new IllegalArgumentException("连线答案引用了不存在的左侧条目");
            if (!rightIds.contains(right)) throw new IllegalArgumentException("连线答案引用了不存在的右侧条目");
            if (!pairedLeft.add(left)) throw new IllegalArgumentException("连线答案的左侧条目不能重复");
            if (!pairedRight.add(right)) throw new IllegalArgumentException("连线答案的右侧条目不能重复");
        }
        // R14:全连对时当场写本局状态。
        validateEffects(match.get("effects"), "连线通过 effects");
        validateXp(match, "连线");
    }

    /** 连线题单侧:2 至 6 条,id 组内唯一且形状受限,label 必填。 */
    private Set<String> validateMatchSide(JsonNode side, String label) {
        if (side == null || !side.isArray()
                || side.size() < MIN_MATCH_SIDE || side.size() > MAX_MATCH_SIDE) {
            throw new IllegalArgumentException(
                    "连线题" + label + "须为 " + MIN_MATCH_SIDE + " 至 " + MAX_MATCH_SIDE + " 条");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode item : side) {
            requireObject(item, "连线题" + label + "条目格式不正确");
            String id = requiredGameId(item, "id", "连线题" + label + "条目 id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("连线题" + label + "条目 id 不能重复");
            requiredText(item, "label", MAX_GAME_LABEL,
                    "连线题" + label + "条目名称不能为空",
                    "连线题" + label + "条目名称不能超过 " + MAX_GAME_LABEL + " 字");
        }
        return ids;
    }

    /**
     * 分类题:每张卡片只有一个正确分类。
     *
     * <p>★ {@code answer}(itemId → binId 映射)绝不进公开投影
     *   (见 AdvancedGamePublicProjection#copyClassify)。
     *
     * <p>answer 的键必须**恰好**是全部 itemId:少一个那张卡片没处放,多一个说明
     *   答案里混进了已经删掉的卡片 —— 两种都会让玩家永远分不对,且不报错。
     */
    private void validateClassify(JsonNode classify) {
        if (!enabled(classify)) return;
        requireObject(classify, "分类题配置格式不正确");
        requiredText(classify, "prompt", MAX_GAME_PROMPT,
                "分类题题干不能为空", "分类题题干不能超过 " + MAX_GAME_PROMPT + " 字");

        Set<String> binIds = validateClassifyList(classify.get("bins"),
                MIN_CLASSIFY_BINS, MAX_CLASSIFY_BINS, "分类");
        Set<String> itemIds = validateClassifyList(classify.get("items"),
                MIN_CLASSIFY_ITEMS, MAX_CLASSIFY_ITEMS, "待分类项");

        JsonNode answer = classify.get("answer");
        if (answer == null || !answer.isObject() || answer.size() != itemIds.size()) {
            throw new IllegalArgumentException("分类答案必须覆盖全部待分类项");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = answer.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!itemIds.contains(entry.getKey())) {
                throw new IllegalArgumentException("分类答案引用了不存在的待分类项");
            }
            if (!entry.getValue().isTextual() || !binIds.contains(entry.getValue().asText())) {
                throw new IllegalArgumentException("分类答案引用了不存在的分类");
            }
        }
        // R14:全分对时当场写本局状态。
        validateEffects(classify.get("effects"), "分类通过 effects");
        validateXp(classify, "分类");
    }

    /** 分类题的 bins / items 共用同一张形状:id 组内唯一、label 必填、条数在给定区间内。 */
    private Set<String> validateClassifyList(JsonNode rows, int min, int max, String label) {
        if (rows == null || !rows.isArray() || rows.size() < min || rows.size() > max) {
            throw new IllegalArgumentException(
                    "分类题的" + label + "须为 " + min + " 至 " + max + " 个");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows) {
            requireObject(row, "分类题的" + label + "格式不正确");
            String id = requiredGameId(row, "id", "分类题的" + label + " id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("分类题的" + label + " id 不能重复");
            requiredText(row, "label", MAX_GAME_LABEL,
                    "分类题的" + label + "名称不能为空",
                    "分类题的" + label + "名称不能超过 " + MAX_GAME_LABEL + " 字");
        }
        return ids;
    }

    /** 新玩法共用的 id 校验:1 至 32 位字母、数字、下划线或短横线。 */
    private String requiredGameId(JsonNode node, String field, String blankMessage) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(blankMessage);
        if (!GAME_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 只能使用 1 至 32 位字母、数字、下划线或短横线");
        }
        return value;
    }

    /**
     * 时间轴对照题:两个版本逐条并排,玩家标出「缺失 / 被改动」的条目(可多选,可来自任一侧),
     * 提交的 id 集合与 answer 完全相等才通过。
     *
     * <p>★ {@code answer} 绝不进公开投影(见 AdvancedGamePublicProjection#copyCompare):
     *   它就是这一屏要找出来的那几条。items 照**原顺序**下发 —— 时间轴顺序本身就是题面,
     *   与排序/连线不同,这里没有需要打乱的东西。
     *
     * <p>两侧 id 必须**全段唯一**:marked 是一个不分侧的 id 集合,任一侧出现重名,
     *   集合对错就分不清标的是哪一条。
     */
    private void validateCompare(JsonNode compare) {
        if (!enabled(compare)) return;
        requireObject(compare, "对照题配置格式不正确");
        requiredText(compare, "prompt", MAX_GAME_PROMPT,
                "对照题题干不能为空", "对照题题干不能超过 " + MAX_GAME_PROMPT + " 字");

        Set<String> ids = new HashSet<>();
        validateCompareSide(compare.get("left"), "左侧", ids);
        validateCompareSide(compare.get("right"), "右侧", ids);

        JsonNode answer = compare.get("answer");
        if (!(answer instanceof ArrayNode) || answer.size() == 0) {
            // 空答案 = 什么都不用标就过,这一屏当场作废
            throw new IllegalArgumentException("对照答案不能为空");
        }
        Set<String> marked = new HashSet<>();
        for (JsonNode one : answer) {
            String id = one.asText("");
            if (!ids.contains(id)) throw new IllegalArgumentException("对照答案引用了不存在的条目");
            if (!marked.add(id)) throw new IllegalArgumentException("对照答案不能重复标记同一条目");
        }

        if (compare.has("maxAttempts") && !compare.path("maxAttempts").isNull()) {
            int maxAttempts = compare.path("maxAttempts").asInt(0);
            if (maxAttempts < MIN_COMPARE_ATTEMPTS || maxAttempts > MAX_COMPARE_ATTEMPTS) {
                throw new IllegalArgumentException("对照题可以交几次须为 "
                        + MIN_COMPARE_ATTEMPTS + " 至 " + MAX_COMPARE_ATTEMPTS);
            }
        }
        // R19-INT 收口 b:对照玩法不在通过类效果的执行 / 冻结名单里(NodeJourneySnapshot.CHOICE_KITS
        // 不含 compare),手写 effects 没有链路会跑 —— 静默忽略比报错危险,发布期直接拦。
        JsonNode effects = compare.get("effects");
        if (effects != null && !effects.isNull() && !(effects.isArray() && effects.size() == 0)) {
            throw new IllegalArgumentException(
                    "对照题不支持 effects:时间轴玩法没有通过类效果的执行链路,配了会被静默忽略");
        }
        validateXp(compare, "对照");
    }

    /** 对照题单侧:2 至 20 条,label ≤20 字,每条必带 time(≤16 字)与 text(≤200 字)。 */
    private void validateCompareSide(JsonNode side, String label, Set<String> ids) {
        if (side == null || !side.isObject()) {
            throw new IllegalArgumentException("对照题" + label + "格式不正确");
        }
        requiredText(side, "label", MAX_COMPARE_LABEL,
                "对照题" + label + "标题不能为空",
                "对照题" + label + "标题不能超过 " + MAX_COMPARE_LABEL + " 字");
        JsonNode items = side.get("items");
        if (items == null || !items.isArray()
                || items.size() < MIN_COMPARE_SIDE || items.size() > MAX_COMPARE_SIDE) {
            throw new IllegalArgumentException("对照题" + label + "须为 "
                    + MIN_COMPARE_SIDE + " 至 " + MAX_COMPARE_SIDE + " 条");
        }
        for (JsonNode item : items) {
            requireObject(item, "对照题" + label + "条目格式不正确");
            String id = requiredGameId(item, "id", "对照题" + label + "条目 id 不能为空");
            if (!ids.add(id)) throw new IllegalArgumentException("对照题两侧条目 id 不能重复");
            requiredText(item, "time", MAX_COMPARE_TIME,
                    "对照题条目时间不能为空", "对照题条目时间不能超过 " + MAX_COMPARE_TIME + " 字");
            requiredText(item, "text", MAX_COMPARE_TEXT,
                    "对照题条目内容不能为空", "对照题条目内容不能超过 " + MAX_COMPARE_TEXT + " 字");
        }
    }

    // ==================== 预制人生新玩法:建档 / 拍照审核 / 留言 / 限时打字 ====================

    /**
     * 建档:1 至 8 个自由问题(text / pick),pick 的加成挂在选项的 effects 上。
     *
     * <p>★ 加成走**现成的 effects 形状**(与分支选项 / 问答选项同一套),由旅程本局状态承接:
     * 幸运用保留的 sys.luck(只许 INC 1 至 3),精力 / 念头 / 不稳定度就是普通作者变量
     * (clue. / relation. / counter. 前缀)。所以这里直接复用 {@link #validateEffects},
     * 不新写一套校验 —— 两套 shape 迟早对不上。
     *
     * <p>★ effects 绝不进公开投影(见 AdvancedGamePublicProjection#copyProfile):
     * 数值加成是暗的,露出来就成了攻略表。
     */
    private void validateProfile(JsonNode profile) {
        if (!enabled(profile)) return;
        requireObject(profile, "建档配置格式不正确");
        optionalText(profile, "title", 64, "建档标题不能超过 64 字");
        optionalText(profile, "lead", 200, "建档说明不能超过 200 字");
        JsonNode avatar = profile.get("avatar");
        if (avatar != null && !avatar.isNull()) {
            requireObject(avatar, "建档头像配置格式不正确");
        }

        JsonNode questions = profile.get("questions");
        if (questions == null || !questions.isArray()
                || questions.size() < MIN_PROFILE_QUESTIONS || questions.size() > MAX_PROFILE_QUESTIONS) {
            throw new IllegalArgumentException(
                    "建档问题须为 " + MIN_PROFILE_QUESTIONS + " 至 " + MAX_PROFILE_QUESTIONS + " 条");
        }
        Set<String> keys = new HashSet<>();
        Set<String> optionKeys = new HashSet<>();
        for (JsonNode question : questions) {
            requireObject(question, "建档问题格式不正确");
            ((ObjectNode) question).put("required", true);
            String key = question.path("key").asText("").trim();
            if (!PROFILE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "建档问题的 key 只能是以字母开头的 1 至 16 位字母、数字或下划线");
            }
            if (!keys.add(key)) throw new IllegalArgumentException("建档问题的 key 不能重复");
            requiredText(question, "label", 40, "建档问题不能为空", "建档问题不能超过 40 字");
            String kind = question.path("kind").asText("");
            if (!PROFILE_KINDS.contains(kind)) {
                throw new IllegalArgumentException("建档问题的类型只能是 text 或 pick");
            }
            if ("text".equals(kind)) {
                int maxLength = question.path("maxLength").asInt(12);
                if (maxLength < 1 || maxLength > 40) {
                    throw new IllegalArgumentException("建档文本题的长度上限须为 1 至 40 字");
                }
                continue;
            }
            JsonNode options = question.get("options");
            if (options == null || !options.isArray()
                    || options.size() < MIN_PROFILE_OPTIONS || options.size() > MAX_PROFILE_OPTIONS) {
                throw new IllegalArgumentException("建档选择题须为 "
                        + MIN_PROFILE_OPTIONS + " 至 " + MAX_PROFILE_OPTIONS + " 个选项");
            }
            for (JsonNode option : options) {
                requireObject(option, "建档选项格式不正确");
                String optionKey = requiredGameId(option, "key", "建档选项 key 不能为空");
                // 选项 key 跨题也不许重名:加成按 key 定位,重名会写错变量且不报错
                if (!optionKeys.add(optionKey)) throw new IllegalArgumentException("建档选项的 key 不能重复");
                requiredText(option, "label", 32, "建档选项文案不能为空", "建档选项文案不能超过 32 字");
                validateEffects(option.get("effects"), "建档问题 " + key + " 选项 " + optionKey + " 的 effects");
            }
            validateProfileOverride(question.get("override"), options, key);
        }
        validateXp(profile, "建档");
    }

    /**
     * 咔哒:{ requires: [条件], option: 选项 key, voice: 改写后那句内心声音 }。
     * requires 必填 —— 没条件的改写等于这道题只有一个答案,作者该直接删掉其它选项。
     */
    private void validateProfileOverride(JsonNode override, JsonNode options, String key) {
        if (override == null || override.isNull()) return;
        String label = "建档问题 " + key + " 的咔哒";
        requireObject(override, label + "格式不正确");
        JsonNode requires = override.get("requires");
        if (requires == null || !requires.isArray() || requires.size() == 0) {
            throw new IllegalArgumentException(label + "要写明什么时候触发");
        }
        validateConditionList(requires, label + "条件");
        if (findOptionKey(options, override.path("option").asText("")) == null) {
            throw new IllegalArgumentException(label + "改写成的选项不在这道题里");
        }
        optionalText(override, "voice", 120, label + "那句话不能超过 120 字");
    }

    private static JsonNode findOptionKey(JsonNode options, String optionKey) {
        for (JsonNode option : options) if (optionKey.equals(option.path("key").asText())) return option;
        return null;
    }

    /**
     * 拍照审核:requirement(一句人话写清要拍到什么,1–60 字)+ 置信度阈值 + 可拍次数 + 用尽后的兜底。
     *
     * <p>判定全在服务端(契约 §2.2):照片与 requirement 一起交给视觉模型,不通过的由模型给理由。
     * 旧版「客户端启发式报分 + 服务端比 threshold」的 rule.mode / rule.threshold 整组作废,不再存在。
     */
    private void validatePhotoCheck(JsonNode photoCheck) {
        if (!enabled(photoCheck)) return;
        requireObject(photoCheck, "拍照审核配置格式不正确");
        requiredText(photoCheck, "title", 64, "拍照审核标题不能为空", "拍照审核标题不能超过 64 字");
        optionalText(photoCheck, "shotNote", 120, "拍摄要求不能超过 120 字");
        requiredText(photoCheck, "requirement", MAX_PHOTO_REQUIREMENT,
                "拍照要求不能为空(用一句人话写清要拍到什么)",
                "拍照要求不能超过 " + MAX_PHOTO_REQUIREMENT + " 字");
        JsonNode minConfidence = photoCheck.get("minConfidence");
        if (minConfidence != null && !minConfidence.isNull()
                && (!minConfidence.isNumber() || minConfidence.asDouble() < 0d || minConfidence.asDouble() > 100d)) {
            throw new IllegalArgumentException("拍照审核的置信度阈值须为 0 至 100");
        }
        int maxTries = photoCheck.path("maxTries").asInt(0);
        if (maxTries < 1 || maxTries > 10) {
            throw new IllegalArgumentException("拍照审核的可拍次数须为 1 至 10");
        }
        if (!PHOTO_CHECK_FALLBACKS.contains(photoCheck.path("fallback").asText(""))) {
            throw new IllegalArgumentException("拍照审核的兜底只能是 retake 或 pass");
        }
        // 取景轮廓:选填。判定仍然只看 requirement —— 轮廓是给玩家对位用的辅助，不是判定条件
        optionalMediaUrl(photoCheck, "frameUrl", MAX_PHOTO_FRAME_URL,
                "取景轮廓地址不能超过 " + MAX_PHOTO_FRAME_URL + " 字",
                "取景轮廓地址必须是 https 链接或站内路径");
        normalizeFrameOpacity(photoCheck);
        /* 拍物成卡(立体藏品卡契约 §2.1):这一段是「同一段两个玩法」的判别键。
           一律可空 —— 老模板里根本没有这三个键,要求它们存在等于把上线前的配置全判死。
           认不出的值要报错而不是回落默认:静默改写商家选的玩法比报错更糟。 */
        String cardMode = photoCheck.path("mode").asText("");
        if (!cardMode.isEmpty() && !PHOTO_CHECK_MODES.contains(cardMode)) {
            throw new IllegalArgumentException("拍照审核的模式只认 CARD(拍物成卡)");
        }
        optionalText(photoCheck, "cardTitle", MAX_CARD_TITLE, "藏品卡名称不能超过 " + MAX_CARD_TITLE + " 字");
        String cardStyle = photoCheck.path("cardStyle").asText("");
        if (!cardStyle.isEmpty() && !PHOTO_CARD_STYLES.contains(cardStyle)) {
            throw new IllegalArgumentException("藏品卡样式只能是 foil 或 plain");
        }
        validateXp(photoCheck, "拍照审核");
    }

    /**
     * 取景轮廓不透明度：缺省 40，越界夹到 0–100 后**写回配置**存起来。
     * 与 {@link #clampLevel} 同一立场：只夹这一段的这一个字段，不整段重写。
     */
    private void normalizeFrameOpacity(JsonNode photoCheck) {
        JsonNode opacity = photoCheck.get("frameOpacity");
        if (opacity == null || !opacity.isNumber()) return;
        int value = opacity.asInt();
        if (value < 0) value = 0;
        if (value > MAX_PHOTO_FRAME_OPACITY) value = MAX_PHOTO_FRAME_OPACITY;
        ((ObjectNode) photoCheck).put("frameOpacity", value);
    }

    private void validateAlbum(JsonNode album,boolean r5) {
        if (!enabled(album)) return;
        requireObject(album, "相册配置格式不正确");
        if(r5 && !album.has("images"))return;
        JsonNode images = album.path("images");
        if (!images.isArray() || images.size() < 1 || images.size() > 6) {
            throw new IllegalArgumentException("相册须配 1 至 6 张照片");
        }
        for (JsonNode image : images) {
            requireObject(image, "相册照片格式不正确");
            requiredText(image, "url", 1024, "请先上传相册照片", "相册照片地址不能超过 1024 字");
            optionalMediaUrl(image, "url", 1024, "相册照片地址不能超过 1024 字", "相册照片必须是 https 链接或站内路径");
            if (image.hasNonNull("line") && (!image.get("line").isTextual() || image.get("line").asText().length() > 40)) {
                throw new IllegalArgumentException("照片配文不能超过 40 字");
            }
        }
    }

    /**
     * 留言:写给下一个来这里的人。跨玩家存储走 play_note(见 PlayNoteMapper),
     * 这里只校验长度上限、预设短句与展示条数。
     *
     * <p>审核本轮只做长度 + 预设短句两道,机审不接(契约 §2.4 待裁决)。
     */
    private void validateNote(JsonNode note) {
        if (!enabled(note)) return;
        requireObject(note, "留言配置格式不正确");
        requiredText(note, "title", 64, "留言标题不能为空", "留言标题不能超过 64 字");
        requiredText(note, "prompt", 120, "留言提示不能为空", "留言提示不能超过 120 字");
        int maxLength = note.path("maxLength").asInt(40);
        if (maxLength < 1 || maxLength > 40) {
            throw new IllegalArgumentException("留言长度上限须为 1 至 40 字");
        }
        JsonNode presets = note.get("presets");
        if (presets != null && !presets.isNull()) {
            if (!presets.isArray() || presets.size() > MAX_NOTE_PRESETS) {
                throw new IllegalArgumentException("留言预设最多 " + MAX_NOTE_PRESETS + " 条");
            }
            for (JsonNode preset : presets) {
                if (!preset.isTextual() || preset.asText().trim().isEmpty()
                        || preset.asText().length() > maxLength) {
                    throw new IllegalArgumentException("留言预设不能为空且不能超过长度上限");
                }
            }
        }
        int showPrevious = note.path("showPrevious").asInt(0);
        if (showPrevious < 0 || showPrevious > MAX_NOTE_SHOW_PREVIOUS) {
            throw new IllegalArgumentException("留言展示前几条须为 0 至 " + MAX_NOTE_SHOW_PREVIOUS);
        }
        validateXp(note, "留言");
    }

    /**
     * 限时打字:目标文本 + 秒数 + 可试次数。
     *
     * <p>★ 必须先开表再提交:和 reaction / ballShake / quietHold / countdown / stopwatch 同路,
     * 先发 START_CHALLENGE(CHALLENGE_GAME.typein = 'typeIn'),服务端拿那一刻的服务器时间复核 ——
     * 设备时钟玩家改得动。
     */
    private void validateTypeIn(JsonNode typeIn) {
        if (!enabled(typeIn)) return;
        requireObject(typeIn, "限时打字配置格式不正确");
        requiredText(typeIn, "title", 64, "限时打字标题不能为空", "限时打字标题不能超过 64 字");
        String target = typeIn.path("target").asText("").trim();
        if (target.isEmpty() || target.length() > 40) {
            throw new IllegalArgumentException("打字目标须为 1 至 40 字");
        }
        int seconds = typeIn.path("seconds").asInt(0);
        if (seconds < 3 || seconds > 120) {
            throw new IllegalArgumentException("打字时限须为 3 至 120 秒");
        }
        int tries = typeIn.path("tries").asInt(0);
        if (tries < 0 || tries > 10) {
            throw new IllegalArgumentException("打字次数须为 0 至 10,0 表示不限");
        }
        validateXp(typeIn, "限时打字");
    }

    // ==================== 旅程体验模组化:requires / effects / check ====================

    /**
     * 条件条目:requires 与 check.advantageIf / disadvantageIf / mods[].when 是同一张形状 ——
     * 与路线图 edge.conditions 同一套写法。比较类 op 必须带整数 value;
     * HAS_TAG 只问 tag 在不在;NODE_COMPLETED 只问节点做过没有。
     *
     * <p>为什么不在这里查「键注册过没有」:这一步要连库,而校验器在发布、下单、
     * 开局三条链路上都被调用 —— 键注册表只在发布期对账(B1 侧)。
     */
    private void validateConditionList(JsonNode conditions, String label) {
        if (conditions == null || conditions.isNull()) return;
        if (!conditions.isArray()) {
            throw new IllegalArgumentException(label + " 必须是数组");
        }
        if (conditions.size() > MAX_STATE_REFS) {
            throw new IllegalArgumentException(label + " 最多配置 " + MAX_STATE_REFS + " 条");
        }
        for (JsonNode item : conditions) {
            requireObject(item, label + " 条件格式不正确");
            validateCondition(item, label);
        }
    }

    private void validateCondition(JsonNode item, String label) {
        String op = item.path("op").asText("");
        if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) {
            JsonNode quantity = item.path("quantity");
            if (!item.path("itemId").asText().matches("[a-z][a-z0-9_]{0,31}") || !quantity.isIntegralNumber()
                    || !quantity.canConvertToLong() || quantity.asLong() < 1 || quantity.asLong() > 999) throw new IllegalArgumentException(label + " 的物资条件不合法");
            return;
        }
        if ("NODE_COMPLETED".equals(op)) {
            JsonNode nodeId = item.get("nodeId");
            if (nodeId == null || !nodeId.canConvertToLong() || nodeId.asLong() <= 0L) {
                throw new IllegalArgumentException(label + " 的 NODE_COMPLETED 必须带正整数 nodeId");
            }
            return;
        }
        if ("HAS_TAG".equals(op)) {
            requireStateTag(item, label);
            return;
        }
        if (StoryVocabulary.isCondition(op)) {
            StoryVocabulary.validateCondition(item, label, null, (path, child) -> validateCondition(child, path));
            return;
        }
        if (!COMPARISON_OPS.contains(op)) {
            throw new IllegalArgumentException(label + " 的 op 只能是 "
                    + "EQ、NE、GT、GTE、LT、LTE、HAS_TAG 或 NODE_COMPLETED");
        }
        requireConditionVar(item, label);
        requireStateValue(item, label);
    }

    /**
     * 条件里的变量名:具名状态键(clue. / relation. / counter.)或只读系统变量(R14)。
     *
     * <p>系统变量只读、不进键注册表,形状之外还要核对 nodeId 属于本主题 —— 那一步要连库,
     * 由发布期闸做;这里拒绝一切没有列进白名单的 sys.*,效果里更是一个都不放行(sys.luck 例外)。
     */
    private void requireConditionVar(JsonNode item, String label) {
        String var = item.path("var").asText("").trim();
        if (var.startsWith("sys.")) {
            if (isReadableSysVar(var)) return;
            throw new IllegalArgumentException(label + " 的 var 不是可读的系统变量:" + var);
        }
        if (!STATE_VAR.matcher(var).matches()) {
            throw new IllegalArgumentException(label + " 的 var 必须是 clue. / relation. / counter. "
                    + "开头、点号后小写字母开头的具名键");
        }
    }

    /** 只读系统变量白名单(契约 v4):sys.hp / sys.luck / sys.asked.{nodeId}.{qid} / sys.mistakes.{nodeId} / sys.outcome.{nodeId}。 */
    private boolean isReadableSysVar(String var) {
        // 阶段 3:sys.exhausted 开放为只读 —— 创作者可以写「力竭时这一站更难 / 直接锁住」。
        // 它只由服务端在 HP 归零时写,效果侧一个都不放行(与 sys.hp 同口径)。
        // F6 T10:sys.momentum 只读(只由服务端结算 / 花动量写)。
        return "sys.hp".equals(var) || "sys.luck".equals(var) || "sys.exhausted".equals(var)
                || JourneyRpg.SYS_MOMENTUM.equals(var)
                || SYS_ASKED_VAR.matcher(var).matches() || SYS_NODE_VAR.matcher(var).matches();
    }

    /**
     * 效果条目:块跑完对具名状态键的写入。
     * SET 是置位、INC 是加减(可负),两者都要带整数 value;ADD_TAG 只带一个 tag 值。
     */
    private void validateEffects(JsonNode effects, String label) {
        if (effects == null || effects.isNull()) return;
        if (!effects.isArray()) {
            throw new IllegalArgumentException(label + " 必须是数组");
        }
        if (effects.size() > MAX_STATE_REFS) {
            throw new IllegalArgumentException(label + " 最多配置 " + MAX_STATE_REFS + " 条");
        }
        for (JsonNode item : effects) {
            requireObject(item, label + " 效果格式不正确");
            String op = item.path("op").asText("");
            if ("FLOOR".equals(op)) {
                requireStateVar(item, label); requireStateValue(item, label);
                if (!item.path("var").asText().startsWith("counter.") || item.path("value").asInt() < 1)
                    throw new IllegalArgumentException(label + " 的 FLOOR 仅支持正整数数值域");
                continue;
            }
            if ("ADD_TAG".equals(op)) {
                requireStateTag(item, label);
                continue;
            }
            if (StoryVocabulary.isEffect(op)) {
                StoryVocabulary.validateEffect(item, label);
                continue;
            }
            if (!EFFECT_OPS.contains(op)) {
                throw new IllegalArgumentException(label + " 的 op 只能是 SET、INC 或 ADD_TAG");
            }
            if (SYS_LUCK_VAR.equals(item.path("var").asText("").trim())) {
                validateSysLuckEffect(item, op, label);
                continue;
            }
            requireStateVar(item, label);
            requireStateValue(item, label);
        }
    }

    private JsonNode readNormalized(String raw) {
        try { return objectMapper.readTree(raw); }
        catch (Exception invalid) { throw new IllegalArgumentException("高级玩法配置不可读取", invalid); }
    }

    /**
     * variants:阶段 4 自适应难度。每条 = 一个 {@code when} 条件 + 一组 {@code relax} 放宽。
     *
     * <p>字段叫 relax 不叫 patch —— 把「只放宽」这条约束写进名字里,让违反它在读代码时就刺眼。
     * 条数上限复用 {@link #MAX_STATE_REFS};{@code when} 复用同一张条件 DSL,不新增语法。
     *
     * <p>运行期只在开局求值一次并写进本局配置快照,所以这里只管形状与方向;
     * 「开局冻结」「求值在下发白名单之前」两条约束在运行期那一侧。
     */
    private void validateVariants(JsonNode root) {
        // ⚠️ 收 root 而不是 root.get("variants") —— variants 是**根层控制项**(与 present /
        // mistakeTier 同类),不是玩法段。签名写成收段会被小程序侧「段名单一一对上」的门禁
        // 认成一个没接线的玩法段,那是归错类,不是门禁误报。
        JsonNode variants = root.get("variants");
        if (variants == null || variants.isNull()) return;
        if (!variants.isArray()) throw new IllegalArgumentException("variants 必须是数组");
        if (variants.size() > MAX_STATE_REFS) {
            throw new IllegalArgumentException("variants 最多配置 " + MAX_STATE_REFS + " 条");
        }
        int ordinal = 1;
        for (JsonNode item : variants) {
            requireObject(item, "variants 条目格式不正确");
            JsonNode when = item.get("when");
            if (when == null || when.isNull()) {
                throw new IllegalArgumentException("variants 的每条都必须带 when —— 无条件放宽等于直接改配置");
            }
            requireObject(when, "variants 的 when 格式不正确");
            validateCondition(when, "variants 的 when");
            validateRelax(root, item.get("relax"));
            // ⚠️ **逐条**模拟,不累加:运行期是「首条命中为准」,两条各自合法就都该放行。
            // 按「全部同时命中」累加去判会误杀 —— 那种局面运行期根本不会出现。
            requireRelaxedStillValid(root, item.get("relax"), ordinal);
            ordinal++;
        }
    }

    /**
     * 放宽之后仍必须是一份合法配置。做法是把这一条 relax 应用到配置副本上、剥掉 variants、
     * 拿**完整校验器**再跑一遍 —— 上界的真源因此只有各 kit 校验器那一份,这里不抄第二份表。
     *
     * <p>为什么放在发布期而不是运行期夹值:作者写 {@code +90000},发布放行、跑起来悄悄变成
     * 86400,两头都不知道那个数字没生效。与 validateMistakeTier 上写明的那条同一个道理 ——
     * 坏值直接拒,不在运行期悄悄落回。
     */
    private void requireRelaxedStillValid(JsonNode root, JsonNode relax, int ordinal) {
        ObjectNode simulated = ((ObjectNode) root).deepCopy();
        Iterator<String> paths = relax.fieldNames();
        while (paths.hasNext()) {
            String path = paths.next();
            int dot = path.indexOf('.');
            ObjectNode section = (ObjectNode) simulated.get(path.substring(0, dot));
            String field = path.substring(dot + 1);
            applyRelaxValue(section, field, relax.get(path).asText(""));
        }
        simulated.remove("variants");
        try {
            validateAndNormalize(objectMapper.writeValueAsString(simulated));
        } catch (IllegalArgumentException outOfRange) {
            // 带上第几条:配了多条时,不说是哪一条等于让作者自己一条条试。
            throw new IllegalArgumentException("variants 第 " + ordinal + " 条放宽后不合法:"
                    + outOfRange.getMessage());
        } catch (Exception impossible) {
            throw new IllegalArgumentException("variants 第 " + ordinal + " 条放宽后无法复核");
        }
    }

    /** relax:白名单内的字段 + 可证明是放宽的值。两条都不满足就拒,绝不静默忽略那一条。 */
    private void validateRelax(JsonNode root, JsonNode relax) {
        requireObject(relax, "variants 的 relax 格式不正确");
        if (relax.size() == 0) throw new IllegalArgumentException("variants 的 relax 不能为空");
        Iterator<String> names = relax.fieldNames();
        while (names.hasNext()) {
            String path = names.next();
            String kind = RELAX_FIELDS.get(path);
            if (kind == null) {
                throw new IllegalArgumentException("relax 只能调容错面(时限 / 可试次数 / 判定误差):" + path
                        + " 不在白名单里。题面(题目、选项、答案、奖励分)一个字都不许动");
            }
            // 这两条拦的都是「配了等于没配」:运行期原本静默跳过,作者永远不会发现。
            String section = path.substring(0, path.indexOf('.'));
            if (!enabled(root.get(section))) {
                throw new IllegalArgumentException("relax 的字段所在玩法没有启用:" + path);
            }
            // 弹球没开限时,seconds 根本不生效 —— 放宽它 = 配了等于没配
            if ("ballShake.seconds".equals(path) && !root.path("ballShake").path("timed").asBoolean(false)) {
                throw new IllegalArgumentException("弹球没开限时,放宽时长不会生效:" + path);
            }
            // ⚠️ 次数类字段**缺省即 0 即不限**(validateSort 是 path("maxAttempts").asInt(0),
            // 运行期是 if (maxAttempts > 0))。所以判据必须是 asInt(0)==0 而不是「显式写着 0」——
            // 要求字段存在会漏掉「不写」这一半,而那一半 +N 会把不限变成只剩 N 次,方向是收紧。
            if ("ATTEMPTS".equals(kind)
                    && root.path(section).path(path.substring(path.indexOf('.') + 1)).asInt(0) == 0) {
                throw new IllegalArgumentException(path + " 已经是不限,放宽不了更松");
            }
            JsonNode value = relax.get(path);
            String raw = value == null ? "" : value.asText("");
            Matcher delta = RELAX_DELTA.matcher(raw);
            // 越小越容易的一类:只认 -N;+N / =N 在这一类里都是收紧或证明不了方向
            if ("LOWER".equals(kind)) {
                if (!delta.matches() || !"-".equals(delta.group(1))) {
                    throw new IllegalArgumentException("relax 只能放宽:" + path + " 越小越容易,只能写 -N");
                }
                if (Integer.parseInt(delta.group(2)) == 0) {
                    throw new IllegalArgumentException("relax 的 " + path + " 写 -0 没有意义");
                }
                continue;
            }
            if (raw.startsWith("-")) {
                throw new IllegalArgumentException("relax 只能放宽:" + path + " 写 " + raw
                        + " 是收紧。线下真人体验里加压会直接变成投诉,这条闸不留口子");
            }
            if (!delta.matches()) {
                throw new IllegalArgumentException("relax 的 " + path + " 只能写 +N(放宽)"
                        + ("ATTEMPTS".equals(kind) ? " 或 =0(改成不限)" : ""));
            }
            boolean set = "=".equals(delta.group(1));
            int amount = Integer.parseInt(delta.group(2));
            if (!set) {
                if (amount == 0) {
                    throw new IllegalArgumentException("relax 的 " + path + " 写 +0 没有意义");
                }
                continue;                       // +N 两类都算放宽
            }
            // =N:只有次数类的 =0(不限)能证明是放宽;其余一律拒。
            if (!"ATTEMPTS".equals(kind) || amount != 0) {
                throw new IllegalArgumentException("relax 只能放宽:" + path + " 写 =" + amount
                        + " 证明不了比原值宽" + ("ATTEMPTS".equals(kind)
                        ? " —— 把「不限(0)」改成有限次数是收紧,数字反而更大" : ""));
            }
        }
    }

    /**
     * check:块内的技能判定。
     *
     * <p>tier 只有 easy / medium / hard 三档,配置里不出现 DC 数字;
     * checkId 是这一处的稳定标识(正则见共享契约),mods 是条件修正,
     * advantageIf / disadvantageIf 是优劣势条件,收尾两句文案加两组成功/失败效果。
     */
    private void validateCheck(JsonNode check) {
        if (!enabled(check)) return;
        requireObject(check, "check 判定配置格式不正确");
        if (!CHECK_ID.matcher(check.path("checkId").asText("").trim()).matches()) {
            throw new IllegalArgumentException("check 的 checkId 只能使用 1 至 64 位字母、数字、下划线或短横线");
        }
        if (!CHECK_TIERS.contains(check.path("tier").asText(""))) {
            throw new IllegalArgumentException("check 的 tier 只能是 easy、medium 或 hard");
        }
        optionalText(check, "skill", MAX_CHECK_TEXT, "check 的技能名不能超过 " + MAX_CHECK_TEXT + " 字");
        optionalText(check, "successText", MAX_CHECK_TEXT,
                "check 的成功文案不能超过 " + MAX_CHECK_TEXT + " 字");
        optionalText(check, "failText", MAX_CHECK_TEXT,
                "check 的失败文案不能超过 " + MAX_CHECK_TEXT + " 字");
        validateConditionList(check.get("advantageIf"), "check 的 advantageIf");
        validateConditionList(check.get("disadvantageIf"), "check 的 disadvantageIf");
        validateEffects(check.get("successEffects"), "check 的 successEffects");
        validateEffects(check.get("failEffects"), "check 的 failEffects");
        // R14:大成功 / 大失败的额外效果 —— 自然 20 在 successEffects 之后、自然 1 在
        // failEffects 之后追加执行;不配 = 老配置照旧。
        validateEffects(check.get("critEffects"), "check 的 critEffects");
        validateEffects(check.get("fumbleEffects"), "check 的 fumbleEffects");
        validateMods(check.get("mods"));
        checkFailCostTag(check);
        validateBargain(check.get("bargain"));
    }

    /** F6 T09 魔鬼交易单次加值上限:与 modCap 分开计,不让一笔交易压过三档难度的间距。 */
    private static final int MAX_BARGAIN_BONUS = 3;
    private static final int MAX_BARGAIN_EFFECTS = 4;
    /** 交易的代价只能是「给自己添麻烦」:写状态、贴标签、挂状态、推进钟;任何发放类效果一律拒。 */
    private static final Set<String> BARGAIN_EFFECT_OPS = new HashSet<String>(Arrays.asList(
            "SET", "INC", "ADD_TAG", StoryVocabulary.ADD_CONDITION, StoryVocabulary.TICK_CLOCK));

    /**
     * F6 T09 check.bargain:{label, bonus 1..3, effects[1..4]}。只有主题 journeyRules.rpg.bargain 开启时
     * 运行期才会提供这笔交易;这里只管形状。代价不得触碰 sys.*,也不得是物品 / 徽章 / XP 等发放。
     */
    private void validateBargain(JsonNode bargain) {
        if (bargain == null || bargain.isNull()) return;
        requireObject(bargain, "check 的 bargain 格式不正确");
        bargain.fieldNames().forEachRemaining(name -> {
            if (!"label".equals(name) && !"bonus".equals(name) && !"effects".equals(name)) {
                throw new IllegalArgumentException("check 的 bargain 不支持的字段:" + name);
            }
        });
        String label = bargain.path("label").asText("").trim();
        if (label.isEmpty() || label.length() > MAX_CHECK_TEXT) {
            throw new IllegalArgumentException("check 的 bargain 要写明代价(label),且不超过 " + MAX_CHECK_TEXT + " 字");
        }
        JsonNode bonus = bargain.get("bonus");
        if (bonus == null || !bonus.isIntegralNumber() || !bonus.canConvertToInt()
                || bonus.asInt() < 1 || bonus.asInt() > MAX_BARGAIN_BONUS) {
            throw new IllegalArgumentException("check 的 bargain 的 bonus 须为 1 至 " + MAX_BARGAIN_BONUS + " 的整数");
        }
        JsonNode effects = bargain.get("effects");
        if (!(effects instanceof ArrayNode) || effects.size() < 1 || effects.size() > MAX_BARGAIN_EFFECTS) {
            throw new IllegalArgumentException("check 的 bargain 的 effects 须为 1 至 " + MAX_BARGAIN_EFFECTS + " 条代价");
        }
        for (JsonNode effect : effects) {
            String op = effect.path("op").asText("");
            if (!BARGAIN_EFFECT_OPS.contains(op)) {
                throw new IllegalArgumentException("check 的 bargain 的代价只能是 SET、INC、ADD_TAG、ADD_CONDITION 或 TICK_CLOCK");
            }
            if (effect.path("var").asText("").trim().startsWith("sys.")) {
                throw new IllegalArgumentException("check 的 bargain 的代价不能写系统变量");
            }
        }
        validateEffects(effects, "check 的 bargain 的 effects");
    }

    /** mods:条件修正,when 条件 + 整数 value(或 F2 的 counter.* var),label 是给玩家看的一句人话。 */
    private void validateMods(JsonNode mods) {
        if (mods == null || mods.isNull()) return;
        if (!mods.isArray()) throw new IllegalArgumentException("check 的 mods 必须是数组");
        if (mods.size() > MAX_STATE_REFS) {
            throw new IllegalArgumentException("check 的 mods 最多配置 " + MAX_STATE_REFS + " 条");
        }
        for (JsonNode mod : mods) {
            requireObject(mod, "check 的 mods 条目格式不正确");
            JsonNode when = mod.get("when");
            if (when == null || when.isNull()) {
                throw new IllegalArgumentException("check 的 mods 必须带 when 条件");
            }
            validateCondition(when, "check 的 mods 的 when");
            // F2 T03:var = 从局状态读修正值(counter.* 属性),与静态 value 二选一。
            JsonNode var = mod.get("var");
            if (var != null && !var.isNull()) {
                if (!var.isTextual() || !STATE_VAR.matcher(var.asText()).matches() || !var.asText().startsWith("counter.")) {
                    throw new IllegalArgumentException("check 的 mods 的 var 必须是 counter. 开头的状态键");
                }
                if (mod.has("value") && !mod.get("value").isNull()) {
                    throw new IllegalArgumentException("check 的 mods 不能同时配置 var 与 value");
                }
            } else {
                requireStateValue(mod, "check 的 mods");
            }
            optionalText(mod, "label", MAX_CHECK_TEXT, "check 的 mods 的 label 不能超过 "
                    + MAX_CHECK_TEXT + " 字");
        }
    }

    /** 失败代价是一个具名 tag 键 —— 判失败时按它给玩家挂上,所以必须是 tag. 开头的合法键名。 */
    private void checkFailCostTag(JsonNode check) {
        JsonNode tag = check.get("failCostTag");
        if (tag == null || tag.isNull()) return;
        if (!tag.isTextual() || !STATE_TAG.matcher(tag.asText().trim()).matches()) {
            throw new IllegalArgumentException(
                    "check 的 failCostTag 必须是以 tag. 开头的具名状态键,如 tag.hurried");
        }
    }

    /** sys.luck 特例:只能 INC,值只能是 1 至 3(执行时再封顶 luck.max)。 */
    private void validateSysLuckEffect(JsonNode item, String op, String label) {
        if (!"INC".equals(op)) {
            throw new IllegalArgumentException(label + " 的 sys.luck 只支持 INC");
        }
        JsonNode value = item.get("value");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < 1 || value.asInt() > 3) {
            throw new IllegalArgumentException(label + " 的 sys.luck 只能是 1 至 3 的整数");
        }
    }

    /**
     * R18:七段节点叙事的旧校验已迁往章节故事流保存路径(ChapterNarrativeBlocks)。
     * 本方法保留一个公开入口,让故事流侧的 beat 块 when 复用同一套 cond 形状校验,
     * 不复制第二份条件规则。
     */
    public static void validateWhenCondition(JsonNode when, String label) {
        new AdvancedGameConfigValidator().validateConditionNode(when, label);
    }

    // ==================== 双人视角:roleViews(R16-DUO)====================

    /**
     * roleViews:同一节点按 A / B 角色分半的只读内容。单人游玩时两半都下发,
     * 队伍里只下发本人那一半(下发给谁由运行期按队员次序判定,见 PlayEncounterServiceImpl)。
     *
     * <p>形状约束:恰好两个角色且 id 只能是 A / B;每个角色恰好一个 view,
     * view 的 roleId 必须指向这两个角色之一且不重复;一切文本 ≤200;items ≤8。
     * 缺省 / enabled=false = 老配置照旧,这一段不下发也不校验。
     */
    private void validateRoleViews(JsonNode roleViews) {
        if (!enabled(roleViews)) return;
        requireObject(roleViews, "角色视角配置格式不正确");
        JsonNode roles = roleViews.get("roles");
        if (!(roles instanceof ArrayNode) || roles.size() != ROLE_VIEW_IDS.size()) {
            throw new IllegalArgumentException("角色视角的角色必须恰好是 A、B 两个");
        }
        Set<String> roleIds = new HashSet<>();
        for (JsonNode role : roles) {
            requireObject(role, "角色视角的角色格式不正确");
            String id = role.path("id").asText("").trim();
            if (!ROLE_VIEW_IDS.contains(id)) {
                throw new IllegalArgumentException("角色视角的角色 id 只能是 A 或 B");
            }
            if (!roleIds.add(id)) throw new IllegalArgumentException("角色视角的角色 id 不能重复");
            requiredText(role, "label", MAX_ROLE_VIEW_TEXT,
                    "角色视角的角色名称不能为空", "角色视角的角色名称不能超过 " + MAX_ROLE_VIEW_TEXT + " 字");
        }
        JsonNode views = roleViews.get("views");
        if (!(views instanceof ArrayNode) || views.size() != roleIds.size()) {
            throw new IllegalArgumentException("角色视角必须为每个角色各配一个 view");
        }
        Set<String> viewed = new HashSet<>();
        for (JsonNode view : views) {
            requireObject(view, "角色视角的 view 格式不正确");
            String roleId = view.path("roleId").asText("").trim();
            if (!roleIds.contains(roleId)) {
                throw new IllegalArgumentException("角色视角的 view 引用了不存在的角色");
            }
            if (!viewed.add(roleId)) throw new IllegalArgumentException("同一个角色只能配置一个 view");
            optionalText(view, "title", MAX_ROLE_VIEW_TEXT,
                    "角色视角的标题不能超过 " + MAX_ROLE_VIEW_TEXT + " 字");
            optionalText(view, "body", MAX_ROLE_VIEW_TEXT,
                    "角色视角的正文不能超过 " + MAX_ROLE_VIEW_TEXT + " 字");
            JsonNode items = view.get("items");
            if (items == null || items.isNull()) continue;
            if (!items.isArray() || items.size() > MAX_ROLE_VIEW_ITEMS) {
                throw new IllegalArgumentException("角色视角的 items 最多配置 " + MAX_ROLE_VIEW_ITEMS + " 条");
            }
            for (JsonNode item : items) {
                requireObject(item, "角色视角的 items 条目格式不正确");
                requiredText(item, "label", MAX_ROLE_VIEW_TEXT,
                        "角色视角的 items 条目名称不能为空",
                        "角色视角的 items 条目名称不能超过 " + MAX_ROLE_VIEW_TEXT + " 字");
                requiredText(item, "text", MAX_ROLE_VIEW_TEXT,
                        "角色视角的 items 条目内容不能为空",
                        "角色视角的 items 条目内容不能超过 " + MAX_ROLE_VIEW_TEXT + " 字");
            }
        }
        if (!viewed.containsAll(roleIds)) {
            throw new IllegalArgumentException("角色视角必须为每个角色各配一个 view");
        }
    }

    /** when 为 null = 无条件,不校验;给了就必须是与 requires 同一套写法的 cond。 */
    private void validateConditionNode(JsonNode when, String label) {
        if (when == null || when.isNull()) return;
        validateCondition(when, label);
    }

    private void requireStateVar(JsonNode item, String label) {
        String var = item.path("var").asText("").trim();
        if (!STATE_VAR.matcher(var).matches()) {
            throw new IllegalArgumentException(label + " 的 var 必须是 clue. / relation. / counter. "
                    + "开头、点号后小写字母开头的具名键");
        }
    }

    private void requireStateTag(JsonNode item, String label) {
        String tag = item.path("value").asText("").trim();
        if (!STATE_TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException(label + " 的 tag 值必须是 tag. 开头、"
                    + "点号后小写字母开头的具名键");
        }
    }

    private void requireStateValue(JsonNode item, String label) {
        if (!item.has("value") || item.get("value").isNull()) {
            throw new IllegalArgumentException(label + " 必须带整数 value");
        }
        checkStateValue(item.get("value"), label);
    }

    private void checkStateValue(JsonNode value, String label) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(label + " 的 value 必须是整数");
        }
        int number = value.asInt();
        if (number < MIN_STATE_VALUE || number > MAX_STATE_VALUE) {
            throw new IllegalArgumentException(label + " 的 value 须为 " + MIN_STATE_VALUE + " 至 "
                    + MAX_STATE_VALUE + " 之间的整数");
        }
    }

    /** 几个新玩法共用的奖励分校验:0 表示不给分 */
    /**
     * 抛硬币:两面各配一句「叫什么」和「那就做什么」。
     *
     * <p>「正面 / 反面」本身没有意义,写上「这杯店家请 / 这杯你请」才是一个玩法 ——
     * 所以 action 是必填的,label 反而可以缺省。
     */
    private void validateCoinFlip(JsonNode coin) {
        if (!enabled(coin)) return;
        requireObject(coin, "抛硬币配置格式不正确");
        optionalText(coin, "kicker", 32, "抛硬币标题不能超过 32 字");
        validateCoinFace(coin.get("heads"), "正面");
        validateCoinFace(coin.get("tails"), "反面");
        validateXp(coin, "抛硬币");
    }

    private void validateCoinFace(JsonNode face, String which) {
        requireObject(face, which + "配置格式不正确");
        optionalText(face, "label", 16, which + "名称不能超过 16 字");
        requiredText(face, "action", 60,
                which + "要做什么不能为空", which + "要做什么不能超过 60 字");
    }

    /**
     * 掷骰子:六个面各配一件事,一颗或两颗。
     *
     * <p>两颗时判的是点数之和(2–12),所以 faces 仍然只有六条 —— 和值对应哪件事
     * 由玩法自己说了算,不在这里摊成十一条。六条缺一条就有一面掷出来没内容。
     */
    private void validateDiceRoll(JsonNode dice) {
        if (!enabled(dice)) return;
        requireObject(dice, "掷骰子配置格式不正确");
        optionalText(dice, "kicker", 32, "掷骰子标题不能超过 32 字");
        String mode = dice.path("mode").asText("d6");
        if (!"d6".equals(mode) && !"d20".equals(mode)) {
            throw new IllegalArgumentException("掷骰子规则只能是 d6 或 d20");
        }
        if ("d20".equals(mode)) {
            JsonNode dc = dice.path("dc"), modifier = dice.path("modifier");
            if (!dc.isIntegralNumber() || !dc.canConvertToInt() || dc.asInt() < 1 || dc.asInt() > 40) {
                throw new IllegalArgumentException("D20 难度 DC 须为 1 至 40 的整数");
            }
            if (!modifier.isIntegralNumber() || !modifier.canConvertToInt()
                    || modifier.asInt() < -20 || modifier.asInt() > 20) {
                throw new IllegalArgumentException("D20 加值须为 -20 至 20 的整数");
            }
            String rollMode = dice.path("rollMode").asText();
            if (!"normal".equals(rollMode) && !"advantage".equals(rollMode) && !"disadvantage".equals(rollMode)) {
                throw new IllegalArgumentException("请选择 D20 掷骰方式");
            }
            requiredText(dice, "successText", 200, "D20 成功结果不能为空", "D20 成功结果不能超过 200 字");
            requiredText(dice, "failText", 200, "D20 失败结果不能为空", "D20 失败结果不能超过 200 字");
            validateXp(dice, "D20 检定");
            return;
        }
        int count = dice.path("diceCount").asInt(1);
        if (count != 1 && count != 2) {
            throw new IllegalArgumentException("掷骰子只能是一颗或两颗");
        }
        JsonNode faces = dice.get("faces");
        if (faces == null || !faces.isArray() || faces.size() != DICE_FACES) {
            throw new IllegalArgumentException("掷骰子必须配满六个面");
        }
        for (int i = 0; i < faces.size(); i++) {
            JsonNode face = faces.get(i);
            if(face!=null && face.isObject())face=face.get("action");
            if (face == null || !face.isTextual() || StringUtils.isBlank(face.asText())) {
                throw new IllegalArgumentException("掷骰子第 " + (i + 1) + " 面不能为空");
            }
            if (face.asText().trim().length() > 60) {
                throw new IllegalArgumentException("掷骰子第 " + (i + 1) + " 面不能超过 60 字");
            }
        }
        validateXp(dice, "掷骰子");
    }

    /**
     * 变色就点:几轮、达标毫秒。
     *
     * <p>达标毫秒不许低于 {@link #MIN_HUMAN_REACTION_MS} —— 比人的极限还快的门槛
     * 等于这一关谁都过不去,而商家填的时候看不出来。
     */
    private void validateReaction(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "变色就点配置格式不正确");
        optionalText(cfg, "kicker", 32, "变色就点标题不能超过 32 字");
        int rounds = cfg.path("rounds").asInt(0);
        if (rounds < 1 || rounds > MAX_REACTION_ROUNDS) {
            throw new IllegalArgumentException("变色就点轮数须为 1 至 " + MAX_REACTION_ROUNDS);
        }
        int goalMs = cfg.path("goalMs").asInt(0);
        if (goalMs < MIN_HUMAN_REACTION_MS || goalMs > MAX_REACTION_MS) {
            throw new IllegalArgumentException("达标毫秒须为 " + MIN_HUMAN_REACTION_MS
                    + " 至 " + MAX_REACTION_MS);
        }
        validateXp(cfg, "变色就点");
    }

    /** 弹球:撞够多少次。可选限时 —— 不限时就是撞满为止,不催。 */
    private void validateBallShake(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "弹球配置格式不正确");
        optionalText(cfg, "kicker", 32, "弹球标题不能超过 32 字");
        int goal = cfg.path("goal").asInt(0);
        if (goal < 1 || goal > MAX_SHAKE_GOAL) {
            throw new IllegalArgumentException("弹球撞击次数须为 1 至 " + MAX_SHAKE_GOAL);
        }
        if (cfg.path("timed").asBoolean(false)) {
            int seconds = cfg.path("seconds").asInt(0);
            if (seconds < 3 || seconds > MAX_CHALLENGE_SECONDS) {
                throw new IllegalArgumentException("弹球限时须为 3 至 " + MAX_CHALLENGE_SECONDS + " 秒");
            }
        }
        validateXp(cfg, "弹球");
    }

    /** 安静挑战:要安静多久。 */
    private void validateQuietHold(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "安静挑战配置格式不正确");
        optionalText(cfg, "kicker", 32, "安静挑战标题不能超过 32 字");
        int seconds = cfg.path("seconds").asInt(0);
        if (seconds < 5 || seconds > MAX_CHALLENGE_SECONDS) {
            throw new IllegalArgumentException("安静挑战时长须为 5 至 " + MAX_CHALLENGE_SECONDS + " 秒");
        }
        validateXp(cfg, "安静挑战");
    }

    /** 罗盘指向:把手机转向某个方位角,对准并保持几秒。★ 低价值段,禁止当到店凭证(现场感契约 §3.3)。 */
    private void validateCompass(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "罗盘指向配置格式不正确");
        optionalText(cfg, "kicker", 32, "罗盘指向标题不能超过 32 字");
        // bearing 缺省给 -1:没填和填了 0(正北)是两回事,不能拿默认值替商家发明一个目标方向
        int bearing = cfg.path("bearing").asInt(-1);
        if (bearing < 0 || bearing > 359) {
            throw new IllegalArgumentException("罗盘目标方位须为 0 至 359 度");
        }
        int tolerance = cfg.path("tolerance").asInt(0);
        if (tolerance < 5 || tolerance > 90) {
            throw new IllegalArgumentException("罗盘容差须为 5 至 90 度");
        }
        int holdSeconds = cfg.path("holdSeconds").asInt(0);
        if (holdSeconds < 1 || holdSeconds > 10) {
            throw new IllegalArgumentException("罗盘保持时长须为 1 至 10 秒");
        }
        optionalText(cfg, "hint", 60, "罗盘提示语不能超过 60 字");
        validateXp(cfg, "罗盘指向");
    }

    /** 喊一嗓子(现场感契约 §4):让这儿的声音够大,连续喊满几秒。与安静挑战同一把尺,判定反向。 */
    private void validateShout(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "喊一嗓子配置格式不正确");
        optionalText(cfg, "kicker", 32, "喊一嗓子标题不能超过 32 字");
        int seconds = cfg.path("seconds").asInt(0);
        if (seconds < 5 || seconds > MAX_CHALLENGE_SECONDS) {
            throw new IllegalArgumentException("喊一嗓子时长须为 5 至 " + MAX_CHALLENGE_SECONDS + " 秒");
        }
        validateXp(cfg, "喊一嗓子");
    }

    /** 倒计时:它就是倒计时本身,不判对错,所以只有时长和到点那句话。 */
    private void validateCountdown(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "倒计时配置格式不正确");
        optionalText(cfg, "kicker", 32, "倒计时标题不能超过 32 字");
        int seconds = cfg.path("seconds").asInt(0);
        if (seconds < 5 || seconds > MAX_COUNTDOWN_SECONDS) {
            throw new IllegalArgumentException("倒计时时长须为 5 至 " + MAX_COUNTDOWN_SECONDS + " 秒");
        }
        requiredText(cfg, "doneText", 60, "到点时说什么不能为空", "到点时说什么不能超过 60 字");
        validateXp(cfg, "倒计时");
    }

    /** 精准停表:目标秒数、容差毫秒、可以停几次(0 = 不限)。 */
    private void validateStopwatch(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "精准停表配置格式不正确");
        optionalText(cfg, "kicker", 32, "精准停表标题不能超过 32 字");
        double target = cfg.path("targetSeconds").asDouble(0d);
        if (target < 3d || target > MAX_STOPWATCH_TARGET_SECONDS) {
            throw new IllegalArgumentException("精准停表目标须为 3 至 "
                    + (int) MAX_STOPWATCH_TARGET_SECONDS + " 秒");
        }
        int tol = cfg.path("toleranceMs").asInt(0);
        if (tol < 50 || tol > 5000) {
            throw new IllegalArgumentException("精准停表容差须为 50 至 5000 毫秒");
        }
        int tries = cfg.path("tries").asInt(0);
        if (tries < 0 || tries > 10) {
            throw new IllegalArgumentException("精准停表次数须为 0 至 10,0 表示不限");
        }
        validateXp(cfg, "精准停表");
    }

    private void validateXp(JsonNode section, String label) {
        int xp = section.path("xp").asInt(0);
        if (xp < 0 || xp > 1000) {
            throw new IllegalArgumentException(label + "奖励分须为 0 至 1000");
        }
    }

    private static boolean enabled(JsonNode section) {
        return section != null && section.isObject() && section.path("enabled").asBoolean(false);
    }

    private void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(message);
    }

    private String requiredId(JsonNode node, String field, String message) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        if (value.length() > 64 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(field + " 只能使用 1 至 64 位字母、数字、下划线或短横线");
        }
        return value;
    }

    private void requiredText(JsonNode node, String field, int maxLength, String blankMessage, String longMessage) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(blankMessage);
        if (value.length() > maxLength) throw new IllegalArgumentException(longMessage);
    }

    private void optionalText(JsonNode node, String field, int maxLength, String longMessage) {
        if (node.has(field) && node.path(field).asText("").length() > maxLength) {
            throw new IllegalArgumentException(longMessage);
        }
    }

    private void optionalMediaUrl(JsonNode node, String field, int maxLength,
            String longMessage, String invalidMessage) {
        if (!node.has(field) || node.get(field).isNull()) return;
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual()) throw new IllegalArgumentException(invalidMessage);
        String value = valueNode.asText().trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(longMessage);
        boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
        if (value.isEmpty() || (!localPath && !value.startsWith("https://") && !value.matches("media-asset:[1-9][0-9]{0,18}"))) {
            throw new IllegalArgumentException(invalidMessage);
        }
    }

    /** 摘掉值为空白字符串的键(只摘字符串;null / 非文本原样留给后面的校验判)。 */
    private static void dropBlankText(ObjectNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && value.asText().trim().isEmpty()) node.remove(field);
        }
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<>();
        for (String value : values) result.add(value);
        return result;
    }
}
