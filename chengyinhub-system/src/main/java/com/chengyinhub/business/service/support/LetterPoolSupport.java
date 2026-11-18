package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * F03 字母池 / 字符集输入（CG-ALL-7 = B）：「疯狂猜图」式候选字池。
 *
 * <p>同一份池子规则服务两处既有玩法，不新建判题器：
 * <ul>
 *   <li>{@code hangman.inputMode = "pool"} + {@code hangman.pool[]}：逐字猜词的空白槽只能从池里取字；</li>
 *   <li>{@code qa.letterPool = {enabled, chars[], answerLength}}（打字问答）：内容侧已在用的字段形状。</li>
 * </ul>
 *
 * <p>规则：
 * <ol>
 *   <li>池里每项是 1 个完整字符（汉字可以），最多 {@link #MAX_POOL} 项；</li>
 *   <li>答案要填的字必须能从池里取到，按出现次数算；比较前 NFKC + 大小写按开关归一（与 AnswerNormalizer 同口径）；</li>
 *   <li>玩家提交里用了池外的字、或某个字用超了次数：判错前先拒收（不计次数）；</li>
 *   <li>{@code POOL_TRIM} 提示从「池 − 答案」里按本局固定顺序去掉干扰字，被去掉的字不再下发，也不能再用；</li>
 *   <li>下发顺序按本局种子固定打乱（{@code poolShuffle} 缺省 true），同一局刷新顺序不变。</li>
 * </ol>
 *
 * <p>本类不落库、不发奖励；状态节点由调用方持久化。
 */
public final class LetterPoolSupport {

    /** 池子上限（规格 3.1：≤30）。 */
    public static final int MAX_POOL = 30;
    /** POOL_TRIM 缺省每次去掉的干扰字数（规格 3.2）。 */
    public static final int DEFAULT_TRIM = 3;
    /** 本局状态里记录已用 POOL_TRIM 层级的字段名（挂在各玩法自己的状态节点下）。 */
    public static final String STATE_TRIMS = "poolTrims";
    public static final String INPUT_MODE_POOL = "pool";
    /** 本局打乱种子在玩法状态节点里的字段名（与 HangmanGameplay.POOL_SEED 同名）。 */
    public static final String STATE_SEED = "poolSeed";
    public static final String REJECT_MESSAGE = "只能使用给出的字";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LetterPoolSupport() {
    }

    // ==================== 配置读取 ====================

    /** 逐字猜词是否为字母池模式。 */
    public static boolean hangmanPool(JsonNode hangman) {
        return hangman != null && hangman.isObject() && hangman.path("enabled").asBoolean(false)
                && INPUT_MODE_POOL.equals(hangman.path("inputMode").asText(""));
    }

    /** 打字问答是否带字母池（只认 TYPE 模式）。 */
    public static boolean qaPool(JsonNode qa) {
        return qa != null && qa.isObject() && qa.path("enabled").asBoolean(false)
                && "TYPE".equals(qa.path("mode").asText("TYPE"))
                && qa.path("letterPool").isObject() && qa.path("letterPool").path("enabled").asBoolean(false);
    }

    /** 逐字猜词池子原始字符（作者顺序）。 */
    public static List<String> hangmanChars(JsonNode hangman) {
        return chars(hangman.get("pool"), "逐字猜词字母池");
    }

    /** 打字问答池子原始字符（作者顺序）。 */
    public static List<String> qaChars(JsonNode qa) {
        return chars(qa.path("letterPool").get("chars"), "问答字符池");
    }

    private static List<String> chars(JsonNode pool, String label) {
        if (pool == null || !pool.isArray() || pool.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (pool.size() > MAX_POOL) throw new IllegalArgumentException(label + "最多 " + MAX_POOL + " 个字");
        List<String> out = new ArrayList<>(pool.size());
        for (JsonNode item : pool) {
            if (item == null || !item.isTextual()) throw new IllegalArgumentException(label + "每项必须是文字");
            String text = item.asText();
            if (text.codePointCount(0, text.length()) != 1 || !Character.isLetterOrDigit(text.codePointAt(0))) {
                throw new IllegalArgumentException(label + "每项必须是 1 个字母、数字或汉字");
            }
            out.add(text);
        }
        return out;
    }

    // ==================== 校验 ====================

    /**
     * 文本里所有可比较字符（字母/数字/汉字）能否从池里拼出来（按次数）。
     * 分隔符（空格、标点）不占池子。
     */
    public static boolean spellable(List<String> pool, String text, boolean caseSensitive) {
        return missing(pool, text, caseSensitive).isEmpty();
    }

    /** 拼不出来的字（按次数，可能重复）；空表 = 可拼。 */
    public static List<String> missing(List<String> pool, String text, boolean caseSensitive) {
        Map<String, Integer> left = counts(pool, caseSensitive);
        List<String> missing = new ArrayList<>();
        for (String key : keys(text, caseSensitive)) {
            Integer have = left.get(key);
            if (have == null || have <= 0) {
                missing.add(key);
                continue;
            }
            left.put(key, have - 1);
        }
        return missing;
    }

    /** 逐字猜词：池子必须能拼出全部「空白槽」答案字；别名（若有）也必须能完整拼出。 */
    public static void validateHangman(JsonNode hangman, String blankAnswerChars, List<String> aliases,
            boolean caseSensitive) {
        if (!hangmanPool(hangman)) {
            JsonNode mode = hangman == null ? null : hangman.get("inputMode");
            if (mode != null && !mode.isNull() && !"keyboard".equals(mode.asText(""))
                    && !INPUT_MODE_POOL.equals(mode.asText(""))) {
                throw new IllegalArgumentException("逐字猜词输入方式只能是 keyboard 或 pool");
            }
            return;
        }
        if (hangman.has("poolShuffle") && !hangman.get("poolShuffle").isNull()
                && !hangman.get("poolShuffle").isBoolean()) {
            throw new IllegalArgumentException("逐字猜词 poolShuffle 必须是布尔值");
        }
        List<String> pool = hangmanChars(hangman);
        if (!spellable(pool, blankAnswerChars, caseSensitive)) {
            throw new IllegalArgumentException("逐字猜词字母池拼不出答案，缺少：" + String.join("", missing(pool, blankAnswerChars, caseSensitive)));
        }
        for (String alias : aliases) {
            if (!spellable(pool, alias, caseSensitive)) {
                throw new IllegalArgumentException("逐字猜词字母池拼不出别名「" + alias + "」");
            }
        }
    }

    /**
     * 打字问答字符池：主答案（answerText 里、分隔的第一个写法）必须能从池里拼出，
     * {@code answerLength}（若给）必须等于主答案可比较字符数。
     *
     * <p>别名不要求能拼：问答别名本来就服务键盘输入的旧客户端（繁体、拼音），
     * 它们不会出现在池子交互里；池子判定也只在「判错」之后才拒收，不会挡住正确答案。
     */
    public static void validateQa(JsonNode qa) {
        if (qa == null || !qa.isObject() || !qa.path("enabled").asBoolean(false)) return;
        JsonNode pool = qa.get("letterPool");
        if (pool == null || pool.isNull()) return;
        if (!pool.isObject()) throw new IllegalArgumentException("问答字符池格式不正确");
        if (!pool.path("enabled").asBoolean(false)) return;
        if (!"TYPE".equals(qa.path("mode").asText(""))) {
            throw new IllegalArgumentException("字符池只用于打字问答");
        }
        List<String> chars = qaChars(qa);
        String answer = primaryAnswer(qa.path("answerText").asText(""));
        if (answer.isEmpty()) throw new IllegalArgumentException("打字问答必须填正确答案");
        if (!spellable(chars, answer, false)) {
            throw new IllegalArgumentException("问答字符池拼不出答案，缺少：" + String.join("", missing(chars, answer, false)));
        }
        JsonNode length = pool.get("answerLength");
        if (length != null && !length.isNull()) {
            if (!length.isIntegralNumber() || length.asInt() < 1) {
                throw new IllegalArgumentException("问答字符池 answerLength 必须是正整数");
            }
            if (length.asInt() != keys(answer, false).size()) {
                throw new IllegalArgumentException("问答字符池 answerLength 与答案字数不一致");
            }
        }
    }

    /** answerText 里用、分隔的多写法：第一种是主答案（与既有 answerTextCandidates 同口径）。 */
    public static String primaryAnswer(String answerText) {
        if (answerText == null) return "";
        for (String part : answerText.split("[、]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    /** 下发给客户端的 answerLength：作者给了用作者的，否则按主答案可比较字数。 */
    public static int qaAnswerLength(JsonNode qa) {
        JsonNode given = qa.path("letterPool").get("answerLength");
        if (given != null && given.isIntegralNumber() && given.asInt() > 0) return given.asInt();
        return keys(primaryAnswer(qa.path("answerText").asText("")), false).size();
    }

    // ==================== 运行期 ====================

    /**
     * 本局可见池：作者池按本局种子固定打乱（关掉 shuffle 时保持作者顺序），再去掉 POOL_TRIM 移除的干扰字。
     *
     * @param answerChars 答案里要从池里取的字（干扰字 = 池 − 这些字）
     * @param kitState    玩法状态节点（读 {@link #STATE_TRIMS}）；可为 null
     */
    public static View view(List<String> pool, String answerChars, boolean caseSensitive, boolean shuffle,
            long seed, JsonNode kitState) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) order.add(i);
        if (shuffle) Collections.shuffle(order, new Random(seed * 31L + pool.size()));
        int trim = trimmedCount(kitState);
        List<Integer> decoys = decoyIndexes(pool, answerChars, caseSensitive, order);
        int removed = Math.min(trim, decoys.size());
        List<Integer> gone = decoys.subList(0, removed);
        List<String> visible = new ArrayList<>();
        for (Integer index : order) if (!gone.contains(index)) visible.add(pool.get(index));
        return new View(List.copyOf(visible), removed, decoys.size());
    }

    /** 打字问答的本局可见池（种子与 POOL_TRIM 记录都在 {@code state.qa} 里）。 */
    public static View qaView(JsonNode qa, JsonNode qaState) {
        long seed = qaState == null ? 0L : qaState.path(STATE_SEED).asLong(0L);
        return view(qaChars(qa), primaryAnswer(qa.path("answerText").asText("")), false,
                qa.path("letterPool").path("shuffle").asBoolean(true), seed, qaState);
    }

    /** 打字问答提交:判错后再查池外字;池外 / 超次数抛错 = 不落状态、不计次数。 */
    public static void requireQaChars(JsonNode qa, JsonNode qaState, String input) {
        if (!qaPool(qa)) return;
        if (!qaView(qa, qaState).allows(input, false)) throw new IllegalArgumentException(REJECT_MESSAGE);
    }

    /** 可见池 + 已移除数。 */
    public record View(List<String> chars, int removed, int decoys) {
        public ArrayNode charsNode() {
            ArrayNode array = MAPPER.createArrayNode();
            chars.forEach(array::add);
            return array;
        }

        /** 提交里每个字都来自可见池（按次数）。 */
        public boolean allows(String submitted, boolean caseSensitive) {
            return spellable(chars, submitted, caseSensitive);
        }
    }

    /**
     * 记一次 POOL_TRIM。同一层级只记一次（重看同一级不再多去字），返回本次之后累计去掉的干扰字数上限。
     *
     * @param kitState 玩法状态节点（直接写入，调用方持久化）
     */
    public static boolean recordTrim(ObjectNode kitState, int level, int count) {
        if (kitState == null) throw new IllegalArgumentException("字母池状态缺失");
        if (count < 1) count = DEFAULT_TRIM;
        ObjectNode trims = kitState.path(STATE_TRIMS).isObject()
                ? (ObjectNode) kitState.get(STATE_TRIMS) : kitState.putObject(STATE_TRIMS);
        String key = String.valueOf(level);
        if (trims.has(key)) return false;
        trims.put(key, count);
        return true;
    }

    /** 累计应去掉的干扰字数（各层之和）。 */
    public static int trimmedCount(JsonNode kitState) {
        if (kitState == null) return 0;
        JsonNode trims = kitState.path(STATE_TRIMS);
        if (!trims.isObject()) return 0;
        int total = 0;
        for (JsonNode value : trims) total += Math.max(0, value.asInt(0));
        return total;
    }

    /** POOL_TRIM 层的数量：payload 是正整数就用它，否则缺省 3。 */
    public static int trimCount(JsonNode payload) {
        if (payload != null && payload.isIntegralNumber() && payload.asInt() > 0) return payload.asInt();
        if (payload != null && payload.isTextual()) {
            try {
                int value = Integer.parseInt(payload.asText().trim());
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // 非数字文案按缺省
            }
        }
        return DEFAULT_TRIM;
    }

    /** 本局种子：同一局固定，不同局不同；不用随机数，刷新/重登顺序不变。 */
    public static long seed(Long sessionId, Long nodeId) {
        long s = sessionId == null ? 0L : sessionId;
        long n = nodeId == null ? 0L : nodeId;
        return s * 1_000_003L + n;
    }

    // ==================== 内部 ====================

    /** 干扰字下标（按下发顺序）：池里按次数扣掉答案字后剩下的就是干扰字。 */
    private static List<Integer> decoyIndexes(List<String> pool, String answerChars, boolean caseSensitive,
            List<Integer> order) {
        Map<String, Integer> needed = new HashMap<>();
        for (String key : keys(answerChars, caseSensitive)) needed.merge(key, 1, Integer::sum);
        // 先按作者顺序把答案字「占住」，保证被去掉的永远不是答案要用的那一份
        boolean[] reserved = new boolean[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            String key = key(pool.get(i), caseSensitive);
            Integer need = needed.get(key);
            if (need != null && need > 0) {
                reserved[i] = true;
                needed.put(key, need - 1);
            }
        }
        List<Integer> decoys = new ArrayList<>();
        for (Integer index : order) if (!reserved[index]) decoys.add(index);
        return decoys;
    }

    private static Map<String, Integer> counts(List<String> pool, boolean caseSensitive) {
        Map<String, Integer> counts = new HashMap<>();
        for (String item : pool) counts.merge(key(item, caseSensitive), 1, Integer::sum);
        return counts;
    }

    /** 文本 → 可比较字符键（NFKC 后去分隔符，按开关折大小写）。 */
    static List<String> keys(String text, boolean caseSensitive) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC);
        folded.codePoints().forEach(cp -> {
            if (!Character.isLetterOrDigit(cp)) return;
            out.add(key(new String(Character.toChars(cp)), caseSensitive));
        });
        return out;
    }

    private static String key(String one, boolean caseSensitive) {
        String folded = Normalizer.normalize(one, Normalizer.Form.NFKC);
        if (caseSensitive) return folded;
        return folded.toUpperCase(java.util.Locale.ROOT).toLowerCase(java.util.Locale.ROOT);
    }
}
