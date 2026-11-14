package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逐字猜词（Hangman）封闭判定：吃冻结配置、本局状态和玩家补入的空白槽，出本局安全投影。
 *
 * <p><b>唯一依据</b>：用户原始资料 {@code docs/questo-teardown/施工文档.md} v1.3.2。本类只实现资料写明的行为。
 * <ul>
 *   <li>M.7（12745 行）「Hangman {@code format} 预填（如 20 槽给 15）」：{@code format} 是<b>槽位数组</b>，
 *       槽数由答案决定，其中一部分槽开局就给字符。</li>
 *   <li>17.6（2761 行）「Hangman 只填空白字母：第 2 关槽位已给 {@code E____ S}，只输入 {@code NDLES}，
 *       App 拼回 {@code ENDLESS}」：玩家<b>只补空白槽</b>，服务端把「预填字符 + 补入字符」重建为完整答案。</li>
 *   <li>17.6「第 3 关输入 {@code NICOLASPERNELLE}（无空格），App 重建为 {@code NICOLAS AND PERNELLE}」：
 *       重建后的比较忽略分隔符；分隔符本身不是可填空槽，始终可见。</li>
 *   <li>MP-05（886 行）提案 {@code answerSpec:{acceptedAliases,normalizationPolicy,caseSensitive}}：
 *       别名与大小写规范化挂在答案合同上。{@code normalizationPolicy} 的最终口径等 C03，
 *       本类只固化原文实测的「忽略分隔符」，大小写由作者开关（缺省不区分）。</li>
 *   <li>BE-16（775 行）「{@code hangman.format} 预填映射为 L3」：预填与逐位揭示属提示阶梯 L3，
 *       运行时逐位揭示走 {@link #revealAt}，与开局预填共用同一份状态。</li>
 * </ul>
 *
 * <p><b>刻意不做</b>（资料未写，禁止自行设计）：不做传统猜字母（猜中一个字母揭示全部相同位置）、
 * 不做 LETTER/WORD/BOTH 模式开关、本类不设答题次数上限或失败耗尽（答题机会归共用题级配置，见 0.4）、
 * 不设宝石/金币/积分/XP/罚时/榜单加成或排序数值、不改题型目录、不把 {@code typeIn}（限时打字，
 * {@code target/seconds/tries}）改名复用。
 *
 * <p><b>产品边界</b>：原文 884 行「中文无意义套用英文Hangman不作为新题型目标」。本类只是作者填写词语的判定机制；
 * 中文与补充平面字符按 Unicode 完整字符处理，是对作者已有输入的兼容，不新增中文猜词题。
 *
 * <p><b>本类边界</b>：不落库、不取余额、不发奖励、不写回执、不做幂等。持久化、事务、扣减、回执与完成证明
 * 由运行时 owner 负责。{@code answer}/{@code aliases}/{@code format} 是冻结配置里的秘密，
 * 任何投影与 {@link #safeConfig} 都不下发。
 */
public final class HangmanGameplay {
    /** 本局状态在会话 JSON 里的节点名。 */
    public static final String STATE_KEY = "hangman";
    /** 模板配置段名。 */
    public static final String SECTION = "hangman";

    /** 重建结果与冻结答案（含别名）一致。 */
    public static final String VERDICT_CORRECT = "CORRECT";
    /** 重建结果与冻结答案（含别名）不一致。 */
    public static final String VERDICT_WRONG = "WRONG";

    private static final int MAX_ANSWER_CODE_POINTS = 40;
    private static final int MAX_ALIASES = 10;
    private static final int STATE_VERSION = 1;
    /** 未揭示槽位的占位符（实测槽位样式 {@code E____ S}）。 */
    private static final char BLANK = '_';

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HangmanGameplay() {
    }

    // ==================== 作者保存 / 发布校验 ====================

    /**
     * 作者保存与发布前的形状校验；未启用即放行（沿本仓库其它玩法段的约定）。
     *
     * <p>校验：答案非空且含可比较字符、{@code format} 是槽位数组且槽数等于答案码点数、非空槽与答案同位一致、
     * 至少留一个空白槽、别名互不重复且不与答案重复。
     *
     * @throws IllegalArgumentException 任一形状不合法
     */
    public static void validate(JsonNode section) {
        if (!enabled(section)) return;
        if (!section.path("answer").isTextual()) throw new IllegalArgumentException("逐字猜词答案必须是文本");
        Layout layout = layout(section);
        boolean sensitive = caseSensitive(section);
        Set<String> keys = new LinkedHashSet<>();
        keys.add(answerKey(layout.cps, sensitive));
        for (String alias : aliasTexts(section)) {
            if (!keys.add(answerKey(alias.codePoints().toArray(), sensitive))) {
                throw new IllegalArgumentException("逐字猜词别名与答案或其它别名重复");
            }
        }
    }

    /**
     * 模板详情等「整实体下发」出口的白名单摘要：只给开局就该公开的形状，不含答案、别名与预填字符
     * （预填字符本身就是答案的一部分）。
     */
    public static ObjectNode safeConfig(JsonNode section) {
        ObjectNode safe = MAPPER.createObjectNode();
        if (!enabled(section)) {
            safe.put("enabled", false);
            return safe;
        }
        Layout layout = layout(section);
        safe.put("enabled", true);
        safe.put("length", layout.cps.length);
        safe.put("blankCount", layout.blankCount());
        safe.put("caseSensitive", caseSensitive(section));
        return safe;
    }

    // ==================== 局状态 ====================

    /**
     * 新开局状态。运行时在会话创建时写进 {@code state.hangman}，之后所有动作只改这个节点。
     *
     * <p>状态只记玩家补进空白槽的字符，用来重登恢复；预填字符与分隔符由冻结配置推导，
     * 不复制进状态，避免状态里出现第二份答案副本。
     */
    public static ObjectNode initialState(JsonNode section) {
        layout(section); // 先过配置形状校验：答案/format/空白槽不足都在这里拒
        ObjectNode state = MAPPER.createObjectNode();
        state.put("version", STATE_VERSION);
        state.putObject("filled");
        state.put("solved", false);
        state.put("lastVerdict", "");
        return state;
    }

    /**
     * 玩家视角安全投影：槽位形状（预填字符、空白槽、分隔符）与玩家已补入的字符。
     * 空白槽只给占位符，绝不下发答案。{@code kitState} 为空时按新局投影。
     */
    public static ObjectNode publicProjection(JsonNode section, JsonNode kitState) {
        Layout layout = layout(section);
        Map<Integer, String> filled = filledFrom(kitState);
        boolean solved = kitState != null && kitState.path("solved").asBoolean(false);
        return projection(section, layout, filled, solved);
    }

    // ==================== 判定 ====================

    /**
     * 应用一次提交。payload 三种形状，全部有资料依据：
     * <ul>
     *   <li>{@code {"slots":{"1":"N","2":"D",...}}}：按答案码点下标（0 起）显式给空白槽字符。
     *       只能填空白槽；越界、重复、填已给出槽一律拒收。</li>
     *   <li>{@code {"input":"NDLES"}}：字符数等于空白槽数时按顺序补空白槽（实测「只输入 NDLES」）；
     *       字符数等于答案总码点数时按整词填（预填槽必须与题面一致，冲突即拒）。</li>
     *   <li>{@code {"input":"<任意长度整词>"}}：字符数两种都对不上时，按整词候选与冻结答案/别名直接比对
     *       （MP-05 {@code acceptedAliases}；别名长度通常与答案不同，塞不进槽位）。</li>
     * </ul>
     *
     * <p>判定顺序固定：先重建「预填字符 + 补入字符」，去分隔符、按大小写开关归一，再与冻结答案及其别名比较。
     *
     * <p>本方法不改答题次数：重复提交、次数上限与失败后续由共用题级配置的调用方处理，
     * 调用方必须用原动作幂等键把重复提交挡在外面。
     *
     * @param kitState 必填，{@code state.hangman} 节点；本方法直接写入，调用方负责持久化
     */
    public static ObjectNode judge(JsonNode section, ObjectNode kitState, JsonNode payload) {
        if (kitState == null) {
            throw new IllegalArgumentException("逐字猜词局状态缺失，请先用 initialState 初始化 " + STATE_KEY);
        }
        Layout layout = layout(section);
        boolean sensitive = caseSensitive(section);
        Map<Integer, String> filled = filledFrom(kitState);

        if (kitState.path("solved").asBoolean(false)) {
            ObjectNode done = projection(section, layout, filled, true);
            done.put("verdict", VERDICT_CORRECT);
            done.put("alreadySolved", true);
            return done;
        }

        if (payload != null && payload.path("slots").isObject()) {
            Map<Integer, Integer> submission = explicitSlots(layout, payload.path("slots"));
            submission.forEach((index, cp) -> filled.put(index, new String(Character.toChars(cp))));
            requireComplete(layout, filled);
            String reconstructed = reconstruct(layout, filled);
            boolean correct = keyMatches(reconstructed.codePoints().toArray(), layout.cps, sensitive, section);
            return decide(section, layout, kitState, filled, reconstructed, correct);
        }

        String raw = payload == null ? "" : payload.path("input").asText("").trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("请补齐空白槽后再提交");
        int[] input = raw.codePoints().toArray();
        int blanks = layout.blankCount();
        if (input.length == blanks || input.length == layout.cps.length) {
            Map<Integer, Integer> submission = sequentialSlots(layout, input, input.length == layout.cps.length, sensitive);
            submission.forEach((index, cp) -> filled.put(index, new String(Character.toChars(cp))));
            requireComplete(layout, filled);
            String reconstructed = reconstruct(layout, filled);
            boolean correct = keyMatches(reconstructed.codePoints().toArray(), layout.cps, sensitive, section);
            return decide(section, layout, kitState, filled, reconstructed, correct);
        }
        // 长度对不上槽位：整词候选，只能考答案/别名本身（别名通常比答案长）。命中则补满所有空白槽。
        boolean correct = keyMatches(input, layout.cps, sensitive, section);
        if (correct) {
            for (int i = 0; i < layout.cps.length; i++) {
                if (!layout.isFixed(i)) filled.put(i, new String(Character.toChars(layout.cps[i])));
            }
        }
        return decide(section, layout, kitState, filled, correct ? reconstructedAnswer(layout) : "", correct);
    }

    /**
     * 提示阶梯 L3（M.7「首字母、逐位」/ BE-16「{@code hangman.format} 预填映射为 L3」）的运行时逐位揭示：
     * 只把指定空白槽显示为答案字符，不置 {@code solved}、不揭示其它槽、不改答题次数。
     *
     * <p>授权、免费额度、代价与幂等由 G4 提示策略负责。这里与开局预填共用同一份状态，
     * 不另建第二套揭示记录。L4「直接揭晓答案」不走这里。
     *
     * @param index 答案的码点下标（0 起），必须是空白槽
     */
    public static ObjectNode revealAt(JsonNode section, ObjectNode kitState, int index) {
        if (kitState == null) {
            throw new IllegalArgumentException("逐字猜词局状态缺失，请先用 initialState 初始化 " + STATE_KEY);
        }
        Layout layout = layout(section);
        if (index < 0 || index >= layout.cps.length) throw new IllegalArgumentException("揭示位置超出答案范围");
        if (layout.isFixed(index)) throw new IllegalArgumentException("该槽已由题面给出或为分隔符，不需要揭示");
        Map<Integer, String> filled = filledFrom(kitState);
        boolean already = filled.containsKey(index);
        filled.put(index, new String(Character.toChars(layout.cps[index])));
        if (!already) writeFilled(kitState, filled);
        boolean solved = kitState.path("solved").asBoolean(false);
        ObjectNode view = projection(section, layout, filled, solved);
        view.put("revealedIndex", index);
        view.put("alreadyRevealed", already);
        return view;
    }

    // ==================== 内部：判定收尾 ====================

    private static ObjectNode decide(JsonNode section, Layout layout, ObjectNode kitState,
                                     Map<Integer, String> filled, String reconstructed, boolean correct) {
        writeFilled(kitState, filled);
        kitState.put("solved", correct);
        kitState.put("lastVerdict", correct ? VERDICT_CORRECT : VERDICT_WRONG);
        ObjectNode view = projection(section, layout, filled, correct);
        view.put("verdict", correct ? VERDICT_CORRECT : VERDICT_WRONG);
        if (correct) view.put("answerText", reconstructed);
        return view;
    }

    // ==================== 内部：槽位模型 ====================

    /**
     * 槽位布局：每个 Unicode 码点一个槽位。{@code fixed} 表示该槽开局就有字符 ——
     * 分隔符（始终可见，不可猜）或 {@code format} 预填位。
     */
    private record Layout(int[] cps, boolean[] fixed, boolean[] separator) {
        int blankCount() {
            int count = 0;
            for (int i = 0; i < cps.length; i++) if (!fixed[i]) count++;
            return count;
        }

        boolean isFixed(int index) {
            return fixed[index];
        }
    }

    private static Layout layout(JsonNode section) {
        if (!section.isObject()) throw new IllegalArgumentException("逐字猜词配置格式不正确");
        JsonNode answerNode = section.get("answer");
        if (answerNode == null || !answerNode.isTextual()) throw new IllegalArgumentException("逐字猜词答案不能为空");
        String answer = answerNode.asText();
        if (answer.trim().isEmpty()) throw new IllegalArgumentException("逐字猜词答案不能为空");
        int[] cps = answer.codePoints().toArray();
        if (cps.length > MAX_ANSWER_CODE_POINTS) {
            throw new IllegalArgumentException("逐字猜词答案不能超过 " + MAX_ANSWER_CODE_POINTS + " 个字符（按完整字符计）");
        }
        boolean sensitive = caseSensitive(section);
        boolean[] fixed = new boolean[cps.length];
        boolean[] separator = new boolean[cps.length];
        boolean anyComparable = false;
        for (int i = 0; i < cps.length; i++) {
            separator[i] = !isComparable(cps[i]);
            fixed[i] = separator[i];
            anyComparable |= !separator[i];
        }
        if (!anyComparable) throw new IllegalArgumentException("逐字猜词答案至少要有一个可补的字母或数字");

        JsonNode format = section.get("format");
        if (format != null && !format.isNull()) {
            if (!format.isArray()) throw new IllegalArgumentException("逐字猜词 format 必须是槽位数组");
            ArrayNode slots = (ArrayNode) format;
            if (slots.size() != cps.length) {
                throw new IllegalArgumentException("逐字猜词 format 槽数必须等于答案字符数（当前 " + slots.size()
                        + " 槽 / 答案 " + cps.length + " 字符）");
            }
            for (int i = 0; i < cps.length; i++) {
                JsonNode given = slots.get(i);
                if (separator[i]) {
                    if (!isEmptySlot(given) && !sameChar(given, cps[i], sensitive)) {
                        throw new IllegalArgumentException("逐字猜词 format 第 " + (i + 1) + " 槽是分隔符，必须留空或与答案一致");
                    }
                    continue;
                }
                if (isEmptySlot(given)) {
                    fixed[i] = false;
                    continue;
                }
                if (!singleCodePoint(given)) {
                    throw new IllegalArgumentException("逐字猜词 format 第 " + (i + 1) + " 槽必须是单个字符或留空");
                }
                if (!sameChar(given, cps[i], sensitive)) {
                    throw new IllegalArgumentException("逐字猜词 format 第 " + (i + 1) + " 槽与答案不符");
                }
                fixed[i] = true;
            }
        }
        Layout layout = new Layout(cps, fixed, separator);
        if (layout.blankCount() == 0) throw new IllegalArgumentException("逐字猜词至少要留一个空白槽");
        return layout;
    }

    /**
     * 空白槽的三种写法:{@code null}、空串、以及真实样本里的字面量 {@code "_"}。
     *
     * <p>依据(用户提供 Flamel 7 组 {@code answer.hangman.format}):待填槽写的是字面量 {@code "_"},
     * 例如 {@code ["E","_","_","_","_","_","S"]} 对应 ENDLESS 的 5 个待填槽；
     * 固定字符与空格原样出现。因此 {@code "_"} 必须当空槽，不能当"与答案不符的固定字符"。
     */
    private static boolean isEmptySlot(JsonNode node) {
        if (node == null || node.isNull()) return true;
        if (!node.isTextual()) return false;
        String text = node.asText();
        return text.isEmpty() || text.equals(String.valueOf(BLANK));
    }

    private static boolean singleCodePoint(JsonNode node) {
        return node.isTextual() && node.asText().codePointCount(0, node.asText().length()) == 1;
    }

    private static boolean sameChar(JsonNode node, int cp, boolean caseSensitive) {
        if (!singleCodePoint(node)) return false;
        String text = node.asText();
        return canon(text.codePointAt(0), caseSensitive) == canon(cp, caseSensitive);
    }

    private static List<String> aliasTexts(JsonNode section) {
        JsonNode aliases = section.get("aliases");
        List<String> result = new ArrayList<>();
        if (aliases == null || aliases.isNull()) return result;
        if (!aliases.isArray()) throw new IllegalArgumentException("逐字猜词别名必须是数组");
        if (aliases.size() > MAX_ALIASES) throw new IllegalArgumentException("逐字猜词别名最多 " + MAX_ALIASES + " 条");
        for (JsonNode alias : aliases) {
            if (!alias.isTextual()) throw new IllegalArgumentException("逐字猜词别名必须是文本");
            String text = alias.asText().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("逐字猜词别名不能为空");
            if (text.codePointCount(0, text.length()) > MAX_ANSWER_CODE_POINTS) {
                throw new IllegalArgumentException("逐字猜词别名不能超过 " + MAX_ANSWER_CODE_POINTS + " 个字符");
            }
            boolean comparable = false;
            for (int cp : text.codePoints().toArray()) comparable |= isComparable(cp);
            if (!comparable) throw new IllegalArgumentException("逐字猜词别名至少要有一个字母或数字");
            result.add(text);
        }
        return result;
    }

    // ==================== 内部：提交解析 ====================

    /** {@code {"slots":{...}}}：显式下标，只接受空白槽。 */
    private static Map<Integer, Integer> explicitSlots(Layout layout, JsonNode slots) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = slots.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int index;
            try {
                index = Integer.parseInt(field.getKey().trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("槽位下标必须是整数：" + field.getKey());
            }
            if (index < 0 || index >= layout.cps.length) {
                throw new IllegalArgumentException("槽位下标超出答案范围：" + index);
            }
            if (layout.isFixed(index)) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 槽已由题面给出，只需补空白槽");
            }
            JsonNode value = field.getValue();
            if (!singleCodePoint(value)) throw new IllegalArgumentException("第 " + (index + 1) + " 槽必须是单个字符");
            if (result.put(index, value.asText().codePointAt(0)) != null) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 槽重复提交");
            }
        }
        return result;
    }

    /**
     * 顺序提交：{@code wholeWord=false} 时按空白槽顺序补；{@code true} 时按答案位置整词填
     * （遇到预填槽必须与题面给出的字符一致，否则拒收而不是静默丢弃）。
     */
    private static Map<Integer, Integer> sequentialSlots(Layout layout, int[] input, boolean wholeWord, boolean sensitive) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (!wholeWord) {
            int cursor = 0;
            for (int i = 0; i < layout.cps.length; i++) {
                if (layout.isFixed(i)) continue;
                result.put(i, input[cursor++]);
            }
            return result;
        }
        for (int i = 0; i < layout.cps.length; i++) {
            if (layout.isFixed(i)) {
                if (layout.separator[i]) continue;
                if (canon(input[i], sensitive) != canon(layout.cps[i], sensitive)) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " 槽题面已给出，整词提交与题面不一致");
                }
                continue;
            }
            result.put(i, input[i]);
        }
        return result;
    }

    /** 提交后每个空白槽都必须有字符：缺一个就整体拒收，不静默用答案补位。 */
    private static void requireComplete(Layout layout, Map<Integer, String> filled) {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < layout.cps.length; i++) {
            if (!layout.isFixed(i) && !filled.containsKey(i)) missing.add(i + 1);
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("还有空白槽未补齐：" + missing);
    }

    // ==================== 内部：重建与比较 ====================

    /** 只用「预填字符 + 玩家补入字符」重建；缺失位写占位符，绝不回填答案。 */
    private static String reconstruct(Layout layout, Map<Integer, String> filled) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < layout.cps.length; i++) {
            // 分隔符与 format 预填位一律取冻结答案本身：状态里即便混进这些下标也不能改写重建结果。
            if (layout.fixed[i]) {
                text.appendCodePoint(layout.cps[i]);
                continue;
            }
            text.append(filled.getOrDefault(i, String.valueOf(BLANK)));
        }
        return text.toString();
    }

    private static String reconstructedAnswer(Layout layout) {
        return new String(layout.cps, 0, layout.cps.length);
    }

    private static boolean keyMatches(int[] submitted, int[] answer, boolean sensitive, JsonNode section) {
        String key = answerKey(submitted, sensitive);
        if (key.isEmpty()) return false;
        if (key.equals(answerKey(answer, sensitive))) return true;
        for (String alias : aliasTexts(section)) {
            if (key.equals(answerKey(alias.codePoints().toArray(), sensitive))) return true;
        }
        // E-A4:旧键不中时再按 NFKC 后的键比一次(「ＡＢＣ」=「ABC」),只放宽。
        String nfkcKey = nfkcAnswerKey(submitted, sensitive);
        if (nfkcKey.isEmpty()) return false;
        if (nfkcKey.equals(nfkcAnswerKey(answer, sensitive))) return true;
        for (String alias : aliasTexts(section)) {
            if (nfkcKey.equals(nfkcAnswerKey(alias.codePoints().toArray(), sensitive))) return true;
        }
        return false;
    }

    /** 比对键：去分隔符（实测整词输入省略空格仍判对），大小写按开关归一。 */
    private static String answerKey(int[] cps, boolean caseSensitive) {
        StringBuilder key = new StringBuilder();
        for (int cp : cps) {
            if (!isComparable(cp)) continue;
            key.appendCodePoint(canon(cp, caseSensitive));
        }
        return key.toString();
    }

    private static String nfkcAnswerKey(int[] cps, boolean caseSensitive) {
        String folded = java.text.Normalizer.normalize(new String(cps, 0, cps.length),
                java.text.Normalizer.Form.NFKC);
        return answerKey(folded.codePoints().toArray(), caseSensitive);
    }

    private static boolean isComparable(int cp) {
        return Character.isLetterOrDigit(cp);
    }

    private static int canon(int cp, boolean caseSensitive) {
        return caseSensitive ? cp : Character.toLowerCase(Character.toUpperCase(cp));
    }

    // ==================== 内部：状态下读下写 ====================

    private static Map<Integer, String> filledFrom(JsonNode kitState) {
        Map<Integer, String> filled = new LinkedHashMap<>();
        JsonNode stored = kitState == null ? null : kitState.get("filled");
        if (stored != null && stored.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = stored.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                try {
                    int index = Integer.parseInt(field.getKey());
                    if (field.getValue().isTextual() && !field.getValue().asText().isEmpty()) {
                        filled.put(index, field.getValue().asText());
                    }
                } catch (NumberFormatException ignored) {
                    // 状态里出现非法键不影响投影；下次写入会整体重写。
                }
            }
        }
        return filled;
    }

    private static void writeFilled(ObjectNode kitState, Map<Integer, String> filled) {
        ObjectNode node = kitState.putObject("filled");
        for (Map.Entry<Integer, String> entry : filled.entrySet()) {
            node.put(String.valueOf(entry.getKey()), entry.getValue());
        }
    }
    // ==================== 内部：投影 ====================

    private static ObjectNode projection(JsonNode section, Layout layout, Map<Integer, String> filled, boolean solved) {
        ObjectNode view = MAPPER.createObjectNode();
        view.put("length", layout.cps.length);
        view.put("blankCount", layout.blankCount());
        view.put("caseSensitive", caseSensitive(section));
        view.put("solved", solved);
        StringBuilder display = new StringBuilder();
        ArrayNode slots = view.putArray("slots");
        for (int i = 0; i < layout.cps.length; i++) {
            ObjectNode slot = slots.addObject().put("i", i);
            if (layout.separator[i]) {
                slot.put("ch", new String(Character.toChars(layout.cps[i]))).put("kind", "SEPARATOR");
                display.appendCodePoint(layout.cps[i]);
                continue;
            }
            // 预填位一律取冻结答案本身：状态里即便混进这些下标也不能改写题面。
            if (layout.fixed[i]) {
                slot.put("ch", new String(Character.toChars(layout.cps[i]))).put("kind", "PREFILLED");
                display.appendCodePoint(layout.cps[i]);
                continue;
            }
            String filledChar = filled.get(i);
            if (filledChar != null) {
                slot.put("ch", filledChar).put("kind", "FILLED");
                display.append(filledChar);
            } else {
                slot.put("kind", "BLANK");
                display.append(BLANK);
            }
        }
        view.put("display", display.toString());
        return view;
    }

    // ==================== 内部：小工具 ====================

    private static boolean enabled(JsonNode section) {
        return section != null && section.isObject() && section.path("enabled").asBoolean(false);
    }

    private static boolean caseSensitive(JsonNode section) {
        JsonNode node = section.get("caseSensitive");
        if (node == null || node.isNull()) return false;
        if (!node.isBoolean()) throw new IllegalArgumentException("逐字猜词 caseSensitive 必须是布尔值");
        return node.asBoolean();
    }
}
