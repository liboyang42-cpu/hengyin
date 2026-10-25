package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采用公共库模板的秘密字段服务端回填契约。
 *
 * /api/template/info 对非 owner 剥掉 blindTaste.answerKey 与 dailySign.poems
 * (AdvancedGamePublicProjection),客户端回填出来的采用配置里这两个字段必然为空。
 * 发布路径必须由服务端从源库模板补回(backfillAdoptedSecrets),否则
 * 「闭眼味觉师」「今日城市签」一采用就撞校验器必填项,永远发布不出去。
 * 同时钉死:这不是校验豁免 —— 非采用场景照旧拦;源模板也没有就照旧报错。
 */
class TemplateAdoptSecretBackfillTest {

    /** R3 三段 + 问答多选:四处的答案都被投影剥掉,采用时必须逐字段(或按 id)补回。 */
    private static final String R3_SOURCE_CONFIG = "{\"schemaVersion\":1,"
            + "\"sort\":{\"enabled\":true,\"prompt\":\"按苦味从轻到重排\",\"xp\":20,\"maxAttempts\":3,"
            + "\"items\":[{\"id\":\"mocha\",\"label\":\"摩卡\"},{\"id\":\"latte\",\"label\":\"拿铁\"},"
            + "{\"id\":\"espresso\",\"label\":\"浓缩\"}],\"answerOrder\":[\"latte\",\"mocha\",\"espresso\"]},"
            + "\"match\":{\"enabled\":true,\"prompt\":\"把店和招牌连起来\",\"xp\":20,"
            + "\"left\":[{\"id\":\"l1\",\"label\":\"甲\"},{\"id\":\"l2\",\"label\":\"乙\"}],"
            + "\"right\":[{\"id\":\"r1\",\"label\":\"一\"},{\"id\":\"r2\",\"label\":\"二\"}],"
            + "\"pairs\":[[\"l1\",\"r2\"],[\"l2\",\"r1\"]]},"
            + "\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\",\"xp\":20,"
            + "\"bins\":[{\"id\":\"hot\",\"label\":\"热的\"},{\"id\":\"iced\",\"label\":\"冰的\"}],"
            + "\"items\":[{\"id\":\"americano\",\"label\":\"美式\"},{\"id\":\"frappe\",\"label\":\"星冰乐\"}],"
            + "\"answer\":{\"americano\":\"hot\",\"frappe\":\"iced\"}},"
            + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"multi\":true,\"title\":\"哪些是冷萃?\","
            + "\"maxTries\":3,\"xp\":20,\"options\":["
            + "{\"id\":\"a\",\"label\":\"冷萃\",\"correct\":true,\"fb\":\"对,冷萃是冷的。\"},"
            + "{\"id\":\"b\",\"label\":\"拿铁\",\"correct\":false,\"fb\":\"拿铁是热的。\"},"
            + "{\"id\":\"c\",\"label\":\"冰滴\",\"correct\":true,\"fb\":\"对,冰滴也是。\"}]}}";

    private static final String SOURCE_CONFIG = "{\"schemaVersion\":1,"
            + "\"blindTaste\":{\"enabled\":true,\"title\":\"先尝再猜\",\"xp\":15,\"answerKey\":\"B\","
            + "\"options\":[{\"key\":\"A\",\"label\":\"桂花乌龙\"},{\"key\":\"B\",\"label\":\"茉莉雪芽\"}]},"
            + "\"dailySign\":{\"enabled\":true,\"signer\":\"猫向导\",\"poems\":[[\"第一行\"]]}}";

    /** 与 AdvancedGamePublicProjection 的剥离输出同形:answerKey 键不存在、poems 键不存在。 */
    private static final String STRIPPED_CONFIG = "{\"schemaVersion\":1,"
            + "\"blindTaste\":{\"enabled\":true,\"title\":\"先尝再猜\",\"xp\":15,"
            + "\"options\":[{\"key\":\"A\",\"label\":\"桂花乌龙\"},{\"key\":\"B\",\"label\":\"茉莉雪芽\"}]},"
            + "\"dailySign\":{\"enabled\":true,\"signer\":\"猫向导\"}}";

    private ICmsTemplateLibraryService libraryService;
    private TemplatePublishServiceImpl service;
    private final AtomicReference<CmsMemberTemplate> inserted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        // 能力位闸(X03)在发布路径上,契约是「放行返回 null」—— Mockito 对 String 默认就返回 null,
        // 所以这个裸 mock 等于「四项能力位全部放行」。本用例验的是采用场景的秘密字段回填,
        // 不是能力位门禁;把闸配成放行,才不会让别的关注点混进这条断言里。
        // 能力位本身的正/负控在 CapabilityGuardImplTest,不在这里重复。
        ICapabilityGuard capabilityGuard = mock(ICapabilityGuard.class);
        service = new TemplatePublishServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        com.chengyinhub.business.mapper.CmsMemberTemplateMapper lockedMembers = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", lockedMembers);
        ReflectionTestUtils.setField(service, "libraryMapper", mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class));
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        when(lockedMembers.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
            java.util.List<Long> ids = call.getArgument(0);
            CmsMemberTemplate row = memberService.selectCmsMemberTemplateById(ids.get(0));
            return row == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(row);
        });
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        ReflectionTestUtils.setField(service, "capabilityGuard", capabilityGuard);
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(91L);
            when(memberService.selectCmsMemberTemplateById(row.getId())).thenReturn(row);
            inserted.set(row);
            return 1;
        });
    }

    @Test
    void adoptedPublishBackfillsAnswerKeyAndPoemsFromSourceTemplate() {
        CmsTemplateLibrary source = visibleSource(SOURCE_CONFIG);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);

        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);
        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));

        String saved = inserted.get().getAdvancedConfigJson();
        assertTrue(saved.contains("\"answerKey\":\"B\""), "盲品答案必须从源模板补回:" + saved);
        assertTrue(saved.contains("\"poems\""), "签文池必须从源模板补回:" + saved);
        assertTrue(saved.contains("第一行"), "补回的签文内容必须与源一致:" + saved);
    }

    @Test
    void sourceOnlyDraftCopiesAuthorConfigInsteadOfClientDefaults() throws Exception {
        CmsTemplateLibrary source = visibleSource(R3_SOURCE_CONFIG);
        source.setTitle("源玩法"); source.setDescription("旧描述不能截断");
        source.setDuration(7L); source.setActivityCategoryids("12");
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        CmsTemplatePublishRequest request = adoptedRequest(9001L, "{\"schemaVersion\":1}");
        request.setAdoptSourceOnly(true); request.setScope("MERCHANT");
        assertEquals(91L, service.saveOrUpdateDraft(request, 7L, "member7"));
        CmsMemberTemplate saved = inserted.get();
        assertEquals(new ObjectMapper().readTree(R3_SOURCE_CONFIG), new ObjectMapper().readTree(saved.getAdvancedConfigJson()));
        assertEquals("源玩法", saved.getTitle()); assertEquals(7L, saved.getDuration());
        assertEquals("12", saved.getActivityCategoryids()); assertEquals(7L, saved.getMemberId());
        assertEquals(9001L, saved.getOriginalTemplateId()); assertEquals(0L, saved.getPublishStatus());
        assertEquals("MERCHANT", request.getScope());
        assertFalse(AdvancedGamePublicProjection.safeConfigJson(saved.getAdvancedConfigJson()).contains("answerOrder"));
    }

    @Test
    void sourceOnlyDraftRejectsHiddenSourcesExistingIdsAndForeignCoupons() {
        CmsTemplateLibrary source = visibleSource(SOURCE_CONFIG);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);
        request.setAdoptSourceOnly(true); request.setId(10L);
        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        request.setId(null); source.setPublishStatus(0L);
        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        source.setPublishStatus(1L); source.setCouponId(99L);
        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        source.setCouponId(null); source.setRewardRulesJson("[{\"outcomeCode\":\"COMPLETED\",\"couponId\":99}]");
        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, 7L, "member7"));
        assertTrue(inserted.get() == null);
    }

    @Test
    void sourceOnlyAdoptionDoesNotApplyClientClearFlagsOrInventCanonicalFields() {
        CmsTemplateLibrary source = visibleSource(SOURCE_CONFIG);
        source.setTitle("完整原玩法"); source.setMerchantGuide("只给店员的说明");
        source.setRewardRulesJson("[{\"outcomeCode\":\"COMPLETED\",\"xp\":2}]");
        source.setStoryImg("media-asset:1");
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);
        request.setAdoptSourceOnly(true);
        request.setClearFields(new java.util.HashSet<>(java.util.Arrays.asList(
                "merchantGuide", "rewardRulesJson", "storyImg")));
        request.setInteractionType("client-invented");
        request.setMedalName("客户端夹带"); request.setMedalStyle("enamel");

        service.saveOrUpdateDraft(request, 7L, "member7");

        assertEquals("只给店员的说明", inserted.get().getMerchantGuide());
        assertEquals(source.getRewardRulesJson(), inserted.get().getRewardRulesJson());
        assertEquals("media-asset:1", inserted.get().getStoryImg());
        org.junit.jupiter.api.Assertions.assertNull(inserted.get().getClearFields());
        org.junit.jupiter.api.Assertions.assertNull(inserted.get().getInteractionType());
        org.junit.jupiter.api.Assertions.assertNull(inserted.get().getMedalName());
        assertEquals("glow", inserted.get().getMedalStyle());
    }

    @Test
    void sourceOnlyAdoptionReadsCanonicalInteractionAndMedalWithoutRequiringPersonalListing() {
        CmsTemplateLibrary source = visibleSource(SOURCE_CONFIG);
        source.setMemberId(99L); source.setMemberTemplateId(700L);
        CmsMemberTemplate canonical = new CmsMemberTemplate();
        canonical.setId(700L); canonical.setMemberId(99L); canonical.setStatus(1L);
        canonical.setDelFlag(0); canonical.setPublishStatus(0L);
        canonical.setInteractionType("hidden_menu");
        canonical.setMedalName("原奖章"); canonical.setMedalImg("media-asset:1");
        canonical.setMedalStyle("enamel");
        ICmsMemberTemplateService members = (ICmsMemberTemplateService)
                ReflectionTestUtils.getField(service, "memberTemplateService");
        when(members.selectCmsMemberTemplateById(700L)).thenReturn(canonical);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);
        request.setAdoptSourceOnly(true);

        service.saveOrUpdateDraft(request, 7L, "member7");

        assertEquals("hidden_menu", inserted.get().getInteractionType());
        assertEquals("原奖章", inserted.get().getMedalName());
        assertEquals("media-asset:1", inserted.get().getMedalImg());
        assertEquals("enamel", inserted.get().getMedalStyle());
        assertEquals(99L, canonical.getMemberId());
        assertEquals(0L, canonical.getPublishStatus());
    }

    @Test
    void sourceOnlyAdoptionRejectsMissingForeignDeletedOrUnapprovedCanonicalBeforeWriting() {
        CmsTemplateLibrary source = visibleSource(SOURCE_CONFIG);
        source.setMemberId(99L); source.setMemberTemplateId(700L);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        ICmsMemberTemplateService members = (ICmsMemberTemplateService)
                ReflectionTestUtils.getField(service, "memberTemplateService");
        for (String state : java.util.Arrays.asList("missing", "foreign", "deleted", "pending")) {
            CmsMemberTemplate canonical = new CmsMemberTemplate();
            canonical.setId(700L); canonical.setMemberId("foreign".equals(state) ? 98L : 99L);
            canonical.setStatus("pending".equals(state) ? 0L : 1L);
            canonical.setDelFlag("deleted".equals(state) ? 2 : 0);
            when(members.selectCmsMemberTemplateById(700L))
                    .thenReturn("missing".equals(state) ? null : canonical);
            CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);
            request.setAdoptSourceOnly(true);
            assertThrows(IllegalArgumentException.class,
                    () -> service.saveOrUpdateDraft(request, 7L, "member7"), state);
            org.junit.jupiter.api.Assertions.assertNull(inserted.get(), state);
        }
    }

    @Test
    void adoptedPublishBackfillsR3AnswersAndQaCorrectFlagsFromSourceTemplate() throws Exception {
        // 采用方的配置不是手写的,而是投影真正下发的那一份 —— 这条链路整体走一遍
        String stripped = AdvancedGamePublicProjection.safeConfigJson(R3_SOURCE_CONFIG);
        assertFalse(stripped.contains("answerOrder"), "前提:投影不下发排序答案");
        assertFalse(stripped.contains("pairs"), "前提:投影不下发连线答案");
        assertFalse(stripped.contains("\"answer\""), "前提:投影不下发分类答案");
        assertFalse(stripped.contains("\"correct\""), "前提:投影不下发问答正确答案");

        CmsTemplateLibrary source = visibleSource(R3_SOURCE_CONFIG);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);

        CmsTemplatePublishRequest request = adoptedRequest(9001L, stripped);
        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode saved = mapper.readTree(inserted.get().getAdvancedConfigJson());
        JsonNode src = mapper.readTree(R3_SOURCE_CONFIG);
        assertEquals(src.path("sort").path("answerOrder"), saved.path("sort").path("answerOrder"),
                "排序答案必须从源模板补回:" + saved);
        assertEquals(src.path("match").path("pairs"), saved.path("match").path("pairs"),
                "连线答案必须从源模板补回:" + saved);
        assertEquals(src.path("classify").path("answer"), saved.path("classify").path("answer"),
                "分类答案必须从源模板补回:" + saved);
        for (JsonNode option : src.path("qa").path("options")) {
            String id = option.path("id").asText();
            JsonNode savedOption = findOption(saved.path("qa").path("options"), id);
            assertNotNull(savedOption, "采用后问答选项必须还在:" + id);
            assertEquals(option.path("correct").asBoolean(), savedOption.path("correct").asBoolean(),
                    "选项 " + id + " 的 correct 必须与源模板一致:" + saved);
        }

        // 答案只留在服务端那一份里:同一配置再经投影,客户端依旧一个答案字段都看不到
        String projection = AdvancedGamePublicProjection.safeConfigJson(inserted.get().getAdvancedConfigJson());
        assertFalse(projection.contains("answerOrder"));
        assertFalse(projection.contains("pairs"));
        assertFalse(projection.contains("\"answer\""));
        assertFalse(projection.contains("\"correct\""));
    }

    /** R16 对照题:answer(该标哪几条)被投影剥掉,采用时必须从源模板整段补回。 */
    private static final String COMPARE_SOURCE_CONFIG = "{\"schemaVersion\":1,"
            + "\"compare\":{\"enabled\":true,\"prompt\":\"找出两版对不上的地方\",\"xp\":10,\"maxAttempts\":3,"
            + "\"left\":{\"label\":\"版本 A\",\"items\":["
            + "{\"id\":\"a1\",\"time\":\"14:00\",\"text\":\"取号\"},"
            + "{\"id\":\"a2\",\"time\":\"14:10\",\"text\":\"称重\"}]},"
            + "\"right\":{\"label\":\"版本 B\",\"items\":["
            + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"},"
            + "{\"id\":\"b2\",\"time\":\"14:15\",\"text\":\"送检\"}]},"
            + "\"answer\":[\"a2\",\"b2\"]}}";

    @Test
    void adoptedPublishBackfillsCompareAnswerFromSourceTemplate() throws Exception {
        // 采用方的配置不是手写的,而是投影真正下发的那一份 —— 这条链路整体走一遍
        String stripped = AdvancedGamePublicProjection.safeConfigJson(COMPARE_SOURCE_CONFIG);
        assertFalse(stripped.contains("\"answer\""), "前提:投影不下发对照答案");

        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(visibleSource(COMPARE_SOURCE_CONFIG));

        CmsTemplatePublishRequest request = adoptedRequest(9001L, stripped);
        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode saved = mapper.readTree(inserted.get().getAdvancedConfigJson());
        assertEquals(mapper.readTree(COMPARE_SOURCE_CONFIG).path("compare").path("answer"),
                saved.path("compare").path("answer"), "对照答案必须从源模板补回:" + saved);
        // 答案只留在服务端那一份里:同一配置再经投影,客户端依旧看不到
        String projection = AdvancedGamePublicProjection.safeConfigJson(inserted.get().getAdvancedConfigJson());
        assertFalse(projection.contains("\"answer\""), "答案不该出现在下发里:" + projection);
    }

    private JsonNode findOption(JsonNode options, String id) {
        for (JsonNode option : options) {
            if (id.equals(option.path("id").asText())) return option;
        }
        return null;
    }

    @Test
    void scratchPublishWithMissingAnswerKeyStillFailsValidation() {
        // 负控:回填只服务采用场景。白手起家缺 answerKey 依旧是校验错误,不能被顺手放过。
        CmsTemplatePublishRequest request = adoptedRequest(null, STRIPPED_CONFIG);

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTemplateWithTransaction(request, 7L, "member7"));
    }

    @Test
    void adoptedPublishStillFailsWhenSourceHasNoSecretsEither() {
        // 源模板自己也没配答案:补不回来就照旧报错,不许静默吞成"看起来发布成功"。
        CmsTemplateLibrary source = visibleSource(STRIPPED_CONFIG);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);

        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTemplateWithTransaction(request, 7L, "member7"));
    }

    @Test
    void adoptedPublishRefusesToBackfillFromInvisibleSource() {
        // originalTemplateId 是客户端提交的:指向未过审/已下架/已删除的库行时绝不回填,
        // 否则这里就是绕过 TemplateSecrets.strip 的秘密窃取路径。不可见 ⇒ 不回填 ⇒ 照常撞必填校验。
        long[] ids = {9101L, 9102L, 9103L, 9104L};
        CmsTemplateLibrary unpublished = visibleSource(SOURCE_CONFIG);
        unpublished.setPublishStatus(0L);
        CmsTemplateLibrary disabled = visibleSource(SOURCE_CONFIG);
        disabled.setStatus(0L);
        CmsTemplateLibrary deleted = visibleSource(SOURCE_CONFIG);
        deleted.setDelFlag(2);
        CmsTemplateLibrary nullFields = new CmsTemplateLibrary();
        nullFields.setId(9104L);
        nullFields.setAdvancedConfigJson(SOURCE_CONFIG);
        when(libraryService.selectCmsTemplateLibraryById(9101L)).thenReturn(unpublished);
        when(libraryService.selectCmsTemplateLibraryById(9102L)).thenReturn(disabled);
        when(libraryService.selectCmsTemplateLibraryById(9103L)).thenReturn(deleted);
        when(libraryService.selectCmsTemplateLibraryById(9104L)).thenReturn(nullFields);

        for (long id : ids) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.publishTemplateWithTransaction(adoptedRequest(id, STRIPPED_CONFIG), 7L, "member7"),
                    "不可见源 id=" + id + " 必须拒绝回填");
        }
    }

    /**
     * R22-SEC S5:回填只兜 JSON 解析 —— 源模板查询的 DB 故障必须原样抛,
     * 不能被 catch(Exception) 吞成「JSON 不合法」再伪装成校验错误。
     *
     * <p>负控:把 try 范围恢复到包住 selectCmsTemplateLibraryById,本用例立刻红
     * (抛出的是 IllegalArgumentException,不是 DB 异常)。
     */
    @Test
    void adoptedPublishPropagatesSourceLookupFailure() {
        when(libraryService.selectCmsTemplateLibraryById(9001L))
                .thenThrow(new BadSqlGrammarException("select", "cms_template_library",
                        new SQLException("table is gone")));

        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG);

        assertThrows(BadSqlGrammarException.class,
                () -> service.publishTemplateWithTransaction(request, 7L, "member7"));
    }

    @Test
    void ownedAdoptedDraftBackfillsOnlyItsStoredAnswersAfterPublicSourceDrifts() throws Exception {
        ICmsMemberTemplateService members = (ICmsMemberTemplateService)
                ReflectionTestUtils.getField(service, "memberTemplateService");
        CmsMemberTemplate owned = new CmsMemberTemplate();
        owned.setId(71L); owned.setMemberId(7L); owned.setDelFlag(0); owned.setOriginalTemplateId(9001L);
        String ownedConfig = SOURCE_CONFIG.replace("\"answerKey\":\"B\"", "\"answerKey\":\"A\"");
        owned.setAdvancedConfigJson(ownedConfig);
        when(members.selectCmsMemberTemplateById(71L)).thenReturn(owned);
        when(members.updateCmsMemberTemplate(any())).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        when(libraryService.selectCmsTemplateLibraryById(9001L))
                .thenThrow(new IllegalStateException("live 来源不可用或已被修改"));
        CmsTemplatePublishRequest request = adoptedRequest(9001L, STRIPPED_CONFIG); request.setId(71L);
        service.saveOrUpdateDraft(request, 7L, "member7");
        JsonNode saved = new ObjectMapper().readTree(inserted.get().getAdvancedConfigJson());
        assertEquals("A", saved.path("blindTaste").path("answerKey").asText());
        assertEquals("第一行", saved.path("dailySign").path("poems").get(0).get(0).asText());
        org.mockito.Mockito.verify(libraryService, org.mockito.Mockito.never()).selectCmsTemplateLibraryById(any());
        assertEquals(ownedConfig, owned.getAdvancedConfigJson());
    }

    private CmsTemplateLibrary visibleSource(String configJson) {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(9001L);
        source.setAdvancedConfigJson(configJson);
        source.setDelFlag(0);
        source.setStatus(1L);
        source.setPublishStatus(1L);
        return source;
    }

    @Test
    void adoptedD20RestoresOnlyMissingResultsFromVisibleSource() throws Exception {
        String sourceConfig = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\","
                + "\"dc\":15,\"modifier\":2,\"rollMode\":\"normal\","
                + "\"successText\":\"发现暗门\",\"failText\":\"绕路前往下一站\"}}";
        String stripped = AdvancedGamePublicProjection.safeConfigJson(sourceConfig);
        assertFalse(stripped.contains("发现暗门"));
        assertFalse(stripped.contains("绕路前往下一站"));
        CmsTemplateLibrary source = visibleSource(sourceConfig);
        when(libraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(source);
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode draft =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(stripped);
        ((com.fasterxml.jackson.databind.node.ObjectNode) draft.get("diceRoll"))
                .put("successText", "作者改写的成功结果");
        service.publishTemplateWithTransaction(adoptedRequest(9001L, draft.toString()), 7L, "member7");
        JsonNode saved = mapper.readTree(inserted.get().getAdvancedConfigJson()).path("diceRoll");
        assertEquals("作者改写的成功结果", saved.path("successText").asText());
        assertEquals("绕路前往下一站", saved.path("failText").asText());
        source.setPublishStatus(0L);
        assertThrows(IllegalArgumentException.class,
                () -> service.publishTemplateWithTransaction(adoptedRequest(9001L, stripped), 7L, "member7"));
    }

    private CmsTemplatePublishRequest adoptedRequest(Long originalTemplateId, String configJson) {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("从货架采用的玩法");
        request.setOriginalTemplateId(originalTemplateId);
        request.setAdvancedConfigJson(configJson);
        return request;
    }
}
