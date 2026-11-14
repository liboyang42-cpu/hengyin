package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E-A1~E-A5:共享归一器向量 + 「只放宽、不收紧」的性质测试。 */
class AnswerNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 全角数字字母归一() {
        assertEquals("3号线abc", AnswerNormalizer.normalize("３号线ＡＢＣ"));
        assertTrue(AnswerNormalizer.equivalent("3号线", "３号线"));
        assertTrue(AnswerNormalizer.equivalent("Line 3", "ＬＩＮＥ\u3000３"));
    }

    @Test
    void NBSP与U3000与零宽字符被去掉() {
        assertTrue(AnswerNormalizer.equivalent("故宫博物院", "故宫\u00A0博物院"));
        assertTrue(AnswerNormalizer.equivalent("故宫博物院", "故宫\u3000博物院"));
        assertTrue(AnswerNormalizer.equivalent("故宫博物院", "故宫\u200B博物院\uFEFF"));
        assertTrue(AnswerNormalizer.equivalent("AB", "A\u3000B"));
    }

    @Test
    void 首尾标点被去掉_中间保留() {
        assertTrue(AnswerNormalizer.equivalent("故宫博物院", "故宫博物院。"));
        assertTrue(AnswerNormalizer.equivalent("故宫博物院", "「故宫博物院。」"));
        assertTrue(AnswerNormalizer.equivalent("圣·约翰", "圣·约翰！"));
        assertFalse(AnswerNormalizer.equivalent("圣约翰", "圣·约翰"));
        assertEquals("3.5", AnswerNormalizer.normalize("3.5"));
    }

    @Test
    void 只有标点或空白不会命中任何答案() {
        assertEquals("", AnswerNormalizer.normalize("。。。"));
        assertFalse(AnswerNormalizer.equivalent("。", "。"));
        assertFalse(AnswerNormalizer.equivalent("答案", "  "));
        assertFalse(AnswerNormalizer.equivalent(null, "x"));
    }

    @Test
    void 繁简不自动互转_只能靠显式别名() {
        assertFalse(AnswerNormalizer.equivalent("故宫博物院", "故宮博物院"));
        AnswerPolicy policy = AnswerPolicy.of(readTree("{\"version\":1,\"acceptedAliases\":[\"故宮博物院\"]}"));
        assertTrue(policy.matches("故宫博物院", "故宮博物院"));
    }

    @Test
    void 备选分隔符含全角逗号和分号() {
        assertEquals(List.of("苹果", "梨", "桃", "李", "杏"), AnswerNormalizer.split("苹果，梨、桃,李；杏"));
        assertEquals(List.of("a", "b"), AnswerNormalizer.split("a;b"));
        assertTrue(BasicTextAnswer.matches("苹果，梨", "苹果"));
        assertTrue(BasicTextAnswer.matches("苹果，梨", "梨。"));
        assertTrue(BasicTextAnswer.matches("苹果;梨", "梨"));
    }

    @Test
    void 普通文字题新增向量() {
        assertTrue(BasicTextAnswer.matches("故宫博物院", "「故宫博物院。」"));
        assertTrue(BasicTextAnswer.matches("故宫博物院", "故宫\u00A0博物院"));
        assertTrue(BasicTextAnswer.matches("故宫博物院", "故宫\u3000博物院"));
        assertTrue(BasicTextAnswer.matches("3号线", "３号线"));
        assertFalse(BasicTextAnswer.matches("故宫博物院", "天坛"));
        assertFalse(BasicTextAnswer.matches("、、", "、"));
    }

    @Test
    void 无政策的高级问答也走共享归一() {
        assertTrue(AnswerPolicy.matchesAdvancedType("３号线", "3号线"));
        assertTrue(AnswerPolicy.matchesAdvancedType("苹果，梨", "苹果"));
        assertTrue(AnswerPolicy.matchesAdvancedType("苹果，梨", "梨"));
        assertTrue(AnswerPolicy.matchesAdvancedType("故宫博物院", "故宫博物院。"));
        assertTrue(AnswerPolicy.matchesAdvancedType("故宫博物院", "故宫\u00A0博物院"));
        assertFalse(AnswerPolicy.matchesAdvancedType("苹果，梨", "桃"));
        assertFalse(AnswerPolicy.matchesAdvancedType("", "a"));
        assertFalse(AnswerPolicy.matchesAdvancedType("a", "  "));
    }

    @Test
    void 配了政策的高级问答同样接受新写法() {
        AnswerPolicy policy = AnswerPolicy.of(readTree("{\"version\":1,\"acceptedAliases\":[\"Palace Museum\"]}"));
        List<String> candidates = AnswerPolicy.answerTextCandidates("苹果，梨");
        assertTrue(policy.matchesAny(candidates, "苹果"));
        assertTrue(policy.matchesAny(candidates, "梨。"));
        assertTrue(policy.matchesAny(candidates, "palace\u00A0museum"));
        assertFalse(policy.matchesAny(candidates, "桃"));
    }

    @Test
    void 逐字猜词全角等同半角() {
        ObjectNode section = JSON.createObjectNode().put("enabled", true).put("answer", "ABC");
        ObjectNode kit = HangmanGameplay.initialState(section);
        assertTrue(HangmanGameplay.judge(section, kit, JSON.createObjectNode().put("input", "ＡＢＣ")).path("solved").asBoolean());
        assertTrue(HangmanGameplay.judge(section, HangmanGameplay.initialState(section),
                JSON.createObjectNode().put("input", "abc")).path("solved").asBoolean());
    }

    // ---------- 性质:新规则只会多接受,不会少接受 ----------

    private static final String[] ATOMS = {
            "a", "B", "苹", "果", "3", "３", "Ａ", " ", "\u00A0", "\u3000", "\u200B", "、", ",", "，", ";", "；",
            "。", "「", "」", "·", "-", "x́", "²", "ß", "İ", "\t", "!", "！"};

    private static String random(Random r) {
        int n = r.nextInt(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ATOMS[r.nextInt(ATOMS.length)]);
        return sb.toString();
    }

    @Test
    void 性质_基础文字题旧规则命中的输入新规则必命中() {
        Random r = new Random(20261114L);
        int legacyHits = 0;
        for (int i = 0; i < 60000; i++) {
            String authored = random(r);
            String input = r.nextInt(4) == 0 ? authored : r.nextInt(2) == 0 ? mutate(authored, r) : random(r);
            if (BasicTextAnswer.matchesLegacy(authored, input)) {
                legacyHits++;
                assertTrue(BasicTextAnswer.matches(authored, input), "收紧了:[" + authored + "] [" + input + "]");
            }
        }
        assertTrue(legacyHits > 500, "样本里旧规则命中太少,性质测试没有意义:" + legacyHits);
    }

    @Test
    void 性质_高级问答无政策旧规则命中的输入新规则必命中() {
        Random r = new Random(1114L);
        int legacyHits = 0;
        for (int i = 0; i < 60000; i++) {
            String authored = random(r);
            String input = r.nextInt(4) == 0 ? authored : mutate(authored, r);
            if (AnswerPolicy.matchesLegacyAdvancedType(authored, input)) {
                legacyHits++;
                assertTrue(AnswerPolicy.matchesAdvancedType(authored, input), "收紧了:[" + authored + "] [" + input + "]");
            }
        }
        assertTrue(legacyHits > 500, "样本里旧规则命中太少:" + legacyHits);
    }

    @Test
    void 性质_高级问答带政策旧规则命中的输入新规则必命中() {
        Random r = new Random(7L);
        AnswerPolicy policy = AnswerPolicy.of(readTree("{\"version\":1,\"acceptedAliases\":[\"别名\",\"Ａlias\"]}"));
        int legacyHits = 0;
        for (int i = 0; i < 40000; i++) {
            String authored = random(r);
            String input = r.nextInt(4) == 0 ? authored : mutate(authored, r);
            boolean before = legacyPolicyMatches(policy, AnswerPolicy.legacyAnswerTextCandidates(authored), input);
            if (before) {
                legacyHits++;
                assertTrue(policy.matchesAny(AnswerPolicy.answerTextCandidates(authored), input),
                        "收紧了:[" + authored + "] [" + input + "]");
            }
        }
        assertTrue(legacyHits > 200, "样本里旧规则命中太少:" + legacyHits);
    }

    /** 旧 matchesAny 逻辑(别名 + LEGACY_V1),逐字复刻用来对照。 */
    private static boolean legacyPolicyMatches(AnswerPolicy policy, List<String> canonicals, String submitted) {
        String got = AnswerPolicy.normalize(submitted);
        if (got.isEmpty()) return false;
        boolean has = false;
        for (String c : canonicals) {
            if (StringUtils.isBlank(c)) continue;
            has = true;
            if (got.equalsIgnoreCase(AnswerPolicy.normalize(c))) return true;
        }
        if (!has) return false;
        for (String a : policy.acceptedAliases()) {
            if (got.equalsIgnoreCase(AnswerPolicy.normalize(a))) return true;
        }
        return false;
    }

    private static String mutate(String s, Random r) {
        switch (r.nextInt(5)) {
            case 0: return s.toUpperCase();
            case 1: return " " + s + " ";
            case 2: return s.replace(" ", "");
            case 3: return s.toLowerCase();
            default: List<String> parts = new ArrayList<>(AnswerPolicy.legacyAnswerTextCandidates(s));
                return parts.isEmpty() ? s : parts.get(r.nextInt(parts.size()));
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
