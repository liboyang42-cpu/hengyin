package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T01–T08 固定玩法预设 → 作者个人草稿的真实链路。
 *
 * <p>为什么必须有这一层：预设本身只是受控结构。"能选"不等于"能建"——如果创建时把
 * {@code advancedConfigJson} 丢掉、把 {@code presetSource} 写错、或者让同一份草稿被第二个身份改，
 * 作者拿到的都是一份看着像成品、实际不能用的东西。这里逐条钉住，并且**写入走真实**
 * {@link TemplatePublishServiceImpl#saveOrUpdateDraft}，不是替身。
 *
 * <p>预设不是市场内容：没有 {@code cms_template_library} 来源行，{@code originalTemplateId} 恒为 0，
 * 创建出来的是 draft_status=0 / status=0 / publish_status=0 的草稿——公开与可售仍归原审核流程。
 *
 * <p>不连库、不起服务；本类只验源码级契约，真实 Java/MySQL/HTTP 由 I 在批准 VPS 执行。
 */
class GameplayPresetCatalogContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long AUTHOR = 41L;
    private static final long OTHER = 92L;

    // ------------------------------------------------------------ 1 目录自身

    /**
     * 正式资源必须真的可加载：每个预设的结构填满声明值后都过真实保存闸，
     * 声明的能力与模板实际启用的段一致，作者字段都被引用。这条挂了说明 catalog/模板写错了。
     */
    @Test
    void shippedCatalogLoadsAndEveryPresetSurvivesTheRealSaveGate() {
        GameplayPresetCatalog catalog = GameplayPresetCatalog.shared();
        assertEquals(8, catalog.presets().size(), "正式目录必须覆盖 T01–T08 八类题材结构");
        for (GameplayPresetCatalog.Preset preset : catalog.presets()) {
            assertEquals(preset.presetKey() + "@" + preset.version(), preset.sourceMark());
            assertFalse(preset.capabilities().isEmpty(), preset.presetKey() + " 必须声明用到的真实玩法能力");
            for (String capability : preset.capabilities()) {
                assertTrue(matrixHas(capability),
                        preset.presetKey() + " 声明了支持矩阵里没有的能力：" + capability);
                assertFalse(GameplaySupportMatrix.presentation(capability),
                        preset.presetKey() + " 不能把呈现段当题型声明：" + capability);
            }
            assertFalse(preset.fields().isEmpty(), preset.presetKey() + " 必须声明作者要填的内容");
            assertNotNull(preset.cityBinding().appliesAt(), preset.presetKey() + " 必须说明城市绑定在哪一层");
            // 按声明填满后必须能过真实保存闸（加载期已验过，这里再钉一次可执行性）。
            String materialized = GameplayPresetCatalog.materialize(
                    preset.template(), preset.fields(), GameplayPresetCatalog.sampleValues(preset.fields())).toString();
            assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(materialized),
                    preset.presetKey() + " 填满后必须过保存闸");
            // 公开视图只给声明，绝不能带出模板与答案。
            String publicJson = JSON.valueToTree(preset.publicView()).toString();
            assertFalse(publicJson.contains("\"correct\""), preset.presetKey() + " 公开视图不得带答案");
            assertFalse(publicJson.contains("${"), preset.presetKey() + " 公开视图不得暴露模板占位");
        }
    }

    /**
     * 声明能力与实际启用段必须严格一致：多报等于骗作者，漏报等于保存时突然被拒。
     */
    @Test
    void declaredCapabilitiesMatchWhatTheTemplateActuallyEnables() {
        for (GameplayPresetCatalog.Preset preset : GameplayPresetCatalog.shared().presets()) {
            Set<String> enabled = new java.util.LinkedHashSet<>();
            preset.template().fields().forEachRemaining(entry -> {
                if (!entry.getValue().path("enabled").asBoolean(false)) return;
                if (GameplaySupportMatrix.presentation(entry.getKey())) return;
                enabled.add("qa".equals(entry.getKey())
                        ? ("TYPE".equals(entry.getValue().path("mode").asText("")) ? "qaText" : "qaPick")
                        : entry.getKey());
            });
            assertEquals(new java.util.LinkedHashSet<>(preset.capabilities()), enabled,
                    preset.presetKey() + " 声明的能力必须与模板实际启用段完全一致");
        }
    }

    // ------------------------------------------------------------ 2 正常选择 → 真实草稿写入 → 读回

    /**
     * 正向链路：作者选一个预设、填完声明值，真实发布服务把它写成**自己的**草稿，读回来与写入一致。
     */
    @Test
    void choosingAPresetWritesARealDraftAndReadsItBack() throws Exception {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T03-family-observation");
        Map<String, Object> values = valuesFor(preset);

        Map<String, Object> receipt = harness.service.create(AUTHOR, "author", preset.presetKey(), values);
        long draftId = Long.parseLong(String.valueOf(receipt.get("draftId")));
        assertEquals("MINI_GAME_DRAFT", receipt.get("templateType"));
        assertEquals(preset.sourceMark(), receipt.get("presetSource"));
        assertEquals(0, receipt.get("draftStatus"));
        assertEquals(0, receipt.get("publishStatus"));

        CmsMemberTemplate saved = harness.drafts.get(draftId);
        assertNotNull(saved, "预设草稿必须真的落库");
        assertEquals(Long.valueOf(AUTHOR), saved.getMemberId(), "草稿归属必须是当前作者");
        assertEquals(preset.sourceMark(), saved.getPresetSource(), "预设来源必须落库");
        assertEquals(Long.valueOf(0L), saved.getOriginalTemplateId(), "预设不是市场采用，不能占用市场来源字段");
        assertEquals(Integer.valueOf(0), saved.getDraftStatus());
        assertEquals(Long.valueOf(0L), saved.getStatus());
        assertEquals(Long.valueOf(0L), saved.getPublishStatus());
        assertEquals(values.get("title"), saved.getTitle(), "标题取自作者填的参数");

        JsonNode config = JSON.readTree(saved.getAdvancedConfigJson());
        // 作者值真的写进了结构：排序答案来自作者的 ORDER，问答正确项来自作者的 CHOICE。
        assertEquals(JSON.valueToTree(values.get("order")), config.path("sort").path("answerOrder"),
                "排序答案必须是作者排的那一版");
        String correctId = null;
        for (JsonNode option : config.path("qa").path("options")) if (option.path("correct").asBoolean()) correctId = option.path("id").asText();
        assertEquals(values.get("rightAnswer"), correctId, "正确选项必须来自作者的选择");
        assertEquals(values.get("item1"), config.path("sort").path("items").get(0).path("label").asText());
        assertFalse(config.toString().contains("${"), "落库配置不得残留占位");
    }

    /**
     * 未填不建：缺一项就整体拒绝，并一次列全缺什么。不能建出一份"看起来能用"的半成品。
     */
    @Test
    void everyDeclaredFieldIsRequiredBeforeADraftExists() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T08-multi-clue-investigation");
        Map<String, Object> partial = valuesFor(preset);
        partial.remove("timerSeconds");
        partial.remove("question");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> harness.service.create(AUTHOR, "author", preset.presetKey(), partial));
        assertTrue(refused.getMessage().contains("timerSeconds"), refused.getMessage());
        assertTrue(refused.getMessage().contains("question"), "缺失项必须一次列全：" + refused.getMessage());
        assertTrue(harness.drafts.isEmpty(), "被拒的创建不得写出任何草稿");
    }

    /**
     * 只接受目录声明过的字段：作者多传一个键就拒（防止客户端塞进未校验的自定义配置）。
     */
    @Test
    void undeclaredAuthorFieldsAreRejectedInsteadOfSilentlyIgnored() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T05-city-treasure");
        Map<String, Object> values = valuesFor(preset);
        values.put("advancedConfigJson", "{\"schemaVersion\":1}");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> harness.service.create(AUTHOR, "author", preset.presetKey(), values));
        assertTrue(refused.getMessage().contains("advancedConfigJson"), refused.getMessage());
        assertTrue(harness.drafts.isEmpty());
    }

    // ------------------------------------------------------------ 3 编辑不回流 / 版本不覆盖

    /**
     * 作者改自己的预设草稿：不回流到目录、不改动别人的同预设草稿。
     * 编辑走真实覆盖保存路径（先锁自己的行）。
     */
    @Test
    void editingAPresetDraftNeverTouchesTheCatalogOrAnotherAuthorsDraft() throws Exception {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T05-city-treasure");
        long mine = Long.parseLong(String.valueOf(
                harness.service.create(AUTHOR, "author", preset.presetKey(), valuesFor(preset)).get("draftId")));
        long theirs = Long.parseLong(String.valueOf(
                harness.service.create(OTHER, "other", preset.presetKey(), valuesFor(preset)).get("draftId")));
        assertNotEquals(mine, theirs, "两个作者各得一份草稿");

        String theirsBefore = harness.drafts.get(theirs).getAdvancedConfigJson();
        JsonNode edited = JSON.readTree(harness.drafts.get(mine).getAdvancedConfigJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) edited.path("qa")).put("title", "作者改写后的题面");

        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setId(mine);
        request.setTitle("作者改写后的标题");
        request.setValidationMethod(harness.drafts.get(mine).getValidationMethod());
        request.setAdvancedConfigJson(edited.toString());
        assertEquals(Long.valueOf(mine), harness.publisher.saveOrUpdateDraft(request, AUTHOR, "author"));

        assertEquals("作者改写后的题面", JSON.readTree(harness.drafts.get(mine).getAdvancedConfigJson())
                .path("qa").path("title").asText(), "草稿必须读回作者改的内容");
        assertEquals(theirsBefore, harness.drafts.get(theirs).getAdvancedConfigJson(),
                "另一作者的草稿不受影响");
        assertEquals(preset.sourceMark(), harness.drafts.get(mine).getPresetSource(),
                "编辑不能把预设出身洗掉");
    }

    /**
     * 目录版本升级不覆盖已建草稿：旧草稿保持原内容，新预设版本只影响之后新建的草稿。
     */
    @Test
    void aNewPresetVersionNeverRewritesAnExistingDraft() throws Exception {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset v1 = GameplayPresetCatalog.shared().require("T07-fantasy-festival");
        long oldDraft = Long.parseLong(String.valueOf(
                harness.service.create(AUTHOR, "author", v1.presetKey(), valuesFor(v1)).get("draftId")));
        String oldContent = harness.drafts.get(oldDraft).getAdvancedConfigJson();
        String oldSource = harness.drafts.get(oldDraft).getPresetSource();

        // 版本 2 的同一预设：结构不变、版本号变。已建草稿不得被它改写。
        GameplayPresetCatalog v2Catalog = GameplayPresetCatalog.parse(
                GameplayPresetCatalog.rawCatalogJson().replace("\"version\": 1", "\"version\": 2"),
                GameplayPresetCatalogContractTest::templateResource);
        GameplayPresetCatalog.Preset v2 = v2Catalog.require(v1.presetKey());
        assertEquals(2, v2.version());
        GameplayPresetDraftService v2Service = GameplayPresetDraftService.withCatalog(harness.publisher, v2Catalog);
        long newDraft = Long.parseLong(String.valueOf(
                v2Service.create(AUTHOR, "author", v2.presetKey(), valuesFor(v2)).get("draftId")));

        assertNotEquals(oldDraft, newDraft, "新版本必须新建草稿，不能复用旧的");
        assertEquals(oldContent, harness.drafts.get(oldDraft).getAdvancedConfigJson(),
                "新版本不得改写旧草稿内容");
        assertEquals(oldSource, harness.drafts.get(oldDraft).getPresetSource(),
                "旧草稿仍记旧版本来源");
        assertEquals(v2.sourceMark(), harness.drafts.get(newDraft).getPresetSource(),
                "新草稿记新版本来源");
        assertTrue(oldSource.endsWith("@1"), "旧草稿必须记旧版本号：" + oldSource);
    }

    // ------------------------------------------------------------ 4 不同身份

    /**
     * 别人的草稿既不能改也不能当成自己的：覆盖保存走的是真实 owner 校验。
     */
    @Test
    void anotherIdentityCannotOverwriteSomeoneElsesPresetDraft() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T02-ghost-tracking");
        long mine = Long.parseLong(String.valueOf(
                harness.service.create(AUTHOR, "author", preset.presetKey(), valuesFor(preset)).get("draftId")));
        String before = harness.drafts.get(mine).getAdvancedConfigJson();

        CmsTemplatePublishRequest hijack = new CmsTemplatePublishRequest();
        hijack.setId(mine);
        hijack.setTitle("别人想改");
        hijack.setValidationMethod(3);
        hijack.setAdvancedConfigJson("{\"schemaVersion\":1}");
        assertThrows(RuntimeException.class,
                () -> harness.publisher.saveOrUpdateDraft(hijack, OTHER, "other"),
                "换身份覆盖别人的草稿必须被拒");
        assertEquals(before, harness.drafts.get(mine).getAdvancedConfigJson(), "被拒后草稿内容不变");
    }

    /**
     * 预设来源不能由客户端伪造或迁移：编辑时声明另一个来源被拒。
     */
    @Test
    void presetSourceCannotBeRewrittenByTheClient() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T04-era-trace");
        long mine = Long.parseLong(String.valueOf(
                harness.service.create(AUTHOR, "author", preset.presetKey(), valuesFor(preset)).get("draftId")));
        CmsTemplatePublishRequest forged = new CmsTemplatePublishRequest();
        forged.setId(mine);
        forged.setTitle("换来源");
        forged.setValidationMethod(0);
        forged.setPresetSource("T01-urban-mystery@1");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> harness.publisher.saveOrUpdateDraft(forged, AUTHOR, "author"));
        assertTrue(refused.getMessage().contains("预设来源"), refused.getMessage());
        assertEquals(preset.sourceMark(), harness.drafts.get(mine).getPresetSource());
    }

    /**
     * 预设草稿不能同时声称市场来源：那会把平台结构伪装成别人的上架内容。
     */
    @Test
    void aPresetDraftCannotAlsoClaimAMarketSource() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T01-urban-mystery");
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("冒充市场来源");
        request.setValidationMethod(3);
        request.setAdvancedConfigJson(GameplayPresetCatalog.materialize(
                preset.template(), preset.fields(), GameplayPresetCatalog.sampleValues(preset.fields())).toString());
        request.setPresetSource(preset.sourceMark());
        request.setOriginalTemplateId(9_001L);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> harness.publisher.saveOrUpdateDraft(request, AUTHOR, "author"));
        assertTrue(refused.getMessage().contains("市场来源"), refused.getMessage());
        assertTrue(harness.drafts.isEmpty());
    }

    // ------------------------------------------------------------ 5 跨城绑定 / 秘密隔离

    /**
     * 跨城：预设声明必须重绑什么，作者拿到回执就知道；未重绑前不可发布的口径与原流程一致
     * （这里钉住声明本身，真实发布阻断由原发布闸负责）。
     */
    @Test
    void cityBindingDeclarationsAreCarriedIntoTheReceipt() {
        Harness harness = new Harness();
        for (String key : List.of("T04-era-trace", "T08-multi-clue-investigation")) {
            GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require(key);
            Map<String, Object> receipt = harness.service.create(AUTHOR, "author", key, valuesFor(preset));
            @SuppressWarnings("unchecked")
            Map<String, Object> binding = (Map<String, Object>) receipt.get("cityBinding");
            assertEquals("THEME_NODE_BINDING", binding.get("appliesAt"));
            @SuppressWarnings("unchecked")
            Set<String> rebinds = (Set<String>) binding.get("requiredRebinds");
            for (String required : List.of("nodeIds", "locationKey", "observedFact", "answerValues")) {
                assertTrue(rebinds.contains(required), key + " 换城必须声明要重绑：" + required);
            }
            @SuppressWarnings("unchecked")
            List<String> todos = (List<String>) receipt.get("authorTodos");
            assertFalse(todos.isEmpty(), key + " 必须告诉作者还有哪些事没做");
        }
    }

    /**
     * 秘密隔离：预设草稿里答案在（判定要用），但同一份配置经公开投影后一个答案都不剩。
     */
    @Test
    void presetDraftKeepsAnswersPrivatelyAndThePublicProjectionDropsThem() {
        Harness harness = new Harness();
        GameplayPresetCatalog.Preset preset = GameplayPresetCatalog.shared().require("T06-building-observation");
        long draftId = Long.parseLong(String.valueOf(
                harness.service.create(AUTHOR, "author", preset.presetKey(), valuesFor(preset)).get("draftId")));
        String stored = harness.drafts.get(draftId).getAdvancedConfigJson();
        assertTrue(stored.contains("\"answer\""), "分类答案必须在服务端那份里");

        String projected = AdvancedGamePublicProjection.safeConfigJson(stored);
        assertNotNull(projected);
        assertFalse(projected.contains("\"answer\""), "公开投影不得下发分类答案");
        assertFalse(projected.contains("\"correct\""), "公开投影不得下发问答正确项");
    }

    // ------------------------------------------------------------ 装配

    /** 真实发布服务 + 内存行表；不连库。 */
    private static final class Harness {
        final TemplatePublishServiceImpl publisher = new TemplatePublishServiceImpl();
        final GameplayPresetDraftService service;
        final Map<Long, CmsMemberTemplate> drafts = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(6_100L);

        Harness() {
            ICmsMemberTemplateService members = mock(ICmsMemberTemplateService.class);
            when(members.insertCmsMemberTemplate(any())).thenAnswer(call -> {
                CmsMemberTemplate row = call.getArgument(0);
                row.setId(ids.incrementAndGet());
                drafts.put(row.getId(), row);
                return 1;
            });
            when(members.updateCmsMemberTemplate(any())).thenAnswer(call -> {
                CmsMemberTemplate row = call.getArgument(0);
                drafts.put(row.getId(), row);
                return 1;
            });
            when(members.selectCmsMemberTemplateById(any())).thenAnswer(
                    call -> drafts.get((Long) call.getArgument(0)));
            CmsMemberTemplateMapper lockedMembers = mock(CmsMemberTemplateMapper.class);
            when(lockedMembers.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
                List<Long> rowIds = call.getArgument(0);
                CmsMemberTemplate row = drafts.get(rowIds.get(0));
                return row == null ? Collections.emptyList() : Collections.singletonList(row);
            });
            ICmsTemplateLibraryService libraries = mock(ICmsTemplateLibraryService.class);
            CmsTemplateLibraryMapper libraryMapper = mock(CmsTemplateLibraryMapper.class);
            when(libraryMapper.selectByMemberTemplateForUpdate(any())).thenReturn(Collections.emptyList());
            ICapabilityGuard capabilities = mock(ICapabilityGuard.class);
            when(capabilities.checkAdvancedMechanics(any(), any())).thenReturn(null);
            when(capabilities.checkDesignMedal(any())).thenReturn(null);

            ReflectionTestUtils.setField(publisher, "memberTemplateService", members);
            ReflectionTestUtils.setField(publisher, "memberTemplateMapper", lockedMembers);
            ReflectionTestUtils.setField(publisher, "templateLibraryService", libraries);
            ReflectionTestUtils.setField(publisher, "libraryMapper", libraryMapper);
            ReflectionTestUtils.setField(publisher, "capabilityGuard", capabilities);
            ReflectionTestUtils.setField(publisher, "couponRewardAuthorizationService",
                    mock(ICouponRewardAuthorizationService.class));
            ReflectionTestUtils.setField(publisher, "mediaPublicationGuard",
                    MediaPublicationTestFixtures.template(AUTHOR));
            ReflectionTestUtils.setField(publisher, "auditTaskService", mock(IAuditTaskService.class));
            ReflectionTestUtils.setField(publisher, "auditTaskMapper", mock(AuditTaskMapper.class));

            service = new GameplayPresetDraftService(publisher);
        }
    }

    /** 按预设声明生成一组合法作者值（文本用可判定但不含剧情的内容）。 */
    private static Map<String, Object> valuesFor(GameplayPresetCatalog.Preset preset) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (GameplayPresetCatalog.Field field : preset.fields()) {
            switch (field.kind()) {
                case TEXT -> values.put(field.key(), field.maxLength() <= 16 ? "测试栏目" : "作者填写的题面");
                case NUMBER -> values.put(field.key(), Math.max(field.min(), 120));
                case CHOICE -> values.put(field.key(), field.options().get(field.options().size() - 1));
                case ORDER -> values.put(field.key(), new ArrayList<>(reverse(field.options())));
                case BIN_MAP -> {
                    Map<String, String> mapping = new LinkedHashMap<>();
                    for (String item : field.items()) mapping.put(item, field.bins().get(0));
                    values.put(field.key(), mapping);
                }
            }
        }
        return values;
    }

    private static List<String> reverse(List<String> source) {
        List<String> rows = new ArrayList<>(source);
        Collections.reverse(rows);
        return rows;
    }

    private static List<String> splitOrder(Object value) {
        List<String> rows = new ArrayList<>();
        for (String id : String.valueOf(value).split(",")) rows.add(id);
        return rows;
    }

    /** 能力是否真的在支持矩阵里（types 或 addons 任一处登记）。 */
    private static boolean matrixHas(String capability) {
        JsonNode matrix = GameplaySupportMatrix.view();
        for (String bucket : List.of("types", "addons")) {
            for (JsonNode row : matrix.path(bucket)) if (capability.equals(row.path("key").asText())) return true;
        }
        return false;
    }

    private static String templateResource(String relative) {
        try (InputStream in = GameplayPresetCatalogContractTest.class
                .getResourceAsStream("/gameplay-presets/" + relative)) {
            assertNotNull(in, "缺少预设模板资源：" + relative);
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
