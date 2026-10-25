package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.service.ICapabilityGuard;
/**
 * 题面媒体（题干配图/配音 + 选项媒体）在发布链路上的落点。
 *
 * <p>★ 这组测试存在的理由：个人玩法过审后经 market-submit 复制为模板广场投影时，
 * 题干媒体和玩法 JSON 不能静默丢失。
 */
class TemplatePublishQuestionMediaTest
{
    private final AtomicReference<CmsMemberTemplate> memberRow = new AtomicReference<>();
    private final AtomicReference<CmsTemplateLibrary> libraryRow = new AtomicReference<>();

    private TemplatePublishServiceImpl service()
    {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        com.chengyinhub.business.mapper.CmsMemberTemplateMapper memberMapper =
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", memberMapper);
        com.chengyinhub.business.mapper.CmsTemplateLibraryMapper libraries = mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class);
        ReflectionTestUtils.setField(service, "libraryMapper", libraries);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        when(libraries.selectByMemberTemplateForUpdate(any())).thenAnswer(call -> libraryRow.get() == null
                ? java.util.Collections.emptyList() : java.util.Collections.singletonList(libraryRow.get()));
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(91L);
            memberRow.set(row);
            return 1;
        });
        when(memberService.selectCmsMemberTemplateById(any())).thenAnswer(invocation -> memberRow.get());
        when(memberMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(invocation ->
                memberRow.get() == null ? java.util.Collections.emptyList()
                        : java.util.Collections.singletonList(memberRow.get()));
        when(libraryService.insertCmsTemplateLibrary(any())).thenAnswer(invocation -> {
            CmsTemplateLibrary row = invocation.getArgument(0);
            row.setId(191L);
            libraryRow.set(row);
            return 1;
        });
        when(auditService.submitOrRefreshTemplateLibrary(any(), any(), any())).thenReturn(301L);
        return service;
    }

    private void publishAndSubmitMarket(CmsTemplatePublishRequest request)
    {
        memberRow.set(null); libraryRow.set(null);
        TemplatePublishServiceImpl service = service();
        service.publishTemplateWithTransaction(request, 7L, "member7");
        CmsMemberTemplate member = memberRow.get();
        member.setDraftStatus(1);
        member.setStatus(1L);
        member.setCheckStatus(1);
        member.setPublishStatus(1L);
        service.submitTemplateToMarket(member.getId(), 7L, "member7");
    }

    private CmsTemplatePublishRequest request()
    {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("听音辨路");
        request.setIsSync(1);
        request.setValidationMethod(3);
        request.setQuestionName("这段录音里的是哪座桥的钟声？");
        request.setQuestionA("外白渡桥");
        request.setQuestionB("四川路桥");
        request.setCorrectAnswer("A");
        request.setQuestionImg("media-asset:1");
        request.setQuestionAudio("media-asset:2");
        request.setQuestionOptionMediaJson("{\"A\":{\"img\":\"media-asset:3\"}}");
        return request;
    }

    @Test
    void bothMemberTemplateAndLibraryReceiveQuestionMedia()
    {
        publishAndSubmitMarket(request());

        CmsMemberTemplate member = memberRow.get();
        assertNotNull(member, "我的模板必须落库");
        assertEquals("media-asset:1", member.getQuestionImg());
        assertEquals("media-asset:2", member.getQuestionAudio());
        assertEquals("{\"A\":{\"img\":\"media-asset:3\"}}", member.getQuestionOptionMediaJson());

        CmsTemplateLibrary library = libraryRow.get();
        assertNotNull(library, "isSync=1 必须同步进模板库");
        assertEquals("media-asset:1", library.getQuestionImg(),
                "模板库漏字段 = 发布即丢，且没有任何报错");
        assertEquals("media-asset:2", library.getQuestionAudio());
        assertEquals("{\"A\":{\"img\":\"media-asset:3\"}}", library.getQuestionOptionMediaJson());
    }

    @Test
    void advancedGameConfigurationReachesCanonicalProjectionAndDraft()
    {
        CmsTemplatePublishRequest request = request();
        request.setAdvancedConfigJson("{\n\"schemaVersion\":1,\"timer\":{" +
                "\"enabled\":true,\"durationSeconds\":90,\"timeoutResult\":\"FAILED\"}}\n");

        publishAndSubmitMarket(request);

        String expected = "{\"schemaVersion\":1,\"timer\":{" +
                "\"enabled\":true,\"durationSeconds\":90,\"timeoutResult\":\"FAILED\"}}";
        assertEquals(expected, memberRow.get().getAdvancedConfigJson());
        assertEquals(expected, libraryRow.get().getAdvancedConfigJson(),
                "同步到模板库时不能静默丢失高级玩法配置");

        memberRow.set(null);
        libraryRow.set(null);
        request.setIsSync(0);
        service().saveOrUpdateDraft(request, 7L, "member7");
        assertEquals(expected, memberRow.get().getAdvancedConfigJson(), "草稿也必须完整保留配置");
        assertNull(libraryRow.get());
    }

    @Test
    void interactionAndCompletePresentationReachCanonicalMarketAndTheirAuditContent() throws Exception
    {
        CmsTemplatePublishRequest request = request();
        request.setInteractionType("city_story_card"); request.setMerchantGuide("店员说明");
        request.setFeedbackText("完成反馈"); request.setMedalImg("media-asset:1");
        request.setMedalName("城市印记"); request.setMedalStyle("enamel");
        request.setRewardRulesJson("[{\"outcomeCode\":\"COMPLETED\",\"xp\":2,\"feedbackText\":\"条件反馈\"}]");
        request.setStoryText("剧情"); request.setStoryImg("media-asset:1");
        request.setStoryJson("[{\"text\":\"故事节点\",\"tag\":\"提示\"}]");
        request.setHint1("一级提示"); request.setHint2("二级提示"); request.setAnswerReveal("解析");
        request.setPhotoRequireDesc("拍到标识"); request.setPhotoReview(1);
        request.setAudioUrl("media-asset:2"); request.setAudioDuration(12);
        publishAndSubmitMarket(request);
        for (String field : new String[]{"interactionType", "merchantGuide", "feedbackText", "rewardRulesJson",
                "storyText", "storyImg", "storyJson", "hint1", "hint2", "answerReveal",
                "photoRequireDesc", "photoReview", "audioUrl", "audioDuration"}) {
            Object expected = new org.springframework.beans.BeanWrapperImpl(request).getPropertyValue(field);
            assertEquals(expected, new org.springframework.beans.BeanWrapperImpl(memberRow.get()).getPropertyValue(field), field);
            assertEquals(expected, new org.springframework.beans.BeanWrapperImpl(libraryRow.get()).getPropertyValue(field), field);
        }
        assertEquals("城市印记", memberRow.get().getMedalName());
        assertEquals("media-asset:1", memberRow.get().getMedalImg());
        assertEquals("enamel", memberRow.get().getMedalStyle());
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("city_story_card", json.readTree(com.chengyinhub.business.service.support.TemplateAuditSnapshot
                .personal(memberRow.get())).path("interactionType").asText());
        String market = com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(libraryRow.get());
        assertEquals("city_story_card", json.readTree(market).path("interactionType").asText());
        libraryRow.get().setInteractionType("hidden_menu");
        assertEquals(false, com.chengyinhub.business.service.support.TemplateAuditSnapshot.matches(market,
                com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(libraryRow.get())));
    }

    @Test
    void interactionIsRequestContentButReadOnlyInPublicMarketProjection() throws Exception
    {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        CmsTemplatePublishRequest left = request(); CmsTemplatePublishRequest right = request();
        left.setInteractionType("hidden_menu"); right.setInteractionType("city_story_card");
        assertEquals(false, left.equals(right));
        assertEquals(false, left.hashCode() == right.hashCode());
        assertEquals("hidden_menu", json.readValue("{\"interactionType\":\"hidden_menu\"}",
                CmsTemplatePublishRequest.class).getInteractionType());
        assertNull(json.readValue("{\"interactionType\":\"client-invented\"}", CmsTemplateLibrary.class).getInteractionType());
        CmsTemplateLibrary source = new CmsTemplateLibrary(); source.setInteractionType("hidden_menu");
        assertEquals("hidden_menu", json.readTree(json.writeValueAsString(source)).path("interactionType").asText());
    }

    @Test
    void malformedAdvancedGameConfigurationIsRejectedBeforeAnyRowIsWritten()
    {
        CmsTemplatePublishRequest request = request();
        request.setAdvancedConfigJson("{\"schemaVersion\":1,\"random\":{" +
                "\"enabled\":true,\"drawCount\":2,\"items\":[{" +
                "\"id\":\"only\",\"label\":\"唯一\",\"weight\":1}]}}" );

        assertThrows(IllegalArgumentException.class,
                () -> service().publishTemplateWithTransaction(request, 7L, "member7"));
        assertNull(memberRow.get());
        assertNull(libraryRow.get());
    }

    @Test
    void bothMappersContainAdvancedConfigurationReadInsertAndUpdateBindings() throws Exception
    {
        assertMapperAdvancedBindings("mapper/business/CmsMemberTemplateMapper.xml");
        assertMapperAdvancedBindings("mapper/business/CmsTemplateLibraryMapper.xml");
    }

    private void assertMapperAdvancedBindings(String resource) throws Exception
    {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);
            String xml = new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
            assertEquals(true, xml.contains("property=\"advancedConfigJson\""));
            assertEquals(true, xml.contains("advanced_config_json,"));
            assertEquals(true, xml.contains("advanced_config_json = #{advancedConfigJson}"));
        }
    }

    @Test
    void preferenceConfigurationReachesBothMemberAndLibraryRows()
    {
        CmsTemplatePublishRequest request = request();
        request.setValidationMethod(6);
        request.setPreferenceJson("{\"steps\":[{\"key\":\"pace\",\"type\":\"single\","
                + "\"title\":\"选一个\",\"options\":[{\"key\":\"A\",\"text\":\"慢\","
                + "\"scores\":{\"slow\":1}},{\"key\":\"B\",\"text\":\"稳\","
                + "\"scores\":{\"slow\":2}}]}],\"dimensions\":[\"slow\"],"
                + "\"results\":{\"slow\":{\"title\":\"慢生活\","
                + "\"body\":\"因为你选择了{{choices}}\",\"nextStep\":\"散步\","
                + "\"nextStepDays\":7}}}");

        publishAndSubmitMarket(request);

        assertEquals(request.getPreferenceJson(), memberRow.get().getPreferenceJson());
        assertEquals(request.getPreferenceJson(), libraryRow.get().getPreferenceJson(),
                "同步到模板库时不能静默丢失 validationMethod=6 的题组配置");
    }

    @Test
    void libraryMapperContainsPreferenceReadInsertAndUpdateBindings() throws Exception
    {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("mapper/business/CmsTemplateLibraryMapper.xml")) {
            assertNotNull(in);
            String xml = new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
            assertEquals(true, xml.contains("property=\"preferenceJson\""));
            assertEquals(true, xml.contains("preference_json,"));
            assertEquals(true, xml.contains("preference_json = #{preferenceJson}"));
        }
    }

    @Test
    void preferenceConfigurationParticipatesInPublishRequestIdentity()
    {
        CmsTemplatePublishRequest left = request();
        CmsTemplatePublishRequest right = request();
        left.setPreferenceJson("{\"questions\":[]}");
        right.setPreferenceJson("{\"questions\":[{\"id\":\"pace\"}]}");

        assertEquals(false, left.equals(right));
        assertEquals(false, left.hashCode() == right.hashCode());
    }

    @Test
    void nonPreferenceTemplateNeverWritesAnEmptyStringIntoJsonColumns()
    {
        CmsTemplatePublishRequest request = request();
        request.setValidationMethod(3);
        request.setPreferenceJson("");

        publishAndSubmitMarket(request);

        assertNull(memberRow.get().getPreferenceJson(), "MySQL JSON 列不接受空串");
        assertNull(libraryRow.get().getPreferenceJson(), "同步模板库也必须规范化非 VM6 的空串");
    }

    @Test
    void bothMappersExplicitlyClearPreferenceWhenValidationMethodChangesAwayFromVm6() throws Exception
    {
        assertMapperPreferenceClearBinding("mapper/business/CmsMemberTemplateMapper.xml");
        assertMapperPreferenceClearBinding("mapper/business/CmsTemplateLibraryMapper.xml");
    }

    /**
     * ★ 清空必须真能清掉。mapper 用 {@code <if test="... != null">} 决定这一列写不写，
     * 所以「显式清空」要落成空串而不是 null —— 落 null 的话 update 会跳过该列，
     * 「选项题 → 改成拍照 → 再改回选项题」时旧的选项图会复活。
     * 这条正是先前负控没打到的路径：老测试只覆盖 insert，看不出 update 的差别。
     */
    @Test
    void explicitlyClearedMediaBecomesEmptyStringSoUpdateActuallyWipesTheColumn()
    {
        // ⚠️ 用空串而不是 "{}" —— 小程序 prepareFormData 清空时发的就是空串,
        //    盯在 "{}" 上会让「空串分支退回返回 null」这种回归从这条测试底下溜过去
        //    (2026-08-16 负控实测:改坏空串分支,这条测试当时仍然是绿的)。
        CmsTemplatePublishRequest request = request();
        request.setQuestionImg("");
        request.setQuestionAudio("");
        request.setQuestionOptionMediaJson("");

        publishAndSubmitMarket(request);

        assertEquals("", memberRow.get().getQuestionOptionMediaJson(),
                "空串才会进 update 的 set 子句；null 会被跳过，旧值留在库里");
        assertEquals("", libraryRow.get().getQuestionOptionMediaJson());
    }

    /** 字段压根没传时保持 null —— 那是「不动库里的值」，与清空是两回事。 */
    @Test
    void absentMediaFieldStaysNullSoTheColumnIsLeftAlone()
    {
        CmsTemplatePublishRequest request = request();
        request.setQuestionOptionMediaJson(null);

        publishAndSubmitMarket(request);

        assertNull(memberRow.get().getQuestionOptionMediaJson());
        assertNull(libraryRow.get().getQuestionOptionMediaJson());
    }

    /** 畸形选项媒体必须在写入前被拦下，不能靠 varchar 截断或 MySQL 报错兜底。 */
    @Test
    void malformedOptionMediaIsRejectedBeforeAnyRowIsWritten()
    {
        CmsTemplatePublishRequest request = request();
        request.setQuestionOptionMediaJson("{\"E\":{\"img\":\"https://cdn/e.jpg\"}}");

        assertThrows(IllegalArgumentException.class,
                () -> service().publishTemplateWithTransaction(request, 7L, "member7"));
    }

    @Test
    void appSensorFieldsReachBothMemberAndLibraryRows()
    {
        CmsTemplatePublishRequest request = request();
        request.setValidationMethod(7);
        request.setSensorType("steps");
        request.setSensorConfig("{\"targetSteps\":120,\"windowSec\":300}");

        publishAndSubmitMarket(request);

        assertEquals("steps", memberRow.get().getSensorType());
        assertEquals("{\"targetSteps\":120,\"windowSec\":300}", memberRow.get().getSensorConfig());
        assertEquals("steps", libraryRow.get().getSensorType());
        assertEquals("{\"targetSteps\":120,\"windowSec\":300}", libraryRow.get().getSensorConfig());
    }

    @Test
    void photoFilterFieldsReachBothMemberAndLibraryRows()
    {
        for (String style : new String[] {"night_vision", "pet_pov"}) {
            CmsTemplatePublishRequest request = request();
            request.setValidationMethod(2);
            request.setSensorType("filter_shot");
            request.setSensorConfig("{\"filterStyle\":\"" + style + "\"}");

            publishAndSubmitMarket(request);

            assertEquals("filter_shot", memberRow.get().getSensorType());
            assertEquals("{\"filterStyle\":\"" + style + "\"}", memberRow.get().getSensorConfig());
            assertEquals("filter_shot", libraryRow.get().getSensorType());
            assertEquals("{\"filterStyle\":\"" + style + "\"}", libraryRow.get().getSensorConfig());
        }
    }

    @Test
    void photoFilterRejectsUnknownOrMismatchedConfigurationBeforeWrite()
    {
        for (String[] invalid : new String[][] {
                {"filter_shot", "{}"},
                {"filter_shot", "{\"filterStyle\":\"unknown\"}"},
                {"steps", "{\"targetSteps\":120,\"windowSec\":300}"}
        }) {
            CmsTemplatePublishRequest request = request();
            request.setValidationMethod(2);
            request.setSensorType(invalid[0]);
            request.setSensorConfig(invalid[1]);

            assertThrows(IllegalArgumentException.class,
                    () -> service().publishTemplateWithTransaction(request, 7L, "member7"));
        }
        assertNull(memberRow.get());
        assertNull(libraryRow.get());
    }

    @Test
    void stepsTemplateRequiresBoundedWindowBeforePublish()
    {
        for (String config : new String[] {"{\"targetSteps\":120}",
                "{\"targetSteps\":120,\"windowSec\":86401}"}) {
            CmsTemplatePublishRequest request = request();
            request.setValidationMethod(7);
            request.setSensorType("steps");
            request.setSensorConfig(config);
            assertThrows(IllegalArgumentException.class,
                    () -> service().publishTemplateWithTransaction(request, 7L, "member7"), config);
        }
        assertNull(memberRow.get());
        assertNull(libraryRow.get());
    }

    @Test
    void bothMapperDefinitionsContainSensorReadInsertAndUpdateBindings() throws Exception
    {
        assertMapperSensorBindings("mapper/business/CmsMemberTemplateMapper.xml");
        assertMapperSensorBindings("mapper/business/CmsTemplateLibraryMapper.xml");
    }

    @Test
    void publicCategoryShelfReadsPhotoFilterContract() throws Exception
    {
        String resource = "mapper/business/CmsTemplateLibraryMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            String xml = new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
            int start = xml.indexOf("<select id=\"selectTemplatesByCategoryIds\"");
            int end = xml.indexOf("</select>", start);
            assertEquals(true, start >= 0 && end > start, "公开模板货架 SQL 必须存在");
            String shelfQuery = xml.substring(start, end);
            assertEquals(true, shelfQuery.contains("mt.sensor_type"));
            assertEquals(true, shelfQuery.contains("mt.sensor_config"));
        }
    }

    @Test
    void vm7RejectsRemovedOrMalformedSensorConfigurationBeforeWrite()
    {
        CmsTemplatePublishRequest removed = request();
        removed.setValidationMethod(7);
        removed.setSensorType("filter_shot");
        removed.setSensorConfig("{}");
        assertThrows(IllegalArgumentException.class,
                () -> service().publishTemplateWithTransaction(removed, 7L, "member7"));

        CmsTemplatePublishRequest malformed = request();
        malformed.setValidationMethod(7);
        malformed.setSensorType("still");
        malformed.setSensorConfig("not-json");
        assertThrows(IllegalArgumentException.class,
                () -> service().publishTemplateWithTransaction(malformed, 7L, "member7"));

        CmsTemplatePublishRequest missingTarget = request();
        missingTarget.setValidationMethod(7);
        missingTarget.setSensorType("still");
        missingTarget.setSensorConfig("{}");
        assertThrows(IllegalArgumentException.class,
                () -> service().publishTemplateWithTransaction(missingTarget, 7L, "member7"));
    }

    @Test
    void audioClipRequiresReachableDurationThresholdBeforeWrite()
    {
        for (String config : new String[] {"{}", "{\"minDurationSec\":-1}", "{\"minDurationSec\":61}"}) {
            CmsTemplatePublishRequest invalid = request();
            invalid.setValidationMethod(7);
            invalid.setSensorType("audio_clip");
            invalid.setSensorConfig(config);
            assertThrows(IllegalArgumentException.class,
                    () -> service().publishTemplateWithTransaction(invalid, 7L, "member7"), config);
        }
    }

    @Test
    void nonSensorTemplateNormalizesLegacySensorFieldsAway()
    {
        CmsTemplatePublishRequest request = request();
        request.setValidationMethod(3);
        request.setSensorType("");
        request.setSensorConfig("");

        publishAndSubmitMarket(request);

        assertNull(memberRow.get().getSensorType());
        assertNull(memberRow.get().getSensorConfig());
        assertNull(libraryRow.get().getSensorType());
        assertNull(libraryRow.get().getSensorConfig());
    }

    private void assertMapperSensorBindings(String resource) throws Exception
    {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            String xml = new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
            assertEquals(true, xml.contains("property=\"sensorType\"") && xml.contains("property=\"sensorConfig\""));
            assertEquals(true, xml.contains("sensor_type,") && xml.contains("sensor_config,"));
            assertEquals(true, xml.contains("sensor_type = #{sensorType}") && xml.contains("sensor_config = #{sensorConfig}"));
            assertEquals(true, xml.contains("sensor_type = NULL") && xml.contains("sensor_config = NULL"));
        }
    }

    private void assertMapperPreferenceClearBinding(String resource) throws Exception
    {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            String xml = new String(org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
            assertEquals(true, xml.contains("validationMethod != 6")
                    && xml.contains("preferenceJson.trim() == ''")
                    && xml.contains("preference_json = NULL"),
                    resource + " 必须在 VM6 切到普通题型时清掉旧配置");
        }
    }
}
