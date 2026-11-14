package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-08 答案判定政策:显式别名 + LEGACY_V1,并且「没配政策 = 旧行为逐字不变」。
 *
 * <p>这里只钉判定与形状,不证明 controller / 冻结链;后者见
 * {@code ApiPlayProgressCompletionTruthTest}、{@code TopicPublicationContentSnapshotTest}
 * 与 {@code TopicRouteRuntimeServiceImplTest}。
 */
class AnswerPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------- LEGACY_V1(普通文字题 / guided / 共享) ----------

    @Test
    void ordinaryNormalizationStillDropsEveryBlankAndFoldsFullWidthAscii() {
        // 原实现:trim → 去 \s → 全角 ASCII→半角 → 忽略大小写。
        assertTrue(AnswerPolicy.legacy().matches("Ａ B", "ab"));
        assertTrue(AnswerPolicy.legacy().matches("答案", " 答 案 "));
        assertFalse(AnswerPolicy.legacy().matches("答案", "答 案 吗"));
        // 全角空格(U+3000)仍落成半角空格,且不参与去空白 —— 这是原实现的既有行为,
        // 不因为在别处看着像 bug 就悄悄放宽。
        assertEquals("A B", AnswerPolicy.normalize("A　B"));
        // 2026-11-14 起共享归一器(AnswerNormalizer)在旧口径之外多接受一层:「A　B」现在能命中「AB」(只放宽)。
        assertTrue(AnswerPolicy.legacy().matches("AB", "A　B"));
    }

    @Test
    void explicitAliasesMatchButNearMissesStayWrong() {
        AnswerPolicy policy = policy("{\"version\":1,\"normalizationPolicy\":\"LEGACY_V1\","
                + "\"acceptedAliases\":[\"正确\",\"Right\"]}");
        assertTrue(policy.matches("正确答案", "正确"));
        assertTrue(policy.matches("正确答案", " RIGHT "), "别名同样走 LEGACY_V1 归一");
        assertTrue(policy.matches("正确答案", "正确答案"), "canonical 永远保留");
        assertFalse(policy.matches("正确答案", "正确答"), "近似错字不得自动通过");
        assertTrue(policy.matches("正确答案", "正确。"), "显式别名也接受首尾标点归一");
        assertFalse(policy.matches("正确答案", "正确答。"), "去掉标点后错字仍不得通过");
        assertFalse(policy.matches("", "正确"), "canonical 为空 = 这题没有可判定的答案");
    }

    @Test
    void noAliasesMeansLegacyComparisonOnly() {
        AnswerPolicy none = AnswerPolicy.of(null);
        assertTrue(none.isEmpty());
        assertTrue(none.matches("答案", "答 案"));
        assertFalse(none.matches("答案", "另一个答案"));
        // 显式空数组 = 作者没写别名,与缺键等价。
        assertTrue(policy("{\"version\":1,\"acceptedAliases\":[]}").isEmpty());
    }

    // ---------- 高级 qa.TYPE 的旧比较器 ----------

    @Test
    void advancedTypeWithoutPolicyKeepsTrimAndCaseOnly() {
        assertTrue(AnswerPolicy.matchesLegacyAdvancedType("a、b", "A"));
        assertTrue(AnswerPolicy.matchesLegacyAdvancedType("a,b", " b "));
        // 旧实现只 trim,不删中间空白,也不折全角 —— 与普通文字题刻意不同。
        assertFalse(AnswerPolicy.matchesLegacyAdvancedType("a b", "ab"));
        assertFalse(AnswerPolicy.matchesLegacyAdvancedType("Ａ", "A"));
        assertFalse(AnswerPolicy.matchesLegacyAdvancedType("", "a"));
        assertFalse(AnswerPolicy.matchesLegacyAdvancedType("a", "  "));
    }

    @Test
    void advancedTypeWithPolicyUsesTheDeclaredLegacyV1Normalization() {
        AnswerPolicy policy = policy("{\"version\":1,\"normalizationPolicy\":\"LEGACY_V1\","
                + "\"acceptedAliases\":[\"芝麻\"]}");
        assertTrue(policy.matchesAny(AnswerPolicy.legacyAnswerTextCandidates("芝麻开门"), "芝麻"));
        assertTrue(policy.matchesAny(AnswerPolicy.legacyAnswerTextCandidates("芝麻开门、暗号"), "暗号"));
        // 作者显式配了政策,才接受 LEGACY_V1 的空白/全角归一;这是政策选择,不是静默改旧行为。
        assertTrue(policy.matchesAny(Collections.singletonList("Ａ B"), "ab"));
        assertFalse(policy.matchesAny(AnswerPolicy.legacyAnswerTextCandidates("芝麻开门"), "芝麻开门吧"));
    }

    @Test
    void legacyAnswerTextCandidatesKeepTheExistingSeparators() {
        assertEquals(Arrays.asList("甲", "乙", "丙"),
                AnswerPolicy.legacyAnswerTextCandidates(" 甲 、 乙 ,丙 "));
        assertTrue(AnswerPolicy.legacyAnswerTextCandidates("").isEmpty());
        assertTrue(AnswerPolicy.legacyAnswerTextCandidates(" 、 , ").isEmpty());
    }

    // ---------- 读配置:运行期宽容 ----------

    @Test
    void malformedPolicyFallsBackToNoAliasesInsteadOfBlockingPlayers() throws Exception {
        assertTrue(AnswerPolicy.of(JSON.readTree("{\"version\":2}")).isEmpty());
        assertTrue(AnswerPolicy.of(JSON.readTree("{\"version\":1,\"caseSensitive\":true}")).isEmpty());
        assertTrue(AnswerPolicy.of(JSON.readTree("\"nope\"")).isEmpty());
        assertTrue(AnswerPolicy.of(JSON.readTree("[]")).isEmpty());
        assertTrue(AnswerPolicy.of(JSON.readTree("{}")).isEmpty());
    }

    @Test
    void policyIsReadFromThePrivateAdvancedConfigRoot() {
        String raw = "{\"schemaVersion\":1,\"answerPolicy\":{\"version\":1,"
                + "\"normalizationPolicy\":\"LEGACY_V1\",\"acceptedAliases\":[\"暗号\"]}}";
        AnswerPolicy policy = AnswerPolicy.ofAdvancedConfig(raw);
        assertTrue(policy.matches("正确答案", "暗号"));
        assertTrue(AnswerPolicy.ofAdvancedConfig("{broken").isEmpty());
        assertTrue(AnswerPolicy.ofAdvancedConfig(null).isEmpty());
        assertTrue(AnswerPolicy.ofAdvancedConfig("{\"schemaVersion\":1}").isEmpty());
    }

    // ---------- 保存/发布闸:严格 ----------

    @Test
    void requireValidRejectsWhatTheEditorMustNotSave() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree("{\"version\":1,\"caseSensitive\":true}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree("{\"version\":2}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree(
                        "{\"version\":1,\"normalizationPolicy\":\"FUZZY\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree(
                        "{\"version\":1,\"acceptedAliases\":\"暗号\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree(
                        "{\"version\":1,\"acceptedAliases\":[\"\"]}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree(
                        "{\"version\":1,\"acceptedAliases\":[\"AB\",\"ａb\"]}")),
                "归一后重复 = 作者以为写了两条,实际只有一条");
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(JSON.readTree(
                        "{\"version\":1,\"acceptedAliases\":[\"" + "长".repeat(201) + "\"]}")));
        assertThrows(IllegalArgumentException.class,
                () -> AnswerPolicy.requireValid(manyAliases(AnswerPolicy.MAX_ALIASES + 1)));
    }

    @Test
    void requireValidAcceptsTheDocumentedShapeAndNullMeansNotConfigured() throws Exception {
        AnswerPolicy.requireValid(null);
        AnswerPolicy.requireValid(JSON.readTree("{\"version\":1,\"acceptedAliases\":[]}"));
        AnswerPolicy.requireValid(manyAliases(AnswerPolicy.MAX_ALIASES));
        AnswerPolicy policy = policy("{\"version\":1,\"normalizationPolicy\":\"LEGACY_V1\","
                + "\"acceptedAliases\":[\"暗号\"]}");
        ObjectNode frozen = policy.toJson();
        assertEquals(1, frozen.path("version").asInt());
        assertEquals("LEGACY_V1", frozen.path("normalizationPolicy").asText());
        assertEquals("暗号", frozen.path("acceptedAliases").path(0).asText());
    }

    private static AnswerPolicy policy(String json) {
        try {
            return AnswerPolicy.of(JSON.readTree(json));
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static ObjectNode manyAliases(int count) {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("version", 1);
        var aliases = policy.putArray("acceptedAliases");
        for (int i = 0; i < count; i++) aliases.add("别名" + i);
        return policy;
    }
}
