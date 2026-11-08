package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.support.AccusationOutcomePolicy;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.GameplaySupportMatrix;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.business.service.support.TemplateAdoptionService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T04／T06／T07／T08 结构范本的源码级验收。
 *
 * <p>范本只装配既有能力（sort／match／classify／qaPick／branch／timer 的判定、建议、物品与计时），
 * 不新增题型、判题器、模板库或导入 API。玩家正文、现场事实、答案与反馈按 CT-01「待现场核验」
 * 留作待填槽，所以这里证明的是**配置合同**：过真实 validator、过发布闸、经真实采用流程产出
 * 独立草稿、冻结后不漂移。它不表示四套剧本已写完，也不表示城市照片／地点／现场试玩已核验。
 *
 * <p>不连库、不起服务：Mapper 只作记录，判题／冻结／采用都走生产实现。
 */
class ThemePresetExtendedContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 四包各自的结构范本；节点 id 是待重绑占位，见各 preset.json 的 nodeIdPlaceholders。 */
    private static final String[][] PRESETS = {
            {"T04-era-trace", "900101", "900102", "900103"},
            {"T06-building-observation", "900201", "900202", "900203"},
            {"T07-original-fantasy", "900301", "900302", "900303"},
            {"T08-multi-clue-investigation", "900401", "900402", "900403"},
    };
    private static final long AUTHOR = 41L;

    // ---------------------------------------------------------------- 1 结构与能力声明

    @Test
    void everyPresetNodeConfigPassesTheRealValidatorAndOnlyDeclaresSupportedCapabilities() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            JsonNode meta = JSON.readTree(read(code + "/preset.json"));
            assertEquals(code, meta.path("presetCode").asText());
            assertEquals(1, meta.path("schemaVersion").asInt());
            // 当前状态只能是映射就绪：不写剧情、不自称成品、不把规格当已制作资产。
            assertEquals("MAPPING_READY", meta.path("contentStatus").asText(),
                    code + " 只能登记为 MAPPING_READY，不得自称内容已写完");
            assertEquals("MAPPING_READY_NOT_A_PRODUCT", meta.path("productStatus").asText(),
                    code + " 必须显式声明不是可发布成品");
            assertEquals("SPEC_ONLY", meta.path("mediaStatus").asText(), code + " 媒体只是规格，不是已制作资产");
            assertFalse(meta.path("chaptersForced").asBoolean(true), code + " 不为了凑数强制章数");
            for (String notVerified : List.of("城市照片", "地点核验", "现场试玩")) {
                boolean declared = false;
                for (JsonNode item : meta.path("notVerified")) declared |= notVerified.equals(item.asText());
                assertTrue(declared, code + " 必须如实标注未核验项：" + notVerified);
            }

            Set<String> declared = new LinkedHashSet<>();
            for (JsonNode key : meta.path("capabilities")) declared.add(key.asText());
            assertFalse(declared.isEmpty(), code + " 必须声明复用的能力 key");
            for (String key : declared) {
                // 呈现/帮助段(CT-07 accusation、BE-16 hintLadder、有限谜面变换)按设计不进 32 型能力表:
                // 它们挂在真题型上、自己不判题、不产生完成证明。矩阵没有它们的行是对的,
                // 所以这里不能按「题型行」要求 matrixRow,否则会逼着往作者端可见目录里加假类型。
                if (GameplaySupportMatrix.presentation(key)) {
                    assertNull(matrixRow(key), code + " 呈现段 " + key + " 不能占题型目录行");
                    continue;
                }
                JsonNode row = matrixRow(key);
                assertNotNull(row, code + " 声明了支持矩阵里没有的能力：" + key);
                // branch／random 是附加项（不能独立产生完成证明），仍须在矩阵里登记。
                assertTrue(row.path("completionProof").asBoolean() || GameplaySupportMatrix.addon(key)
                                || "PRESENTATION".equals(row.path("category").asText()),
                        code + " 能力 " + key + " 既不是完成证明也不是已登记的附加项");
            }

            JsonNode nodeFiles = meta.path("nodes");
            assertFalse(nodeFiles.isEmpty(), code + " 映射样本必须给出可校验的节点配置");
            Set<String> usedSections = new LinkedHashSet<>();
            for (JsonNode nodeFile : nodeFiles) {
                String text = read(code + "/" + nodeFile.asText());
                AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
                String normalized = validator.validateAndNormalize(text);
                assertNotNull(normalized, code + " " + nodeFile.asText() + " 必须可归一化");
                assertTrue(validator.hasEnabledMechanic(text), code + " " + nodeFile.asText() + " 必须带启用的玩法段");
                JsonNode parsed = JSON.readTree(normalized);
                parsed.fieldNames().forEachRemaining(section -> {
                    if (parsed.get(section).path("enabled").asBoolean(false)) usedSections.add(section);
                });
            }
            Set<String> usedKeys = new LinkedHashSet<>();
            for (String section : usedSections) usedKeys.add(capabilityKey(section));
            assertEquals(declared, usedKeys, code + " 声明能力必须与实际启用段一致（不多报也不漏报）");
            // 呈现段只能挂在真题型上:整包不声明任何真实完成证明,就只是「结局画面 + 无判定」,
            // 玩家走完拿不到可结算的完成事实。这里单独钉住,别让呈现段把真实题型挤掉。
            boolean hasRealProof = false;
            for (String key : usedKeys) {
                if (GameplaySupportMatrix.presentation(key)) continue;
                JsonNode row = matrixRow(key);
                hasRealProof |= row != null && row.path("completionProof").asBoolean();
            }
            assertTrue(hasRealProof, code + " 必须至少声明一个能产生完成证明的真实题型，呈现段不能替代它");

            List<String> stateKeys = splitCsv(meta.path("requiredStateKeys"));
            assertFalse(stateKeys.isEmpty(), code + " 必须声明范本依赖的状态键");
            String allNodes = textOfAllNodes(code, nodeFiles);
            for (String required : stateKeys) {
                assertTrue(required.matches("(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}"),
                        code + " 状态键不是既有具名键形状：" + required);
                assertTrue(allNodes.contains("\"" + required + "\""),
                        code + " 声明的状态键 " + required + " 未被任何节点引用");
            }
        }
    }

    // ---------------------------------------------------------------- 2 发布闸

    @Test
    void presetNodeConfigsPassThePublicationGateAndKeepFreeRetryUnchanged() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            JsonNode meta = JSON.readTree(read(code + "/preset.json"));
            ObjectNode theme = themeSkeleton(preset);
            int index = 0;
            for (JsonNode nodeFile : meta.path("nodes")) {
                long nodeId = Long.parseLong(preset[++index]);
                String text = read(code + "/" + nodeFile.asText());
                JsonNode normalized = JSON.readTree(new AdvancedGameConfigValidator().validateAndNormalize(text));
                GameplaySupportMatrix.validateNode(theme, nodeId, nodeFile.asText(), normalized, 3);
            }

            // 既有免费重答围栏不得被新范本绕过：有限次数仍由原规则拒绝（取本范本自带的那道问答）。
            JsonNode nodeFiles = meta.path("nodes");
            ObjectNode chargedQa = null;
            long chargedNode = 0L;
            for (int i = 0; i < nodeFiles.size(); i++) {
                ObjectNode candidate = (ObjectNode) JSON.readTree(read(code + "/" + nodeFiles.get(i).asText()));
                if (!candidate.path("qa").path("enabled").asBoolean(false)) continue;
                chargedQa = candidate;
                chargedNode = Long.parseLong(preset[i + 1]);
                break;
            }
            assertNotNull(chargedQa, code + " 范本必须至少有一道问答用于校验免费重答围栏");
            ((ObjectNode) chargedQa.path("qa")).put("maxTries", 1);
            final ObjectNode charged = chargedQa;
            final long chargedId = chargedNode;
            IllegalArgumentException qaRejected = assertThrows(IllegalArgumentException.class,
                    () -> GameplaySupportMatrix.validateNode(theme, chargedId, "charged-qa", charged, 3));
            assertTrue(qaRejected.getMessage().contains("免费重答"), qaRejected.getMessage());
        }

        // 排序同样不许悄悄改成有限次数；用 T04 自己的主题骨架验一次即可。
        ObjectNode sortTheme = themeSkeleton(PRESETS[0]);
        ObjectNode chargedSort = (ObjectNode) JSON.readTree(read(PRESETS[0][0] + "/nodes/node-01.json"));
        ((ObjectNode) chargedSort.path("sort")).put("maxAttempts", 1);
        long sortNode = Long.parseLong(PRESETS[0][1]);
        IllegalArgumentException sortRejected = assertThrows(IllegalArgumentException.class,
                () -> GameplaySupportMatrix.validateNode(sortTheme, sortNode, "charged-sort", chargedSort, 3));
        assertTrue(sortRejected.getMessage().contains("免费重答"), sortRejected.getMessage());
    }

    // ---------------------------------------------------------------- 3 模板冻结与地点绑定

    @Test
    void frozenTemplateKeepsOriginalContentAndRefusesMissingPlaceBinding() throws Exception {
        String code = PRESETS[0][0];
        String[] preset = PRESETS[0];
        ObjectNode theme = themeSkeleton(preset);
        // 主题侧已冻结节点配置的容器：merge 会把解析后的副本写回这里。
        theme.putObject("nodeConfigs");
        ObjectNode nodeConfigs = JSON.createObjectNode();
        for (int i = 0; i < 3; i++) {
            nodeConfigs.set(preset[i + 1], JSON.readTree(read(code + String.format("/nodes/node-%02d.json", i + 1))));
        }
        Map<Long, String> acts = actKeys(preset);

        ObjectNode frozen = GameplayTemplateRules.merge(theme.deepCopy(), nodeConfigs, acts, Map.of());
        JsonNode facilities = frozen.path("gameplayRuntime").path("facilities");
        assertEquals(3, facilities.size(), "三站各生成一个模板设施");
        for (JsonNode facility : facilities) {
            assertEquals("tpl_node_" + facility.path("operationNodeId").asLong(),
                    facility.path("facilityKey").asText());
        }
        // requireArrival 的来源是本节点真实到达地点；有绑定才落 arrivalLocationKey。
        assertEquals("slot_900101", facilityOf(frozen, 900101L).path("arrivalLocationKey").asText());
        assertFalse(facilityOf(frozen, 900103L).has("arrivalLocationKey"), "未要求到达的站不得凭空生成到达地点");

        // 作者改源配置之后，已冻结副本仍是原内容。
        String frozenBefore = frozen.path("nodeConfigs").path("900101").toString();
        assertFalse(frozenBefore.isEmpty(), "冻结副本必须包含节点配置");
        ObjectNode source = (ObjectNode) nodeConfigs.get("900101");
        ((ObjectNode) source.path("sort")).set("answerOrder",
                JSON.createArrayNode().add("ev_now_01").add("ev_archive_02").add("ev_archive_01"));
        ((ObjectNode) source.path("sort").path("items").get(0)).put("label", "作者后改的排序项");
        assertEquals(frozenBefore, frozen.path("nodeConfigs").path("900101").toString(),
                "merge 必须深拷贝：改源数据不能改动已冻结副本");

        // 关键绑定缺失：既有闸拒发布（允许存稿，但不允许冒充可发布）。
        ObjectNode unbound = themeSkeleton(preset);
        for (JsonNode quest : unbound.path("gameplayRuntime").path("quests")) {
            if (quest.path("nodeId").asLong() == 900101L) ((ObjectNode) quest).remove("locationKey");
        }
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GameplayTemplateRules.merge(unbound, nodeConfigs, acts, Map.of()));
        assertTrue(refused.getMessage().contains("到达地点"), refused.getMessage());
    }

    // ---------------------------------------------------------------- 4 冻结判题

    @Test
    void frozenObjectiveJudgeKeepsOriginalAnswersAfterTheAuthorEditsTheSource() throws Exception {
        JsonNode archiveSort = JSON.readTree(read("T04-era-trace/nodes/node-01.json"));
        JsonNode correctOrder = sortSubmission(archiveSort.path("sort").path("answerOrder"));
        assertTrue(AdvancedActionJudgement.verifyObjectiveAnswers(archiveSort, correctOrder), "原排序答案应通过");

        ObjectNode editedSort = archiveSort.deepCopy();
        ((ObjectNode) editedSort.path("sort")).set("answerOrder",
                JSON.createArrayNode().add("ev_now_01").add("ev_archive_02").add("ev_archive_01"));
        assertFalse(AdvancedActionJudgement.verifyObjectiveAnswers(editedSort, correctOrder),
                "作者改答案之后，改后的源不再接受旧答案");
        assertFalse(AdvancedActionJudgement.verifyObjectiveAnswers(archiveSort,
                        sortSubmission(JSON.createArrayNode().add("ev_now_01").add("ev_archive_02").add("ev_archive_01"))),
                "错误顺序必须被拒绝");

        // 选项题（T04 现状确认站）：判定交服务端，客户端自报通过一律被拒。
        JsonNode nowCheck = JSON.readTree(read("T04-era-trace/nodes/node-02.json"));
        assertTrue(AdvancedActionJudgement.verifyObjectiveAnswers(nowCheck, pick("opt_changed")));
        assertFalse(AdvancedActionJudgement.verifyObjectiveAnswers(nowCheck, pick("opt_unchanged")));
        IllegalArgumentException forged = assertThrows(IllegalArgumentException.class,
                () -> AdvancedActionJudgement.verifyObjectiveAnswers(nowCheck,
                        JSON.createObjectNode().set("qa",
                                JSON.createObjectNode().put("optionId", "opt_changed").put("passed", true))));
        assertTrue(forged.getMessage().contains("通过标记"), forged.getMessage());
    }

    // ---------------------------------------------------------------- 4b 指认结案（CT-07 配置映射）

    @Test
    void accusationBlockMirrorsTheFinalPickAndCarriesBothEndingTexts() throws Exception {
        JsonNode node = JSON.readTree(read("T08-multi-clue-investigation/nodes/node-03.json"));
        JsonNode qa = node.path("qa");
        JsonNode accusation = node.path("accusation");
        assertEquals("PICK", qa.path("mode").asText(), "最终一题复用既有图片单选");
        assertTrue(accusation.path("enabled").asBoolean(), "结案段须启用，否则就是普通结局");

        // 嫌疑人与选项一一对应，且姓名只有一份真源（qa.options[].label）。
        Set<String> optionIds = new LinkedHashSet<>();
        String correctOption = null;
        for (JsonNode option : qa.path("options")) {
            optionIds.add(option.path("id").asText());
            if (option.path("correct").asBoolean(false)) correctOption = option.path("id").asText();
            assertFalse(option.path("img").asText("").isEmpty(), "图片指认需要选项配图");
            assertFalse(option.path("alt").asText("").isEmpty(), "配图必须有替代文字");
        }
        Set<String> suspectIds = new LinkedHashSet<>();
        for (JsonNode suspect : accusation.path("suspects")) {
            String id = suspect.path("id").asText();
            assertTrue(suspectIds.add(id), "嫌疑人 ID 不能重复");
            String label = null;
            for (JsonNode option : qa.path("options")) {
                if (id.equals(option.path("id").asText())) label = option.path("label").asText();
            }
            assertEquals(label, suspect.path("name").asText(), "人物姓名必须与选项文案一致");
            assertFalse(suspect.path("avatar").asText("").isEmpty(), "人物卡必须有头像");
        }
        assertEquals(optionIds, suspectIds, "问答的每个选项都要有人物档案");
        assertNotNull(correctOption, "指认必须有且只有一个正确选项（真源是原 QA）");

        assertFalse(accusation.path("winScreen").asText("").isEmpty(), "正确指认的结局文案不能为空");
        assertFalse(accusation.path("loseScreen").asText("").isEmpty(), "错误指认的结局文案不能为空");
        // 允许提前指认就必须由作者写明留痕内容；后端原样透传，不设默认码、不限定枚举。
        JsonNode early = accusation.path("earlyAccuse");
        assertTrue(early.path("allowed").isBoolean(), "提前指认 allowed 须为开关");
        if (early.path("allowed").asBoolean()) {
            assertFalse(early.path("recordAs").asText("").trim().isEmpty(), "允许提前指认时必须写明结束态记录");
        }
        // 正确嫌疑人不在 accusation 里另存一份，避免第二真源。
        assertFalse(accusation.has("correctSuspectId"), "正确嫌疑人只能由原 QA 选项派生");

        // 服务端结论:正确项只能来自原 QA;提前指认与对错是两个正交事实。
        AccusationOutcomePolicy.EarlyAccuse earlyAccuse =
                AccusationOutcomePolicy.earlyAccuse(node);
        assertEquals("待填：提前结案留痕说明（作者自写）", earlyAccuse.recordAs(), "作者写的留痕原样透传");
        AccusationOutcomePolicy.Verdict normal =
                AccusationOutcomePolicy.judge(correctOption, correctOption, earlyAccuse, true, 1);
        assertEquals(AccusationOutcomePolicy.VERDICT_CORRECT, normal.verdict());
        assertFalse(normal.early(), "证据门槛已满足时不记提前结案");
        assertEquals("winScreen", normal.endingScreen());

        assertEquals(AccusationOutcomePolicy.REFUSED_EARLY_NOT_ALLOWED,
                AccusationOutcomePolicy.judge(correctOption, correctOption, earlyAccuse, false, 1).refuseCode());
        AccusationOutcomePolicy.EarlyAccuse allowedEarly =
                new AccusationOutcomePolicy.EarlyAccuse(true, earlyAccuse.recordAs());
        AccusationOutcomePolicy.Verdict earlyHit =
                AccusationOutcomePolicy.judge(correctOption, correctOption, allowedEarly, false, 1);
        assertEquals(AccusationOutcomePolicy.VERDICT_CORRECT, earlyHit.verdict(), "提前指认成立不是失败");
        assertTrue(earlyHit.early(), "门禁未满足时 early 为真");
        assertEquals(earlyAccuse.recordAs(), earlyHit.recordCode(), "提前结案的留痕就是作者写的那句");

        String wrongOption = null;
        for (JsonNode option : qa.path("options")) {
            if (!option.path("correct").asBoolean(false)) { wrongOption = option.path("id").asText(); break; }
        }
        AccusationOutcomePolicy.Verdict wrong =
                AccusationOutcomePolicy.judge(wrongOption, correctOption, earlyAccuse, true, 0);
        assertEquals(AccusationOutcomePolicy.VERDICT_FAILED, wrong.verdict());
        assertFalse(wrong.correct());
        assertEquals("loseScreen", wrong.endingScreen(), "错误指认走独立结局文案");

        // 答案来源不可用(没有原 QA 正确项)时直接拒绝,不猜、不默认。
        assertEquals(AccusationOutcomePolicy.REFUSED_CORRECT_UNKNOWN,
                AccusationOutcomePolicy.judge(wrongOption, null, earlyAccuse, true, 1).refuseCode());
    }

    // ---------------------------------------------------------------- 5 跨城绑定合同

    @Test
    void twoCityInstancesShareOneStructureButExchangeNoBoundFacts() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            JsonNode cityA = JSON.readTree(read(code + "/bindings/city-a.json"));
            JsonNode cityB = JSON.readTree(read(code + "/bindings/city-b.json"));
            assertEquals("city-a", cityA.path("cityInstance").asText());
            assertEquals("city-b", cityB.path("cityInstance").asText());

            Set<String> slotsA = slotKeys(cityA), slotsB = slotKeys(cityB);
            assertFalse(slotsA.isEmpty(), code + " 绑定合同必须有槽位");
            assertEquals(slotsA, slotsB, code + " 两城必须复用同一套槽位结构");

            for (JsonNode binding : List.of(cityA, cityB)) {
                assertFalse(binding.path("bound").asBoolean(), code + " 未核验城市不得标成已绑定");
                for (JsonNode slot : binding.path("slots")) {
                    if (slot.path("required").asBoolean()) {
                        assertTrue(slot.path("value").isNull(),
                                code + " " + slot.path("key").asText() + " 在未核验城市里不能有值");
                    }
                }
                for (String rebind : List.of("locationKey", "observedFact", "answerValues", "photoRefs", "nodeIds")) {
                    boolean declared = false;
                    for (JsonNode item : binding.path("rebindRequired")) declared |= rebind.equals(item.asText());
                    assertTrue(declared, code + " 换城必须声明要重绑：" + rebind);
                }
                for (int i = 1; i < preset.length; i++) {
                    String node = preset[i];
                    assertTrue(binding.path("nodeIdMap").has(node), code + " 节点 " + node + " 必须有重绑槽");
                    assertTrue(binding.path("nodeIdMap").path(node).isNull(), "占位节点 id 未重绑前不能有实值");
                }
            }
        }
    }

    // ---------------------------------------------------------------- 6 既有采用流程消费原稿

    @Test
    void presetIsAdoptableThroughTheExistingAdoptionFunnelWithoutANewImportApi() throws Exception {
        String code = "T08-multi-clue-investigation";
        JsonNode meta = JSON.readTree(read(code + "/preset.json"));
        long canonicalTemplateId = 5_001L, draftForA = 6_001L, draftForB = 6_002L;
        String firstNodeConfig = new AdvancedGameConfigValidator()
                .validateAndNormalize(read(code + "/nodes/node-01.json"));

        CmsMemberTemplate canonical = new CmsMemberTemplate();
        canonical.setId(canonicalTemplateId);
        canonical.setMemberId(AUTHOR);
        canonical.setTitle(meta.path("title").asText());
        canonical.setValidationMethod(3);
        canonical.setAdvancedConfigJson(firstNodeConfig);

        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(canonicalTemplateId);
        source.setMemberTemplateId(canonicalTemplateId);
        source.setTitle(meta.path("title").asText());
        source.setQuestionAnswer("待填：作者答案");
        source.setStatus(1L);
        source.setPublishStatus(1L);
        source.setDelFlag(0);
        source.setDuration(600L);

        Map<Long, CmsMemberTemplate> draftsById = new LinkedHashMap<>();
        draftsById.put(canonicalTemplateId, canonical);
        CmsMemberTemplate draftA = new CmsMemberTemplate();
        draftA.setId(draftForA); draftA.setMemberId(90L); draftA.setTitle(source.getTitle());
        draftA.setAdvancedConfigJson(firstNodeConfig);
        CmsMemberTemplate draftB = new CmsMemberTemplate();
        draftB.setId(draftForB); draftB.setMemberId(91L); draftB.setTitle(source.getTitle());
        draftB.setAdvancedConfigJson(firstNodeConfig);
        draftsById.put(draftForA, draftA);
        draftsById.put(draftForB, draftB);

        TemplateAdoptionReceiptMapper mapper = mock(TemplateAdoptionReceiptMapper.class);
        ITemplatePublishService publisher = mock(ITemplatePublishService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        Map<String, Map<String, Object>> rows = new HashMap<>();
        when(mapper.reserve(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> facts = invocation.getArgument(0);
            // 生产 Mapper 的 reserve 落一行 PENDING；这里同形状记录，好让真正状态机跑完整路径。
            rows.computeIfAbsent(facts.get("actor") + ":" + facts.get("requestId"), key -> {
                Map<String, Object> row = new HashMap<>(facts);
                row.put("status", "PENDING");
                return row;
            });
            return 1;
        });
        when(mapper.lock(anyLong(), anyString())).thenAnswer(invocation -> new HashMap<>(
                rows.getOrDefault(invocation.getArgument(0) + ":" + invocation.getArgument(1), Map.of())));
        when(mapper.sourceCanonicalId(anyLong())).thenReturn(canonicalTemplateId);
        when(mapper.lockSource(anyLong())).thenReturn(source);
        when(mapper.lockDraft(anyLong())).thenAnswer(invocation -> draftsById.get(invocation.getArgument(0)));
        when(mapper.finish(anyLong(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> row = rows.get(invocation.getArgument(0) + ":" + invocation.getArgument(1));
            if (row == null) return 0;
            row.put("status", invocation.getArgument(2));
            if (invocation.getArgument(3) != null) row.put("draftId", invocation.getArgument(3));
            row.put("reason", invocation.getArgument(4));
            row.put("draftVersion", invocation.getArgument(5));
            return 1;
        });
        when(publisher.saveOrUpdateDraft(any(), eq(90L), anyString())).thenReturn(draftForA);
        when(publisher.saveOrUpdateDraft(any(), eq(91L), anyString())).thenReturn(draftForB);

        TemplateAdoptionService service = new TemplateAdoptionService(mapper, publisher, manager);
        String sourceVersion = String.valueOf(service.source(42L, 90L, String.valueOf(canonicalTemplateId))
                .get("sourceVersion"));
        assertTrue(sourceVersion.matches("[a-f0-9]{64}"), "来源版本由既有服务按作者字段计算");

        Map<String, Object> adoptedA = service.adopt(42L, 90L,
                command("adopt_city_a_request", canonicalTemplateId, sourceVersion), "author");
        assertEquals("COMPLETED", adoptedA.get("status"), adoptedA.toString());
        // 返回的是采用产生的新草稿 ID，不是来源模板 ID：这就是作者随后编辑的那份内容。
        assertEquals(String.valueOf(draftForA), adoptedA.get("draftId"));
        assertNotEquals(String.valueOf(canonicalTemplateId), adoptedA.get("draftId"));
        assertEquals(String.valueOf(canonicalTemplateId), String.valueOf(adoptedA.get("originalTemplateId")));

        // 同一请求号重试只回原草稿，不再新建。
        Map<String, Object> replay = service.adopt(42L, 90L,
                command("adopt_city_a_request", canonicalTemplateId, sourceVersion), "author");
        assertEquals(adoptedA.get("draftId"), replay.get("draftId"));
        verify(publisher, times(1)).saveOrUpdateDraft(any(), eq(90L), anyString());

        // 第二座城市是它自己的草稿：结构同源，实例独立，返回各自不同的 ID。
        Map<String, Object> adoptedB = service.adopt(43L, 91L,
                command("adopt_city_b_request", canonicalTemplateId, sourceVersion), "author");
        assertEquals("COMPLETED", adoptedB.get("status"), adoptedB.toString());
        assertEquals(String.valueOf(draftForB), adoptedB.get("draftId"));
        assertNotEquals(adoptedA.get("draftId"), adoptedB.get("draftId"));
        assertEquals("待填：作者答案", source.getQuestionAnswer(), "采用不改写来源内容");

        // 作者改自己的采用草稿（编辑/保存读回的起点）不回流到来源模板。
        String canonicalBefore = canonical.getAdvancedConfigJson();
        String cityBEdited = firstNodeConfig.replace("待填：这处调查取得哪一条线索？",
                "待填：乙城本地核对后的问题");
        assertNotEquals(firstNodeConfig, cityBEdited, "编辑必须真的改变了采用实例内容");
        draftB.setAdvancedConfigJson(cityBEdited);
        assertEquals(cityBEdited, draftsById.get(draftForB).getAdvancedConfigJson(), "采用实例保存后读回自己的内容");
        assertEquals(canonicalBefore, canonical.getAdvancedConfigJson(), "改采用实例不得改动原模板");
        assertEquals(firstNodeConfig, draftA.getAdvancedConfigJson(), "同源的另一实例也不受 B 的编辑影响");
        // 采用身份不能被换人复用。
        assertThrows(ServiceException.class, () -> service.adopt(42L, 92L,
                command("adopt_city_a_request", canonicalTemplateId, sourceVersion), "author"));

        // 来源在采用前被改过：旧版本号被拒，不静默换内容。
        source.setTitle("作者后改的范本标题");
        Map<String, Object> drift = service.adopt(44L, 93L,
                command("adopt_drift_request", canonicalTemplateId, sourceVersion), "author");
        assertEquals("REJECTED", drift.get("status"), drift.toString());
        assertEquals("SOURCE_VERSION_CONFLICT", drift.get("reason"));
    }

    // ---------------------------------------------------------------- 辅助

    private static TemplateAdoptionCommand command(String requestId, long sourceId, String sourceVersion) {
        TemplateAdoptionCommand command = new TemplateAdoptionCommand();
        command.setRequestId(requestId);
        command.setOriginalTemplateId(String.valueOf(sourceId));
        command.setSourceVersion(sourceVersion);
        command.setScope("");
        return command;
    }

    private static JsonNode sortSubmission(JsonNode order) {
        return JSON.createObjectNode().set("sort", JSON.createObjectNode().set("order", order.deepCopy()));
    }

    private static JsonNode pick(String optionId) {
        return JSON.createObjectNode().set("qa", JSON.createObjectNode().put("optionId", optionId));
    }

    /** 主题骨架只提供真实幕／任务／物资身份；玩法内容全部来自范本节点配置。 */
    private static ObjectNode themeSkeleton(String[] preset) {
        ObjectNode theme = JSON.createObjectNode().put("schemaVersion", 1).put("capacity", 0);
        ArrayNode items = theme.putArray("items");
        for (String item : List.of("item_fantasy_first", "item_fantasy_second")) {
            items.addObject().put("id", item).put("name", item).put("kind", "ITEM");
        }
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        game.putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST").put("actionLimitPolicy", "BLOCK_OVERFLOW");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "preset_act");
        act.putObject("completionRule").put("type", "ALL");
        ArrayNode questKeys = ((ObjectNode) game.path("acts").get(0)).putArray("questKeys");
        ArrayNode quests = game.putArray("quests");
        for (int i = 1; i < preset.length; i++) {
            String nodeId = preset[i];
            questKeys.add("q_" + nodeId);
            quests.addObject().put("questKey", "q_" + nodeId).put("actKey", "preset_act")
                    .put("nodeId", Long.parseLong(nodeId)).put("locationKey", "slot_" + nodeId)
                    .put("settlementSlot", "slot_" + nodeId);
        }
        return theme;
    }

    private static Map<Long, String> actKeys(String[] preset) {
        Map<Long, String> acts = new LinkedHashMap<>();
        for (int i = 1; i < preset.length; i++) acts.put(Long.parseLong(preset[i]), "preset_act");
        return acts;
    }

    private static JsonNode facilityOf(JsonNode frozen, long nodeId) {
        for (JsonNode facility : frozen.path("gameplayRuntime").path("facilities")) {
            if (facility.path("operationNodeId").asLong() == nodeId) return facility;
        }
        throw new AssertionError("没有生成节点 " + nodeId + " 的模板设施");
    }

    private static String textOfAllNodes(String code, JsonNode nodeFiles) throws Exception {
        StringBuilder all = new StringBuilder();
        for (JsonNode nodeFile : nodeFiles) all.append(read(code + "/" + nodeFile.asText()));
        return all.toString();
    }

    private static Set<String> slotKeys(JsonNode binding) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode slot : binding.path("slots")) keys.add(slot.path("key").asText());
        return keys;
    }

    private static List<String> splitCsv(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return values;
    }

    private static JsonNode matrixRow(String key) {
        JsonNode matrix = GameplaySupportMatrix.view();
        for (String bucket : List.of("types", "addons")) {
            for (JsonNode row : matrix.path(bucket)) if (key.equals(row.path("key").asText())) return row;
        }
        return null;
    }

    /** 配置段 → 支持矩阵 key：qa 段按 mode 区分 TYPE／PICK。 */
    private static String capabilityKey(String section) {
        return "qa".equals(section) ? "qaPick" : section;
    }

    private static String read(String relative) throws Exception {
        try (InputStream in = ThemePresetExtendedContractTest.class
                .getResourceAsStream("/questo-theme-presets/" + relative)) {
            assertNotNull(in, "缺少范本资源 " + relative);
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        }
    }
}
