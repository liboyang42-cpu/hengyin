package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.MediaPublicationTestFixtures;
import com.chengyinhub.business.service.support.TemplateAdoptionService;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T04／T06／T07／T08 范本的**真实采用闭环**:范本 → 审核真源 → 货架投影 → 作者副本 → 保存读回 →
 * 按局冻结。判题、投影、能力表的行为已由 T1／T2 两个类覆盖;本类只钉住 T2 留下的那一环 ——
 * 采用写入走的是**真实** {@link TemplatePublishServiceImpl#saveOrUpdateDraft},不是替身。
 *
 * <p>为什么必须是真实实现:采用回执只说「拿到了 draftId」,并不代表内容真的搬过去了。
 * 若发布服务把 {@code advancedConfigJson} 静默丢掉,替身仍然回一个 ID,测试照样绿;
 * 作者随后看到的是一份没有玩法的空模板。这里读回真实落库实体,逐字段比对玩法配置。
 *
 * <p>范本自身的状态:磁盘 JSON 不是商品,也不是「玩家可采用」的证明。可采用的唯一来源是
 * {@code cms_template_library} 里一条已审核、已上架的投影行(由 {@code submitTemplateToMarket}
 * 在个人审核通过后产生)。所以本类同时钉住:未上架=不可采用,未核验=不发布。
 *
 * <p>不连库、不起服务:Mapper 只记录行,发布/采用/冻结都是生产实现。
 */
class ThemePresetAdoptionLoopContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 每包取一个范本节点做闭环证据:取没有媒体引用的那个,避免把媒体审核混进采用链路。 */
    private static final String[][] PRESETS = {
            {"T04-era-trace", "nodes/node-02.json"},
            {"T06-building-observation", "nodes/node-02.json"},
            {"T07-original-fantasy", "nodes/node-01.json"},
            {"T08-multi-clue-investigation", "nodes/node-01.json"},
    };
    private static final long OWNER = 41L;
    private static final long LIBRARY_ID = 7_001L;
    private static final long CANONICAL_ID = 5_001L;

    // ------------------------------------------------------------ 1 范本 → 真实模板列

    /**
     * 范本给出的 {@code sourceRecords} 必须能直接落成真实的 {@code cms_member_template} 字段,
     * 且 {@code validationMethod} 与节点配置的机械推导一致 —— 写错这一列,节点到点会按
     * 「无需验证」直接打卡,高级玩法形同虚设。
     */
    @Test
    void everyPresetSourceRecordMapsToRealTemplateColumns() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            JsonNode meta = JSON.readTree(read(code + "/preset.json"));
            JsonNode records = meta.path("sourceRecords");
            assertEquals(meta.path("nodes").size(), records.size(),
                    code + " 每个范本节点都要有一条可采用记录，否则作者只拿得到部分内容");
            for (JsonNode record : records) {
                String nodeFile = record.path("node").asText();
                JsonNode config = JSON.readTree(read(code + "/" + nodeFile));
                // 真实推导口径(GameplayTemplateSlotRuntimeTest 同一份):有 qa 段用选项验证,其余走 0。
                int derived = config.path("qa").path("enabled").asBoolean(false) ? 3 : 0;
                assertEquals(derived, record.path("validationMethod").asInt(-1),
                        code + " " + nodeFile + " 的 validationMethod 必须由节点配置推导，不能手填");
                assertFalse(record.path("templateTitle").asText("").trim().isEmpty(),
                        code + " " + nodeFile + " 必须有标题，空标题的货架行无法被发现");
                assertTrue(record.path("templateTitle").asText().contains("待填"),
                        code + " " + nodeFile + " 的标题必须标成待填，不能把范本名当成品名");
                assertEquals("PERSONAL_TEMPLATE", record.path("adoptScope").asText(),
                        code + " 节点绑定的是个人模板；公共库 ID 只能出现在 originalTemplateId");
                assertEquals("REAL_CONTENT_OWNER_REQUIRED_NO_PLACEHOLDER_ID", record.path("ownerPolicy").asText(),
                        code + " 不能把 0 或占位用户写成发布者");
                assertEquals("PENDING_REVIEW_UNTIL_REAL_OWNER_PUBLISHES", record.path("publishPolicy").asText(),
                        code + " 未核验范本不得直接置为上架");

                // 落库前必须过真实保存闸;否则这条记录只是磁盘上的一段 JSON。
                assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()),
                        code + " " + nodeFile + " 必须过真实高级玩法保存闸");
            }
            assertFalse(meta.path("publishBlockers").isEmpty(), code + " 必须声明发布阻断条件");
            assertTrue(meta.path("dataSource").path("noDiskPackageConsumed").asBoolean(),
                    code + " 必须如实声明磁盘包不作为消费来源");
        }
    }

    // ------------------------------------------------------------ 2 未上架 = 不可采用

    /**
     * 「模板可采用」的判据只有一条:货架行已审核(status=1)且已上架(publish_status=1)。
     * 范本声明的 {@code publishPolicy=PENDING_REVIEW_UNTIL_REAL_OWNER_PUBLISHES} 就落在这里 ——
     * 未填完/未核验的范本停在待审态,玩家侧连来源都查不到。
     */
    @Test
    void unpublishedPresetSourceIsNeverAdoptable() throws Exception {
        Harness harness = new Harness(presetConfig("T07-original-fantasy", "nodes/node-01.json"));
        // 先拿在架时的版本号,再由发布者把范本停在待审态:模拟「范本已存在但还没核验完」。
        String version = harness.sourceVersion();
        harness.source.setStatus(0L);
        harness.source.setPublishStatus(0L);

        ServiceException missing = assertThrows(ServiceException.class,
                () -> harness.adoption.source(OWNER, OWNER, String.valueOf(LIBRARY_ID)));
        assertEquals(Integer.valueOf(404), missing.getCode(), "待审的范本对外必须是不存在");
        assertTrue(missing.getMessage().contains("已下架"), missing.getMessage());

        Map<String, Object> refused = harness.adoption.adopt(OWNER, OWNER,
                command("adopt_unpublished_request", version), "author");
        assertEquals("REJECTED", refused.get("status"), refused.toString());
        // ⚠️ 现状:未上架与真版本冲突共用 SOURCE_VERSION_CONFLICT。作者侧只会看到「版本冲突」,
        // 不知道是「还没上架」。已列入交付报告的交界缺口,改在采用服务(非本工作位文件)。
        assertEquals("SOURCE_VERSION_CONFLICT", refused.get("reason"));
        assertTrue(harness.draftsById.isEmpty(), "被拒的采用不得写出任何草稿");
    }

    // ------------------------------------------------------------ 2b 跨城绑定

    /**
     * 同一套范本结构在甲城、乙城各占一份绑定合同:槽位结构相同、值必须为空、
     * 换城必须重绑五项。照抄甲城的节点编号会被真实引用闸拒 —— 换标题不算重新绑定。
     */
    @Test
    void citiesShareTheStructureButRebindingIsRequiredBeforePublish() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            JsonNode cityA = JSON.readTree(read(code + "/bindings/city-a.json"));
            JsonNode cityB = JSON.readTree(read(code + "/bindings/city-b.json"));
            assertEquals("city-a", cityA.path("cityInstance").asText());
            assertEquals("city-b", cityB.path("cityInstance").asText());
            assertEquals(slotKeys(cityA), slotKeys(cityB), code + " 两城必须复用同一套槽位结构");
            assertFalse(slotKeys(cityA).isEmpty(), code + " 绑定合同必须有槽位");

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
                for (JsonNode mapped : binding.path("nodeIdMap")) {
                    assertTrue(mapped.isNull(), code + " 占位节点未重绑前不能有实值");
                }
            }
        }

        // 真实引用闸:本城节点集合内的引用通过,照抄别城节点编号被拒。
        long ownNode = 900101L;
        ThemeRuntimeConfig.assertReferences(minimalTheme(ownNode), Set.of(ownNode), Set.of());
        ObjectNode borrowed = minimalTheme(99L);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ThemeRuntimeConfig.assertReferences(borrowed, Set.of(ownNode), Set.of()));
        assertTrue(refused.getMessage().contains("外来节点"), refused.getMessage());
    }

    // ------------------------------------------------------------ 3 真实采用 + 读回

    /**
     * 正向闭环:真实 {@link TemplatePublishServiceImpl} 从**服务端**货架行复制全部内容,
     * 生成作者自己的草稿。玩法配置逐字段相等,草稿 ID ≠ 来源 ID。
     */
    @Test
    void adoptionCopiesTheWholeGameplayConfigIntoAnIndependentDraft() throws Exception {
        for (String[] preset : PRESETS) {
            String code = preset[0];
            Harness harness = new Harness(presetConfig(code, preset[1]));
            String sourceConfig = harness.source.getAdvancedConfigJson();
            String requestId = "adopt_" + code.replace('-', '_') + "_request";
            String version = harness.sourceVersion();

            Map<String, Object> receipt = harness.adoption.adopt(OWNER, OWNER,
                    command(requestId, version), "author");
            assertEquals("COMPLETED", receipt.get("status"), code + " " + receipt);

            long draftId = Long.parseLong(String.valueOf(receipt.get("draftId")));
            assertNotEquals(LIBRARY_ID, draftId, code + " 采用必须生成新草稿，不能复用货架行");
            assertNotEquals(CANONICAL_ID, draftId, code + " 也不能直接拿来源的个人模板当自己的副本");
            assertEquals(String.valueOf(LIBRARY_ID), String.valueOf(receipt.get("originalTemplateId")),
                    code + " 回执要记住来源货架 ID");

            CmsMemberTemplate draft = harness.draftsById.get(draftId);
            assertNotNull(draft, code + " 草稿必须真的落库");
            assertEquals(Long.valueOf(OWNER), draft.getMemberId(), code + " 草稿归属必须是采用者");
            assertEquals(Integer.valueOf(0), draft.getDraftStatus(), code + " 采用产出的是草稿，不是已发布内容");
            // 这一句是本类存在的理由:整份高级玩法配置必须原样搬过去。
            assertEquals(JSON.readTree(sourceConfig), JSON.readTree(draft.getAdvancedConfigJson()),
                    code + " 采用必须整段复制玩法配置（含答案与秘密字段），不能只搬题面");
            assertEquals(Long.valueOf(LIBRARY_ID), draft.getOriginalTemplateId(), code + " 出身记录为货架 ID");
            assertEquals(harness.source.getTitle(), draft.getTitle(), code + " 标题来自服务端来源，不信客户端");
            assertEquals(harness.source.getQuestionAnswer(), draft.getQuestionAnswer(),
                    code + " 答案同样由服务端复制，客户端拿到的是剥离后的空值");

            // 同一条内容在公开投影里必须看不到答案:秘密只走服务端到采用稿这一段。
            CmsTemplateLibrary publicView = new CmsTemplateLibrary();
            publicView.setQuestionAnswer(harness.source.getQuestionAnswer());
            publicView.setAdvancedConfigJson(harness.source.getAdvancedConfigJson());
            com.chengyinhub.business.util.TemplateSecrets.strip(publicView);
            assertNull(publicView.getQuestionAnswer(), code + " 公开投影不得下发 questionAnswer");
            String projected = publicView.getAdvancedConfigJson();
            assertFalse(projected.contains("\"correct\""), code + " 公开投影不得下发 correct");
            assertFalse(projected.contains("answerOrder"), code + " 公开投影不得下发排序答案");
            assertFalse(projected.contains("\"answer\""), code + " 公开投影不得下发分类答案");
            // 反过来由上面的整段相等断言保证:采用稿里答案仍在,只是不下发给玩家。

            // 同一请求号重试:回原草稿,不再写一份。
            Map<String, Object> replay = harness.adoption.adopt(OWNER, OWNER,
                    command(requestId, version), "author");
            assertEquals(receipt.get("draftId"), replay.get("draftId"));
            verify(harness.memberTemplates, times(1)).insertCmsMemberTemplate(any());
        }
    }

    // ------------------------------------------------------------ 4 副本隔离 / 源修订

    /**
     * 采用之后再编辑自己的草稿:不回流到来源,也不影响同源的另一份草稿。
     * 走的是真实 {@code saveOrUpdateDraft(id)} 覆盖路径,不是直接改对象。
     */
    @Test
    void editingAnAdoptedDraftNeverWritesBackToTheSource() throws Exception {
        Harness harness = new Harness(presetConfig("T07-original-fantasy", "nodes/node-01.json"));
        String sourceBefore = harness.source.getAdvancedConfigJson();
        String version = harness.sourceVersion();

        long mine = adopt(harness, "adopt_edit_a_request", version);
        long theirs = adopt(harness, "adopt_edit_b_request", version);
        assertNotEquals(mine, theirs, "两个人采用同一范本必须各得一份草稿");

        ObjectNode edited = (ObjectNode) JSON.readTree(harness.draftsById.get(mine).getAdvancedConfigJson());
        ((ObjectNode) edited.path("qa")).put("title", "待填：乙作者自己写的题面");
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setId(mine);
        request.setTitle("乙作者的玩法");
        request.setValidationMethod(harness.source.getValidationMethod());
        request.setAdvancedConfigJson(edited.toString());
        assertEquals(Long.valueOf(mine), harness.publisher.saveOrUpdateDraft(request, OWNER, "author"));

        CmsMemberTemplate saved = harness.draftsById.get(mine);
        assertEquals("待填：乙作者自己写的题面",
                JSON.readTree(saved.getAdvancedConfigJson()).path("qa").path("title").asText(),
                "草稿保存后必须读回自己写的内容");
        assertEquals(sourceBefore, harness.source.getAdvancedConfigJson(), "编辑草稿不得改动货架来源");
        assertEquals(JSON.readTree(sourceBefore),
                JSON.readTree(harness.draftsById.get(theirs).getAdvancedConfigJson()),
                "同一来源的另一份草稿也不受这次编辑影响");
        assertEquals(Long.valueOf(LIBRARY_ID), saved.getOriginalTemplateId(), "编辑不能抹掉或替换出身");
    }

    /**
     * 来源被作者改版之后:已采用的草稿保持旧内容(旧局 / 旧实例不被静默替换),
     * 而用旧版本号发起的新采用被拒 —— 不静默换成另一份内容。
     */
    @Test
    void aSourceRevisionNeverRewritesAnAlreadyAdoptedDraft() throws Exception {
        Harness harness = new Harness(presetConfig("T08-multi-clue-investigation", "nodes/node-01.json"));
        String version = harness.sourceVersion();
        long adopted = adopt(harness, "adopt_before_revision", version);
        String adoptedContent = harness.draftsById.get(adopted).getAdvancedConfigJson();

        ObjectNode revised = (ObjectNode) JSON.readTree(harness.source.getAdvancedConfigJson());
        ((ObjectNode) revised.path("qa")).put("title", "作者改版后的题面");
        harness.source.setAdvancedConfigJson(revised.toString());
        harness.source.setTitle("作者改版后的标题");
        assertNotEquals(version, harness.sourceVersion(), "内容改了版本号必须变");

        assertEquals(adoptedContent, harness.draftsById.get(adopted).getAdvancedConfigJson(),
                "来源改版不得覆盖已采用的草稿");

        Map<String, Object> stale = harness.adoption.adopt(OWNER, OWNER,
                command("adopt_after_revision_stale", version), "author");
        assertEquals("REJECTED", stale.get("status"), stale.toString());
        assertEquals("SOURCE_VERSION_CONFLICT", stale.get("reason"),
                "旧版本号必须被拒，不能静默采用改版内容");

        // 用新版本号重来一次:拿到的是改版后的内容,与旧草稿互不影响。
        long refreshed = adopt(harness, "adopt_after_revision_fresh", harness.sourceVersion());
        assertNotEquals(adopted, refreshed);
        assertEquals("作者改版后的题面",
                JSON.readTree(harness.draftsById.get(refreshed).getAdvancedConfigJson())
                        .path("qa").path("title").asText());
        assertEquals(adoptedContent, harness.draftsById.get(adopted).getAdvancedConfigJson(),
                "新采用也不得回头改写旧草稿");
    }

    // ------------------------------------------------------------ 5 归属与身份

    /**
     * 采用回执绑定「发起身份 + 范围 + 请求号」。换个人拿同一个请求号重放,必须被拒,
     * 不能把别人的草稿当成自己的。
     */
    @Test
    void anAdoptionReceiptCannotBeReplayedByAnotherIdentity() throws Exception {
        Harness harness = new Harness(presetConfig("T06-building-observation", "nodes/node-02.json"));
        String version = harness.sourceVersion();
        adopt(harness, "adopt_identity_request", version);

        ServiceException mismatch = assertThrows(ServiceException.class, () -> harness.adoption.adopt(
                OWNER, 92L, command("adopt_identity_request", version), "other"));
        assertEquals(Integer.valueOf(403), mismatch.getCode(), "同一请求号换身份重放必须 403");
    }

    /**
     * 节点 {@code templateId} 只能是个人模板 ID:采用产出的是新草稿 ID,才是可绑定的那一个。
     * 货架 ID 是 {@code originalTemplateId} 的语义,两者不能混用。
     */
    @Test
    void nodeBindingUsesTheAdoptedDraftIdNotTheShelfId() throws Exception {
        Harness harness = new Harness(presetConfig("T07-original-fantasy", "nodes/node-01.json"));
        long draftId = adopt(harness, "adopt_binding_request", harness.sourceVersion());
        long nodeId = 900301L;
        ObjectNode theme = minimalTheme(nodeId);
        ObjectNode binding = theme.putObject("templateBindings").putObject(String.valueOf(nodeId));
        binding.put("templateId", draftId);
        binding.put("slotDigest", "0".repeat(64));
        binding.putObject("values");
        binding.putObject("items");

        ObjectNode parsed = ThemeRuntimeConfig.parseDraft(theme.toString());
        assertEquals(draftId, parsed.path("templateBindings").path(String.valueOf(nodeId))
                .path("templateId").asLong(), "个人模板 ID 可作为节点绑定身份");
        // 形状闸只认「正整数」,分不出货架 ID 与个人模板 ID —— 身份区分靠这里:
        // 采用必须产出与货架不同的 ID,节点绑的是它。
        assertNotEquals(LIBRARY_ID, draftId, "采用必须产出与货架不同的身份");
    }

    // ------------------------------------------------------------ 6 按局冻结

    /**
     * 采用后的内容进入主题,按局冻结时取该局自己的场景节点配置;冻结副本改不动原配置,
     * 因此两份采用实例各自持有独立副本。
     */
    @Test
    void frozenRunsKeepIndependentCopiesOfTheAdoptedGameplay() throws Exception {
        Harness harness = new Harness(presetConfig("T04-era-trace", "nodes/node-02.json"));
        long draftId = adopt(harness, "adopt_freeze_request", harness.sourceVersion());
        String draftConfig = harness.draftsById.get(draftId).getAdvancedConfigJson();
        long nodeId = 900102L;

        ObjectNode themeA = themeWithScene(nodeId, draftConfig);
        ObjectNode themeB = themeWithScene(nodeId, draftConfig);
        ObjectNode frozenA = ThemeRuntimeConfig.freeze(themeA, 0, 0L);
        ObjectNode frozenB = ThemeRuntimeConfig.freeze(themeB, 1, 0L);
        assertEquals(JSON.readTree(draftConfig),
                JSON.readTree(frozenA.path("nodeConfigs").path(String.valueOf(nodeId)).toString()),
                "冻结必须带上采用来的玩法配置");
        assertEquals(frozenA.path("nodeConfigs"), frozenB.path("nodeConfigs"),
                "同一份内容冻结两次必须一致");

        ((ObjectNode) frozenA.path("nodeConfigs").path(String.valueOf(nodeId)))
                .put("schemaVersion", 1).putObject("qa").put("title", "甲局改过的题面");
        assertNotEquals(frozenA.path("nodeConfigs").toString(), frozenB.path("nodeConfigs").toString(),
                "每局冻结的是自己的副本，一局改动不串到另一局");
        assertEquals(JSON.readTree(draftConfig), JSON.readTree(
                ((ObjectNode) themeB.path("scenes").get(0)).path("nodeConfigs")
                        .path(String.valueOf(nodeId)).toString()),
                "冻结不改写源主题配置");
        assertEquals(draftConfig, harness.draftsById.get(draftId).getAdvancedConfigJson(),
                "局内改动不写回作者的采用草稿");
    }

    // ------------------------------------------------------------ 装配

    private static final class Harness {
        final TemplatePublishServiceImpl publisher = new TemplatePublishServiceImpl();
        final TemplateAdoptionService adoption;
        final Map<Long, CmsMemberTemplate> draftsById = new LinkedHashMap<>();
        final AtomicLong draftIds = new AtomicLong(6_000L);
        final ICmsMemberTemplateService memberTemplates = mock(ICmsMemberTemplateService.class);
        final CmsTemplateLibrary source = new CmsTemplateLibrary();
        final CmsMemberTemplate canonical = new CmsMemberTemplate();
        final TemplateAdoptionReceiptMapper receipts = mock(TemplateAdoptionReceiptMapper.class);
        final Map<String, Map<String, Object>> rows = new HashMap<>();

        Harness(String nodeConfig) {
            source.setId(LIBRARY_ID);
            source.setMemberId(OWNER);
            source.setMemberTemplateId(CANONICAL_ID);
            source.setTitle("待填：范本标题（发布者改写）");
            source.setQuestionName("待填：作者问题");
            source.setQuestionAnswer("待填：作者答案");
            // 与范本 sourceRecords 同一推导:有 qa 段用选项验证,其余走 0(判定在高级玩法)。
            source.setValidationMethod(hasQa(nodeConfig) ? 3 : 0);
            source.setStatus(1L);
            source.setPublishStatus(1L);
            source.setDelFlag(0);
            source.setDuration(600L);
            source.setAdvancedConfigJson(nodeConfig);

            canonical.setId(CANONICAL_ID);
            canonical.setMemberId(OWNER);
            canonical.setTitle(source.getTitle());
            canonical.setStatus(1L);
            canonical.setPublishStatus(1L);
            canonical.setDelFlag(0);
            canonical.setAdvancedConfigJson(nodeConfig);

            ICmsTemplateLibraryService libraries = mock(ICmsTemplateLibraryService.class);
            when(libraries.selectCmsTemplateLibraryById(LIBRARY_ID)).thenReturn(source);
            ICapabilityGuard capabilities = mock(ICapabilityGuard.class);
            when(capabilities.checkAdvancedMechanics(anyLong(), any())).thenReturn(null);
            when(capabilities.checkDesignMedal(anyLong())).thenReturn(null);

            when(memberTemplates.insertCmsMemberTemplate(any())).thenAnswer(call -> {
                CmsMemberTemplate row = call.getArgument(0);
                row.setId(draftIds.incrementAndGet());
                draftsById.put(row.getId(), row);
                return 1;
            });
            when(memberTemplates.updateCmsMemberTemplate(any())).thenAnswer(call -> {
                CmsMemberTemplate row = call.getArgument(0);
                draftsById.put(row.getId(), row);
                return 1;
            });
            when(memberTemplates.selectCmsMemberTemplateById(anyLong())).thenAnswer(call -> {
                Long id = call.getArgument(0);
                return Long.valueOf(CANONICAL_ID).equals(id) ? canonical : draftsById.get(id);
            });

            // 编辑已采用草稿走的是覆盖路径:先锁自己的行,再把仍公开的货架投影撤回待审。
            CmsMemberTemplateMapper memberTemplateMapper = mock(CmsMemberTemplateMapper.class);
            when(memberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(any()))
                    .thenAnswer(call -> {
                        List<Long> ids = call.getArgument(0);
                        CmsMemberTemplate row = draftsById.get(ids.get(0));
                        return row == null ? Collections.emptyList() : Collections.singletonList(row);
                    });
            CmsTemplateLibraryMapper libraryMapper = mock(CmsTemplateLibraryMapper.class);
            when(libraryMapper.selectByMemberTemplateForUpdate(anyLong())).thenReturn(Collections.emptyList());
            IAuditTaskService auditTasks = mock(IAuditTaskService.class);
            AuditTaskMapper auditTaskMapper = mock(AuditTaskMapper.class);

            ReflectionTestUtils.setField(publisher, "memberTemplateService", memberTemplates);
            ReflectionTestUtils.setField(publisher, "memberTemplateMapper", memberTemplateMapper);
            ReflectionTestUtils.setField(publisher, "templateLibraryService", libraries);
            ReflectionTestUtils.setField(publisher, "capabilityGuard", capabilities);
            ReflectionTestUtils.setField(publisher, "couponRewardAuthorizationService",
                    mock(ICouponRewardAuthorizationService.class));
            ReflectionTestUtils.setField(publisher, "mediaPublicationGuard",
                    MediaPublicationTestFixtures.template(OWNER));
            ReflectionTestUtils.setField(publisher, "libraryMapper", libraryMapper);
            ReflectionTestUtils.setField(publisher, "auditTaskService", auditTasks);
            ReflectionTestUtils.setField(publisher, "auditTaskMapper", auditTaskMapper);

            when(receipts.sourceCanonicalId(anyLong())).thenReturn(CANONICAL_ID);
            when(receipts.lockSource(anyLong())).thenReturn(source);
            when(receipts.lockDraft(anyLong())).thenAnswer(call -> {
                Long id = call.getArgument(0);
                return Long.valueOf(CANONICAL_ID).equals(id) ? canonical : draftsById.get(id);
            });
            when(receipts.reserve(anyMap())).thenAnswer(call -> {
                Map<String, Object> facts = call.getArgument(0);
                rows.computeIfAbsent(facts.get("actor") + ":" + facts.get("requestId"), key -> {
                    Map<String, Object> row = new HashMap<>(facts);
                    row.put("status", "PENDING");
                    return row;
                });
                return 1;
            });
            when(receipts.lock(anyLong(), anyString())).thenAnswer(call -> new HashMap<>(
                    rows.getOrDefault(call.getArgument(0) + ":" + call.getArgument(1), Map.of())));
            when(receipts.finish(anyLong(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
                Map<String, Object> row = rows.get(call.getArgument(0) + ":" + call.getArgument(1));
                if (row == null) return 0;
                row.put("status", call.getArgument(2));
                if (call.getArgument(3) != null) row.put("draftId", call.getArgument(3));
                row.put("reason", call.getArgument(4));
                row.put("draftVersion", call.getArgument(5));
                return 1;
            });
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            adoption = new TemplateAdoptionService(receipts, publisher, manager);
        }

        String sourceVersion() {
            return String.valueOf(adoption.source(OWNER, OWNER, String.valueOf(LIBRARY_ID)).get("sourceVersion"));
        }
    }

    private static long adopt(Harness harness, String requestId, String version) {
        Map<String, Object> receipt = harness.adoption.adopt(OWNER, OWNER, command(requestId, version), "author");
        assertEquals("COMPLETED", receipt.get("status"), receipt.toString());
        return Long.parseLong(String.valueOf(receipt.get("draftId")));
    }

    private static TemplateAdoptionCommand command(String requestId, String sourceVersion) {
        TemplateAdoptionCommand command = new TemplateAdoptionCommand();
        command.setRequestId(requestId);
        command.setOriginalTemplateId(String.valueOf(LIBRARY_ID));
        command.setSourceVersion(sourceVersion);
        command.setScope("");
        return command;
    }

    /** 完成方式推导:有启用中的 qa 段 → 选项验证(3);否则 0,判定在高级玩法配置里。 */
    private static boolean hasQa(String nodeConfig) {
        try {
            return JSON.readTree(nodeConfig).path("qa").path("enabled").asBoolean(false);
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("范本节点配置不可读取", unreadable);
        }
    }

    private static Set<String> slotKeys(JsonNode binding) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (JsonNode slot : binding.path("slots")) keys.add(slot.path("key").asText());
        return keys;
    }

    /** 最小主题骨架:一个幕、一个任务、一个场景,够真实解析与冻结用。 */
    private static ObjectNode minimalTheme(long nodeId) {
        ObjectNode theme = JSON.createObjectNode().put("schemaVersion", 1).put("capacity", 0);
        theme.putArray("items");
        ObjectNode scene = theme.putArray("scenes").addObject().put("id", "scene_a").put("name", "甲城");
        scene.putObject("nodeConfigs");
        theme.put("currentScene", "scene_a");
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        game.putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST").put("actionLimitPolicy", "BLOCK_OVERFLOW");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "preset_act");
        act.putObject("completionRule").put("type", "ALL");
        ((ObjectNode) game.path("acts").get(0)).putArray("questKeys").add("q_" + nodeId);
        game.putArray("quests").addObject().put("questKey", "q_" + nodeId).put("actKey", "preset_act")
                .put("nodeId", nodeId).put("locationKey", "slot_" + nodeId).put("settlementSlot", "slot_" + nodeId);
        return theme;
    }

    private static ObjectNode themeWithScene(long nodeId, String nodeConfig) throws Exception {
        ObjectNode theme = minimalTheme(nodeId);
        ObjectNode scene = (ObjectNode) theme.path("scenes").get(0);
        ObjectNode nodeConfigs = (ObjectNode) scene.path("nodeConfigs");
        nodeConfigs.set(String.valueOf(nodeId), JSON.readTree(nodeConfig));
        return theme;
    }

    private static String presetConfig(String code, String nodeFile) throws Exception {
        return read(code + "/" + nodeFile);
    }

    private static String read(String relative) throws Exception {
        try (InputStream in = ThemePresetAdoptionLoopContractTest.class
                .getResourceAsStream("/questo-theme-presets/" + relative)) {
            assertNotNull(in, "缺少范本资源 " + relative);
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        }
    }
}
