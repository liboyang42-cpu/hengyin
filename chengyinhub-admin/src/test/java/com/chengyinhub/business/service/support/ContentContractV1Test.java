package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C04 / BE-07 / QA-07 共同内容合同 v1 的 golden 消费端。
 *
 * <p>读 {@code /contracts/content-contract-v1-vectors.json}，把同一份期望交给真实实现：
 * {@link AdvancedGameConfigValidator}（保存/发布形状）、{@link AdvancedGamePublicProjection}（公开白名单）、
 * {@link PlayHelpUsagePolicy}/{@link PlayHelpRunQuotaPolicy}（整局免费额度与重看）、
 * {@link HangmanGameplay}（format 预填/旧局兼容）、
 * {@link AccusationTemplateSupport}/{@link AccusationOutcomePolicy}（指认结案）、
 * {@link ContentCapabilityRequirements}（端能力兼容结论）。
 *
 * <p>这是 helper 级合同测试，不是 E2E/HTTP 测试：不启动 Spring、不连库、不调用 controller。
 * 因此 adopt、DB 幂等重放、到达围栏的持久化事实仍是 NOT_RUN，见 fixtures 的 {@code limits} 与 REPORT。
 */
class ContentContractV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE = "/contracts/content-contract-v1-vectors.json";

    private static JsonNode vectors() throws Exception {
        try (InputStream in = ContentContractV1Test.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "缺少合同夹具 " + FIXTURE);
            return JSON.readTree(in);
        }
    }

    private static JsonNode group(JsonNode root, String name) {
        JsonNode group = root.path("vectors").path(name);
        assertTrue(group.isArray() && group.size() > 0, "夹具组为空:" + name);
        return group;
    }

    private static JsonNode configById(JsonNode root, String id) {
        for (JsonNode vector : group(root, "save")) {
            if (id.equals(vector.path("id").asText())) {
                return vector.path("config");
            }
        }
        throw new AssertionError("夹具里找不到 configRef:" + id);
    }

    // ---------------------------------------------------------------- save

    @Test
    void saveGateMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        for (JsonNode vector : group(root, "save")) {
            JsonNode config = vector.path("config");
            JsonNode expect = vector.path("expect");
            String id = vector.path("id").asText();
            if (expect.has("rejectContains")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(config.toString()), id);
                assertTrue(error.getMessage().contains(expect.path("rejectContains").asText()),
                        id + " 实际错误:" + error.getMessage());
                continue;
            }
            String normalized = validator.validateAndNormalize(config.toString());
            assertNotNull(normalized, id);
            JsonNode parsed = JSON.readTree(normalized);
            assertEquals(expect.path("mechanic").asBoolean(), validator.hasEnabledMechanic(config.toString()), id);
            assertEquals(expect.path("present").asText(),
                    AdvancedGameConfigValidator.resolvePresent(parsed), id);
            if (expect.has("ladderSize")) {
                assertEquals(expect.path("ladderSize").asInt(),
                        PlayHelpUsagePolicy.levels(parsed.get("hintLadder")).size(), id);
            }
            JsonNode quota = expect.get("quota");
            Integer actualQuota = PlayHelpRunQuotaPolicy.fromAdvancedConfig(parsed);
            if (quota == null || quota.isNull()) {
                assertNull(actualQuota, id);
            } else {
                assertEquals(quota.asInt(), actualQuota.intValue(), id);
            }
        }
    }

    // ---------------------------------------------------------------- public projection

    @Test
    void publicProjectionMatchesJsonVectorsAndHidesPrivateValues() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "publicProjection")) {
            JsonNode config = vector.has("configRef")
                    ? configById(root, vector.path("configRef").asText())
                    : vector.path("config");
            JsonNode expect = vector.path("expect");
            String id = vector.path("id").asText();

            String projected = AdvancedGamePublicProjection.safeConfigJson(config.toString());
            assertNotNull(projected, id);
            JsonNode safe = JSON.readTree(projected);
            assertEquals(expect.path("present").asText(), safe.path("present").asText(), id);
            for (JsonNode key : expect.path("rootAbsentKeys")) {
                assertFalse(safe.has(key.asText()), id + " 不应下发 " + key.asText());
            }
            if (expect.has("qaEnabled")) {
                assertEquals(expect.path("qaEnabled").asBoolean(),
                        safe.path("qa").path("enabled").asBoolean(!expect.path("qaEnabled").asBoolean()), id);
            }
            if (expect.has("hangman")) {
                JsonNode expectedHangman = expect.path("hangman");
                assertEquals(expectedHangman, safe.path("hangman"), id);
                for (JsonNode key : expect.path("hangmanAbsentKeys")) {
                    assertFalse(safe.path("hangman").has(key.asText()), id + " hangman 不应下发 " + key.asText());
                }
            }
            if (expect.has("accusation")) {
                JsonNode expected = expect.path("accusation");
                JsonNode view = safe.path("accusation");
                assertEquals(expected.path("enabled").asBoolean(), view.path("enabled").asBoolean(), id);
                assertEquals(expected.path("suspectCount").asInt(), view.path("suspects").size(), id);
                List<String> names = new ArrayList<>();
                for (JsonNode suspect : view.path("suspects")) {
                    names.add(suspect.path("name").asText());
                    assertEquals(0, suspect.path("testimony").size(), id + " 未获证词不得下发");
                }
                List<String> expectedNames = new ArrayList<>();
                for (JsonNode name : expected.path("suspectNames")) expectedNames.add(name.asText());
                assertEquals(expectedNames, names, id);
                assertEquals(expected.path("earlyAllowed").asBoolean(),
                        view.path("earlyAccuse").path("allowed").asBoolean(!expected.path("earlyAllowed").asBoolean()), id);
                assertEquals(expected.path("testimonyCount").asInt(), 0, id);
                for (JsonNode key : expect.path("accusationAbsentKeys")) {
                    assertFalse(view.has(key.asText()), id + " accusation 不应下发 " + key.asText());
                }
            }
            if (expect.has("qaOptionAbsentKeys")) {
                for (JsonNode option : safe.path("qa").path("options")) {
                    for (JsonNode key : expect.path("qaOptionAbsentKeys")) {
                        assertFalse(option.has(key.asText()), id + " qa option 不应下发 " + key.asText());
                    }
                }
            }
            for (JsonNode forbidden : expect.path("forbiddenSubstrings")) {
                assertFalse(projected.contains(forbidden.asText()),
                        id + " 公开投影泄漏私有值:" + forbidden.asText());
            }
        }
    }

    // ---------------------------------------------------------------- freeze / quota

    @Test
    void freezeAndRunQuotaMatchJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "freeze")) {
            JsonNode expect = vector.path("expect");
            assertEquals(expect.path("runFreeUses").asInt(),
                    PlayHelpRunQuotaPolicy.runFreeUses(vector.path("snapshot")).intValue(),
                    vector.path("id").asText());
            assertEquals(expect.path("frozenUses").asInt(),
                    PlayHelpRunQuotaPolicy.frozenUses(vector.path("frozenAnswer")).intValue(),
                    vector.path("id").asText());
        }
        for (JsonNode vector : group(root, "runQuota")) {
            String id = vector.path("id").asText();
            JsonNode expect = vector.path("expect");
            if (expect.has("rejectReason")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> PlayHelpRunQuotaPolicy.runFreeUses(vector.path("snapshot")), id);
                assertTrue(error.getMessage().startsWith(expect.path("rejectReason").asText()),
                        id + " 实际错误:" + error.getMessage());
                continue;
            }
            JsonNode value = expect.get("value");
            Integer actual = PlayHelpRunQuotaPolicy.runFreeUses(vector.path("snapshot"));
            if (value == null || value.isNull()) {
                assertNull(actual, id);
            } else {
                assertEquals(value.asInt(), actual.intValue(), id);
            }
        }
    }

    // ---------------------------------------------------------------- hint policy

    @Test
    void hintPolicyMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "hintPolicy")) {
            String id = vector.path("id").asText();
            Set<Integer> unlocked = new LinkedHashSet<>();
            for (JsonNode level : vector.path("unlockedLevels")) unlocked.add(level.asInt());
            List<PlayHelpUsagePolicy.LevelView> actual = PlayHelpUsagePolicy.views(
                    vector.path("ladder"), unlocked, vector.path("remainingFreeUses").asInt());
            JsonNode expected = vector.path("expect");
            assertEquals(expected.size(), actual.size(), id);
            for (int i = 0; i < expected.size(); i++) {
                JsonNode row = expected.get(i);
                PlayHelpUsagePolicy.LevelView view = actual.get(i);
                assertEquals(row.path("level").asInt(), view.level(), id + "#" + i);
                assertEquals(row.path("type").asText(), view.type(), id + "#" + i);
                assertEquals(row.path("usable").asBoolean(), view.usable(), id + "#" + i);
                assertEquals(row.path("unlocked").asBoolean(), view.unlocked(), id + "#" + i);
                assertEquals(row.path("free").asBoolean(), view.free(), id + "#" + i);
                assertEquals(row.path("costPoints").asInt(), view.costPoints(), id + "#" + i);
                assertEquals(row.path("freeQuota").asInt(), view.freeQuota(), id + "#" + i);
                assertEquals(row.path("reason").isNull() ? null : row.path("reason").asText(),
                        view.reason(), id + "#" + i);
            }
        }
    }

    // ---------------------------------------------------------------- request identity

    @Test
    void requestIdentityMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "requestIdempotency")) {
            String id = vector.path("id").asText();
            long memberId = vector.path("memberId").asLong();
            JsonNode scopeJson = vector.path("scope");
            PlayHelpUsagePolicy.Scope scope = new PlayHelpUsagePolicy.Scope(
                    scopeJson.path("topicId").asLong(),
                    scopeJson.has("activityId") && !scopeJson.path("activityId").isNull()
                            ? Long.valueOf(scopeJson.path("activityId").asLong()) : null,
                    scopeJson.path("nodeId").asLong(),
                    scopeJson.path("playInstance").asText());
            String scopeKey = PlayHelpUsagePolicy.scopeKey(scope);

            String request = vector.path("requestId").asText();
            String otherRequest = vector.path("otherRequestId").asText();
            String fact = PlayHelpUsagePolicy.requestFactKey(memberId, scopeKey, request);
            String otherFact = PlayHelpUsagePolicy.requestFactKey(memberId, scopeKey, otherRequest);
            String fingerprint = PlayHelpUsagePolicy.requestFingerprint(memberId, scopeKey,
                    vector.path("level").asInt(), vector.path("free").asBoolean(),
                    vector.path("chargedPoints").asInt());
            String otherFingerprint = PlayHelpUsagePolicy.requestFingerprint(memberId, scopeKey,
                    vector.path("otherLevel").asInt(), vector.path("otherFree").asBoolean(),
                    vector.path("otherChargedPoints").asInt());

            JsonNode expect = vector.path("expect");
            assertEquals(expect.path("sameRequestFactKeyIgnoresPayload").asBoolean(),
                    fact.equals(PlayHelpUsagePolicy.requestFactKey(memberId, scopeKey, request)), id);
            assertEquals(expect.path("sameRequestDifferentPayloadChangesFingerprint").asBoolean(),
                    !fingerprint.equals(otherFingerprint), id);
            assertEquals(expect.path("differentRequestChangesFactKey").asBoolean(),
                    !fact.equals(otherFact), id);
            assertEquals(expect.path("sameInputsStayStable").asBoolean(),
                    fingerprint.equals(PlayHelpUsagePolicy.requestFingerprint(memberId, scopeKey,
                            vector.path("level").asInt(), vector.path("free").asBoolean(),
                            vector.path("chargedPoints").asInt())), id);
        }
    }

    // ---------------------------------------------------------------- skip

    @Test
    void skipGateMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "skip")) {
            String id = vector.path("id").asText();
            boolean configured = false;
            boolean usable = false;
            for (PlayHelpUsagePolicy.LevelSpec spec : PlayHelpUsagePolicy.levels(vector.path("ladder"))) {
                if (PlayHelpUsagePolicy.TYPE_SKIP.equals(spec.type())) {
                    configured = true;
                    usable = spec.usable();
                }
            }
            assertEquals(vector.path("expect").path("skipConfigured").asBoolean(), configured, id);
            assertEquals(vector.path("expect").path("skipUsable").asBoolean(), usable, id);
        }
    }

    // ---------------------------------------------------------------- hangman

    @Test
    void hangmanMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "hangman")) {
            String id = vector.path("id").asText();
            JsonNode section = vector.path("section");
            JsonNode expect = vector.path("expect");
            if (expect.has("rejectContains")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> HangmanGameplay.validate(section), id);
                assertTrue(error.getMessage().contains(expect.path("rejectContains").asText()),
                        id + " 实际错误:" + error.getMessage());
                continue;
            }
            HangmanGameplay.validate(section);
            if (expect.has("safeConfig")) {
                assertEquals(expect.path("safeConfig"), HangmanGameplay.safeConfig(section), id);
            }
            if (expect.has("projection")) {
                JsonNode projection = HangmanGameplay.publicProjection(section, HangmanGameplay.initialState(section));
                JsonNode expected = expect.path("projection");
                assertEquals(expected.path("display").asText(), projection.path("display").asText(), id);
                assertEquals(expected.path("length").asInt(), projection.path("length").asInt(), id);
                assertEquals(expected.path("blankCount").asInt(), projection.path("blankCount").asInt(), id);
                assertEquals(expected.path("solved").asBoolean(), projection.path("solved").asBoolean(), id);
            }
            for (JsonNode submit : expect.path("submits")) {
                ObjectNode state = HangmanGameplay.initialState(section);
                JsonNode submission = submit.path("payload");
                JsonNode expected = submit.path("expect");
                if (expected.has("rejectContains")) {
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                            () -> HangmanGameplay.judge(section, state, submission), id);
                    assertTrue(error.getMessage().contains(expected.path("rejectContains").asText()),
                            id + " 实际错误:" + error.getMessage());
                    continue;
                }
                JsonNode view = HangmanGameplay.judge(section, state, submission);
                assertEquals(expected.path("verdict").asText(), view.path("verdict").asText(), id);
                if (expected.has("answerText")) {
                    assertEquals(expected.path("answerText").asText(), view.path("answerText").asText(), id);
                }
                if (expected.path("answerTextAbsent").asBoolean(false)) {
                    assertFalse(view.has("answerText"), id + " 不得下发答案文本");
                }
                if (expected.has("display")) {
                    assertEquals(expected.path("display").asText(), view.path("display").asText(), id);
                }
                assertEquals(expected.path("solved").asBoolean(), view.path("solved").asBoolean(), id);
                assertEquals(expected.path("alreadySolved").asBoolean(false),
                        view.path("alreadySolved").asBoolean(false), id);
            }
        }
    }

    // ---------------------------------------------------------------- accusation

    @Test
    void accusationMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "accusation")) {
            String id = vector.path("id").asText();
            JsonNode config = vector.path("config");
            JsonNode expect = vector.path("expect");
            if (expect.has("validateOk")) {
                AccusationTemplateSupport.validate(config);
            }
            assertEquals(expect.path("enabled").asBoolean(), AccusationTemplateSupport.isEnabled(config), id);
            if (!expect.path("enabled").asBoolean()) {
                assertEquals(expect.path("publicEnabled").asBoolean(),
                        AccusationTemplateSupport.publicView(config, null, null).path("enabled").asBoolean(true), id);
                assertNull(AccusationTemplateSupport.resultScreen(config, true), id);
                continue;
            }
            assertEquals(expect.path("correctSuspectId").asText(),
                    AccusationTemplateSupport.correctSuspectId(config.get("qa")), id);

            String publicJson = AccusationTemplateSupport.publicView(config, null, null).toString();
            JsonNode expectedPublic = expect.path("public");
            JsonNode view = JSON.readTree(publicJson);
            assertEquals(expectedPublic.path("enabled").asBoolean(), view.path("enabled").asBoolean(), id);
            assertEquals(expectedPublic.path("suspectCount").asInt(), view.path("suspects").size(), id);
            assertEquals(expectedPublic.path("testimonyCount").asInt(),
                    view.path("suspects").path(0).path("testimony").size(), id);
            assertEquals(expectedPublic.path("earlyAllowed").asBoolean(),
                    view.path("earlyAccuse").path("allowed").asBoolean(!expectedPublic.path("earlyAllowed").asBoolean()), id);
            for (JsonNode forbidden : expect.path("forbiddenSubstrings")) {
                assertFalse(publicJson.contains(forbidden.asText()),
                        id + " 公开投影泄漏:" + forbidden.asText());
            }

            String correctId = AccusationTemplateSupport.correctSuspectId(config.get("qa"));
            for (JsonNode row : expect.path("cases")) {
                String submitted = row.path("submitted").asText();
                JsonNode earlyJson = row.path("early");
                AccusationOutcomePolicy.EarlyAccuse early = new AccusationOutcomePolicy.EarlyAccuse(
                        earlyJson.path("allowed").asBoolean(false),
                        earlyJson.path("recordAs").isNull() || earlyJson.path("recordAs").isMissingNode()
                                ? null : earlyJson.path("recordAs").asText());
                AccusationOutcomePolicy.Verdict verdict = AccusationOutcomePolicy.judge(submitted, correctId,
                        early, row.path("clueGateSatisfied").asBoolean(), row.path("remainingChances").asInt());
                JsonNode expected = row.path("expect");
                String label = id + " submitted=" + submitted;
                assertEquals(expected.path("verdict").asText(), verdict.verdict(), label);
                assertEquals(expected.path("correct").asBoolean(), verdict.correct(), label);
                assertEquals(expected.path("early").asBoolean(), verdict.early(), label);
                assertEquals(expected.path("recordCode").isNull() ? null : expected.path("recordCode").asText(),
                        verdict.recordCode(), label);
                assertEquals(expected.path("canRetry").asBoolean(), verdict.canRetry(), label);
                assertEquals(expected.path("terminal").asBoolean(), verdict.terminal(), label);
                assertEquals(expected.path("endingScreen").isNull() ? null : expected.path("endingScreen").asText(),
                        verdict.endingScreen(), label);
                assertEquals(expected.path("refuseCode").isNull() ? null : expected.path("refuseCode").asText(),
                        verdict.refuseCode(), label);
            }
        }
    }

    // ---------------------------------------------------------------- capability

    @Test
    void capabilityCompatibilityMatchesJsonVectors() throws Exception {
        JsonNode root = vectors();
        for (JsonNode vector : group(root, "capability")) {
            String id = vector.path("id").asText();
            JsonNode snapshot = vector.path("snapshot");
            assertEquals(vector.path("required"), ContentCapabilityRequirements.required(snapshot),
                    id + " requiredCapabilities 必须与推导一致");
            for (JsonNode row : vector.path("cases")) {
                String label = id + " / " + row.path("id").asText();
                ContentCapabilityRequirements.Declaration declaration =
                        ContentCapabilityRequirements.Declaration.parse(
                                row.path("platform").asText(),
                                row.path("clientVersion").asText(),
                                row.path("supportedCapabilities").asText());
                JsonNode view = ContentCapabilityRequirements.compatibility(snapshot, declaration);
                JsonNode expected = row.path("expect");
                assertEquals(expected.path("declared").asBoolean(), view.path("declared").asBoolean(), label);
                JsonNode supported = expected.get("supported");
                if (supported == null || supported.isNull()) {
                    assertTrue(view.path("supported").isNull(), label + " 未确认兼容必须为 null");
                } else {
                    assertEquals(supported.asBoolean(), view.path("supported").asBoolean(), label);
                }
                assertEquals(expected.path("source").asText(), view.path("source").asText(), label);
                List<String> missingKeys = new ArrayList<>();
                for (JsonNode missing : view.path("missingCapabilities")) {
                    missingKeys.add(missing.path("key").asText());
                }
                List<String> expectedMissing = new ArrayList<>();
                for (JsonNode key : expected.path("missingKeys")) expectedMissing.add(key.asText());
                assertEquals(expectedMissing, missingKeys, label);
            }
        }
    }
    // ---------------------------------------------------------------- full section inventory

    @Test
    void everyDeclaredSectionHasAnAcceptedMinimalVector() throws Exception {
        JsonNode root = vectors();
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        Set<String> covered = new LinkedHashSet<>();
        int accepted = 0;
        for (JsonNode vector : group(root, "sections")) {
            String id = vector.path("id").asText();
            JsonNode expect = vector.path("expect");
            if (!expect.path("accepted").asBoolean(true)) continue;
            String normalized = validator.validateAndNormalize(vector.path("config").toString());
            assertNotNull(normalized, id);
            assertEquals(normalized, validator.validateAndNormalize(normalized), id + " 规范化必须幂等");
            String section = vector.path("section").asText();
            if (!"root".equals(section)) {
                assertTrue(JSON.readTree(normalized).has(section), id + " 规范化后必须保留段 " + section);
            }
            covered.add(section);
            accepted++;
        }
        assertTrue(accepted >= 45, "段级接受向量太少:" + accepted);
        JsonNode schema = canonicalSchema();
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode row : schema.path("x-cy-sections").path("sections")) declared.add(row.path("name").asText());
        Set<String> allowed = new LinkedHashSet<>(declared);
        for (JsonNode row : schema.path("x-cy-sections").path("root")) allowed.add(row.path("name").asText());
        allowed.add("root");
        assertTrue(declared.size() >= 40, "content-schema 段清单过短:" + declared.size());
        for (String section : declared) {
            assertTrue(covered.contains(section), "content-schema 声明的段没有可执行向量:" + section);
        }
        for (String section : covered) {
            assertTrue(allowed.contains(section), "向量段未在 content-schema 登记:" + section);
        }
    }

    @Test
    void rejectedSectionVectorsStillFail() throws Exception {
        JsonNode root = vectors();
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        int rejected = 0;
        for (JsonNode vector : group(root, "sections")) {
            JsonNode expect = vector.path("expect");
            if (expect.path("accepted").asBoolean(true)) continue;
            String id = vector.path("id").asText();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(vector.path("config").toString()), id);
            String needle = expect.path("rejectContains").asText();
            assertFalse(needle.isEmpty(), id + " 拒绝向量必须写明错误片段");
            assertTrue(error.getMessage().contains(needle), id + " 实际错误:" + error.getMessage());
            rejected++;
        }
        assertTrue(rejected >= 10, "拒绝向量太少:" + rejected);
    }

    /**
     * {@code clues} 的**结构声明**必须与 {@code validateClues} 的接受集合一致。
     *
     * <p>只锚定本工单触碰的这几个键（键命名空间、条数、值的类型/空白/长度），用真 validator 当判据：
     * 声明接受 ⇔ 服务器接受。它不宣称"结构 schema 能跑完整业务校验"——跨字段语义（如指认结案与
     * qa 选项的一一对应）仍然只有 validateAndNormalize 一处真源。
     */
    @Test
    void cluesDeclaredSchemaRejectsExactlyWhatTheServerRejects() throws Exception {
        JsonNode clues = canonicalSchema().path("properties").path("clues");
        assertEquals("object", clues.path("type").asText(), "clues 必须是对象段");
        assertFalse(clues.path("additionalProperties").asBoolean(true),
                "clues 必须拒绝未匹配的键，否则非法键能过结构声明");
        assertEquals(60, clues.path("maxProperties").asInt(), "条数上限必须与 validateClues 的 60 一致");
        JsonNode patternProperties = clues.path("patternProperties");
        assertEquals(1, patternProperties.size(), "clues 只认一个键命名空间");
        String keyRegex = patternProperties.fieldNames().next();
        JsonNode value = patternProperties.path(keyRegex);
        assertEquals("string", value.path("type").asText(), "线索名必须是字符串");
        assertEquals(1, value.path("minLength").asInt(), "空串必须被拒");
        assertEquals(20, value.path("maxLength").asInt(), "长度上限必须与 validateClues 的 20 一致");
        assertFalse(value.path("pattern").asText().isEmpty(), "必须声明非空白约束");
        assertFalse(clues.path("x-cy-known-divergence").asText().isEmpty(),
                "码点/UTF-16 计数差异必须显式登记，不能被悄悄忘掉");

        java.util.regex.Pattern keyPattern = java.util.regex.Pattern.compile(keyRegex);
        java.util.regex.Pattern nonBlank = java.util.regex.Pattern.compile(value.path("pattern").asText());
        assertFalse(keyPattern.matcher("clue.bad").matches(), "非法键在结构声明里必须不匹配");
        assertFalse(keyPattern.matcher("clue.h_0123456789AB").matches(), "大写十六进制必须不匹配");
        assertFalse(keyPattern.matcher("clue.h_0123456789abc").matches(), "13 位十六进制必须不匹配");
        assertTrue(nonBlank.matcher("钟摆").find(), "正常名称必须过非空白约束");

        // 探针：{键, 值} —— 声明判定与真 validator 判定必须一致（NBSP 只测到"都算非空"这一层）。
        String[][] probes = {
                {"clue.h_0123456789ab", "钟摆"},
                {"clue.h_0123456789ab", ""},
                {"clue.h_0123456789ab", "   "},
                {"clue.h_0123456789ab", "\t\n "},
                {"clue.h_0123456789ab", "\u00a0"},
                {"clue.h_0123456789ab", "钟摆 "},
                {"clue.h_0123456789ab", "一二三四五六七八九十一二三四五六七八九十"},
                {"clue.h_0123456789ab", "一二三四五六七八九十一二三四五六七八九十一"},
                {"clue.h_0123456789AB", "钟摆"},
                {"clue.h_0123456789abc", "钟摆"},
                {"clue.bad", "钟摆"},
                {"Clue.h_0123456789ab", "钟摆"},
                {"clue.h_0123456789ab ", "钟摆"},
        };
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        for (String[] probe : probes) {
            String label = probe[0] + " → \"" + probe[1] + "\"";
            boolean declared = keyPattern.matcher(probe[0]).matches()
                    && probe[1].codePointCount(0, probe[1].length()) >= 1
                    && probe[1].codePointCount(0, probe[1].length()) <= 20
                    && nonBlank.matcher(probe[1]).find();
            ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1);
            config.putObject("clues").put(probe[0], probe[1]);
            boolean server = true;
            try {
                validator.validateAndNormalize(config.toString());
            } catch (IllegalArgumentException rejected) {
                server = false;
            }
            assertEquals(server, declared, "结构声明与保存闸不一致:" + label);
        }

        // 条数边界：60 条接受、61 条拒绝，声明与服务器同一条线。
        ObjectNode sixty = JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode atLimit = sixty.putObject("clues");
        ObjectNode overLimit = JSON.createObjectNode().put("schemaVersion", 1).putObject("clues");
        for (int i = 0; i < 61; i++) {
            String key = String.format("clue.h_%012x", i);
            if (i < 60) atLimit.put(key, "名");
            overLimit.put(key, "名");
        }
        assertEquals(60, atLimit.size());
        assertEquals(61, overLimit.size());
        assertNotNull(validator.validateAndNormalize(sixty.toString()), "60 条线索必须接受");
        assertEquals(clues.path("maxProperties").asInt(), atLimit.size(), "声明的上限必须正好是服务器接受的上限");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(overLimit.toString()), "61 条线索必须被拒");
    }

    /**
     * 规范 schema 的确定性来源。
     *
     * <p>两条来源都必须给出**同一份字节**：测试资源里的副本（模块单独分发/打包也能跑到）与
     * {@code docs/contracts/} 下的仓库声明（root 的源码导出）。找得到 docs 就逐字节比对，
     * 找不到也不静默降级——资源副本本身就是完整 schema，所以断言永远覆盖全量；两者都缺才失败。
     *
     * <p>负控：删掉资源里的 {@code contracts/content-schema.v1.json} 会立刻红；改 docs 不改资源
     * （或反之）也会立刻红——这正是防漂移的那一步。
     */
    private static JsonNode canonicalSchema() throws Exception {
        byte[] canonical = resourceBytes("/contracts/content-schema.v1.json");
        assertNotNull(canonical, "缺少规范 schema 资源 /contracts/content-schema.v1.json");
        Path declared = docsSchemaPath();
        if (declared != null) {
            assertArrayEquals(canonical, Files.readAllBytes(declared),
                    "docs 声明与测试资源里的 schema 必须逐字节一致:" + declared);
        }
        return JSON.readTree(canonical);
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = ContentContractV1Test.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** 从当前工作目录逐级向上找仓库声明；找不到返回 null（由资源副本兜底，但绝不跳过断言）。 */
    private static Path docsSchemaPath() {
        Path relative = Path.of("docs", "contracts", "content-schema.v1.json");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
