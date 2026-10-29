package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Arrays;
import java.util.HashSet;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-A:商家要能改自己那份玩法模板。
 *
 * <p>承接主题模板时 CmsTopicServiceImpl#copyTemplateForOwner 已经把节点模板深拷贝给了新归属,
 * 但在这条改动之前发布链路只 INSERT,商家拿到属于自己的模板却没有任何入口能改题面与答案 ——
 * 只能重建一个再换上去。本测试钉住「带 id 即改写自己那份」以及它的归属闸。
 */
class TemplateEditOwnershipTest {

    private static final Long OWNER = 100L;
    private static final Long STRANGER = 200L;
    private static final Long TEMPLATE_ID = 77L;

    private ICmsMemberTemplateService memberTemplateService;
    private ICmsTemplateLibraryService templateLibraryService;
    private IAuditTaskService auditTaskService;
    private ICapabilityGuard capabilityGuard;
    private TemplatePublishServiceImpl service;
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper memberMapper;
    private com.chengyinhub.business.mapper.CmsTemplateLibraryMapper libraryMapper;
    private com.chengyinhub.business.mapper.AuditTaskMapper taskMapper;

    @BeforeEach
    void setUp() {
        memberTemplateService = mock(ICmsMemberTemplateService.class);
        templateLibraryService = mock(ICmsTemplateLibraryService.class);
        auditTaskService = mock(IAuditTaskService.class);
        capabilityGuard = mock(ICapabilityGuard.class);

        service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        memberMapper = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        libraryMapper = mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class);
        taskMapper = mock(com.chengyinhub.business.mapper.AuditTaskMapper.class);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", memberMapper);
        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        when(memberMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(inv -> {
            Long id = ((java.util.List<Long>) inv.getArgument(0)).get(0);
            CmsMemberTemplate row = memberTemplateService.selectCmsMemberTemplateById(id);
            return row == null ? Collections.emptyList() : Collections.singletonList(row);
        });
        when(libraryMapper.selectByMemberTemplateForUpdate(anyLong())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(service, "templateLibraryService", templateLibraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(service, "capabilityGuard", capabilityGuard);

        when(capabilityGuard.checkAdvancedMechanics(anyLong(), any())).thenReturn(null);
        when(capabilityGuard.checkDesignMedal(anyLong())).thenReturn(null);
        when(memberTemplateService.updateCmsMemberTemplate(any())).thenAnswer(inv -> {
            CmsMemberTemplate row = inv.getArgument(0);
            when(memberTemplateService.selectCmsMemberTemplateById(row.getId())).thenReturn(row);
            return 1;
        });
        when(memberTemplateService.insertCmsMemberTemplate(any())).thenAnswer(inv -> {
            CmsMemberTemplate row = inv.getArgument(0); row.setId(99L);
            when(memberTemplateService.selectCmsMemberTemplateById(99L)).thenReturn(row);
            return 1;
        });
        when(templateLibraryService.selectCmsTemplateLibraryList(any()))
                .thenReturn(Collections.<CmsTemplateLibrary>emptyList());
    }

    @Test
    void merchantEndpointCannotReviveDeletedOrApproveOrdinaryTemplate() {
        CmsMemberTemplate input = new CmsMemberTemplate(); input.setId(TEMPLATE_ID);
        CmsMemberTemplate deleted = existing(OWNER, 2); deleted.setMerchantNodeEnabled(1);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(deleted);
        assertThrows(RuntimeException.class, () -> service.saveMerchantNodeTemplate(input, OWNER, "商家"));
        CmsMemberTemplate ordinary = existing(OWNER, 0); ordinary.setMerchantNodeEnabled(0);
        ordinary.setStatus(0L);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(ordinary);
        assertThrows(RuntimeException.class, () -> service.saveMerchantNodeTemplate(input, OWNER, "商家"));
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void merchantEditWithdrawsMarketProjectionAndIgnoresClientLifecycleFields() {
        CmsMemberTemplate existing = existing(OWNER, 0); existing.setMerchantNodeEnabled(1);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing);
        CmsTemplateLibrary projection = new CmsTemplateLibrary(); projection.setId(171L);
        projection.setMemberTemplateId(TEMPLATE_ID); projection.setDelFlag(0);
        projection.setStatus(1L); projection.setPublishStatus(1L);
        when(libraryMapper.selectByMemberTemplateForUpdate(TEMPLATE_ID)).thenReturn(Collections.singletonList(projection));
        when(templateLibraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
        CmsMemberTemplate input = new CmsMemberTemplate(); input.setId(TEMPLATE_ID);
        input.setTitle("新据点玩法"); input.setStatus(2L); input.setCheckStatus(2); input.setDelFlag(2);
        service.saveMerchantNodeTemplate(input, OWNER, "商家");
        ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
        assertEquals(0, saved.getValue().getDelFlag());
        assertEquals(1, saved.getValue().getCheckStatus());
        assertEquals(0L, projection.getPublishStatus());
        verify(taskMapper).cancelOpenTemplateTasks("TEMPLATE_LIBRARY", 171L);
        verify(taskMapper).cancelOpenTemplateTasks("TEMPLATE", TEMPLATE_ID);
    }

    /**
     * AUDIT-INT-9(#384):撤销在途模板审核单之前,先把该条目的未结申诉以 WITHDRAWN 结案并通知作者
     * (与 AUDIT-INT-6 同口径:作者改内容 = 放弃申诉);cancelOpenTemplateTasks 本身不再碰申诉单。
     */
    @Test
    void editingTemplateWithdrawsOpenAppealsBeforeCancellingTasks() {
        CmsMemberTemplate existing = existing(OWNER, 0); existing.setMerchantNodeEnabled(1);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing);
        CmsTemplateLibrary projection = new CmsTemplateLibrary(); projection.setId(171L);
        projection.setMemberTemplateId(TEMPLATE_ID); projection.setDelFlag(0);
        projection.setStatus(1L); projection.setPublishStatus(1L);
        when(libraryMapper.selectByMemberTemplateForUpdate(TEMPLATE_ID)).thenReturn(Collections.singletonList(projection));
        when(templateLibraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
        CmsMemberTemplate input = new CmsMemberTemplate(); input.setId(TEMPLATE_ID);
        input.setTitle("新据点玩法");

        service.saveMerchantNodeTemplate(input, OWNER, "商家");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(auditTaskService, taskMapper);
        order.verify(auditTaskService).withdrawOpenAppeal(eq("TEMPLATE_LIBRARY"), eq(171L), anyString());
        order.verify(taskMapper).cancelOpenTemplateTasks("TEMPLATE_LIBRARY", 171L);
        order.verify(auditTaskService).withdrawOpenAppeal(eq("TEMPLATE"), eq(TEMPLATE_ID), anyString());
        order.verify(taskMapper).cancelOpenTemplateTasks("TEMPLATE", TEMPLATE_ID);
    }

    /** 最小可发布请求:只填必要字段,其余走默认。 */
    private CmsTemplatePublishRequest request(Long id) {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setId(id);
        request.setTitle("店里的暗号");
        request.setDescription("文字暗号题");
        request.setValidationMethod(1);
        request.setQuestionName("墙上那个年份是多少?");
        request.setQuestionAnswer("1997");
        request.setIsSync(0);
        return request;
    }

    @Test
    void clearFieldsAreValidatedAndCopiedForDraftAndPublish() {
        for (boolean draft : new boolean[]{true, false}) {
            setUp();
            when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing(OWNER, 0));
            CmsTemplatePublishRequest request = request(TEMPLATE_ID);
            request.setClearFields(new HashSet<>(Arrays.asList("rewardRulesJson", "merchantGuide", "sensorType", "sensorConfig", "storyImg")));
            request.setRewardRulesJson("invalid but explicitly cleared");
            request.setMerchantGuide("old guide"); request.setStoryImg("old image");
            if (draft) service.saveOrUpdateDraft(request, OWNER, "作者");
            else service.publishTemplateWithTransaction(request, OWNER, "作者");
            ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
            verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
            assertEquals(request.getClearFields(), saved.getValue().getClearFields());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getRewardRulesJson());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getMerchantGuide());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getStoryImg());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getSensorType());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getSensorConfig());
        }
    }

    @Test
    void missingClearFieldsDoNotSetTheInternalUpdateMarker() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing(OWNER, 0));
        CmsTemplatePublishRequest request = request(TEMPLATE_ID); request.setRewardRulesJson(" ");
        service.saveOrUpdateDraft(request, OWNER, "作者");
        ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
        org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getClearFields());
        org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getRewardRulesJson());
    }

    @Test
    void unknownClearFieldsAreRejectedBeforeWrites() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing(OWNER, 0));
        for (String field : Arrays.asList("memberId", "title", "status", "story_img", "", null)) {
            CmsTemplatePublishRequest request = request(TEMPLATE_ID); request.setClearFields(Collections.singleton(field));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, OWNER, "作者")).getMessage().contains("不支持清空字段"));
            assertThrows(IllegalArgumentException.class, () -> service.publishTemplateWithTransaction(request, OWNER, "作者"));
        }
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void clearingRequiredSensorFieldsUsesStoredValidationMethodAndRejectsBeforeWrites() {
        for (int method : new int[]{2, 7}) for (String field : Arrays.asList("sensorType", "sensorConfig")) {
            CmsMemberTemplate stored = existing(OWNER, 0); stored.setValidationMethod(method);
            stored.setSensorType(method == 2 ? "filter_shot" : "still");
            when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(stored);
            for (Integer requestedMethod : Arrays.asList(null, method)) {
                CmsTemplatePublishRequest request = request(TEMPLATE_ID); request.setValidationMethod(requestedMethod);
                request.setClearFields(Collections.singleton(field));
                assertTrue(assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateDraft(request, OWNER, "作者")).getMessage().contains("先切换"));
                assertThrows(IllegalArgumentException.class, () -> service.publishTemplateWithTransaction(request, OWNER, "作者"));
            }
        }
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void switchingAwayFromSensorAllowsExplicitClearButNeverBypassesOwnership() {
        CmsMemberTemplate stored = existing(OWNER, 0); stored.setValidationMethod(7); stored.setSensorType("still");
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(stored);
        CmsTemplatePublishRequest request = request(TEMPLATE_ID);
        request.setClearFields(new HashSet<>(Arrays.asList("sensorType", "sensorConfig")));
        assertThrows(RuntimeException.class, () -> service.saveOrUpdateDraft(request, STRANGER, "其他人"));
        assertThrows(RuntimeException.class, () -> service.publishTemplateWithTransaction(request, STRANGER, "其他人"));
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
        service.saveOrUpdateDraft(request, OWNER, "作者");
        verify(memberTemplateService).updateCmsMemberTemplate(any());
    }

    @Test
    void onlyRequestDtoAcceptsClearFieldsAndEntityJsonCannotInjectThem() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String raw = "{\"clearFields\":[\"sensorConfig\"]}";
        assertEquals(Collections.singleton("sensorConfig"), json.readValue(raw, CmsTemplatePublishRequest.class).getClearFields());
        CmsMemberTemplate entity = json.readValue(raw, CmsMemberTemplate.class);
        org.junit.jupiter.api.Assertions.assertNull(entity.getClearFields());
        entity.setClearFields(Collections.singleton("sensorConfig"));
        org.junit.jupiter.api.Assertions.assertFalse(json.readTree(json.writeValueAsString(entity)).has("clearFields"));
    }

    /**
     * 据点模板的 duration 是 Long，发布 DTO 是 Integer；BeanUtils 不转换，修复前新建与改写都落成 0。
     */
    @Test
    void merchantNodeDurationSurvivesDtoCopy() {
        CmsMemberTemplate created = new CmsMemberTemplate();
        created.setTitle("新据点玩法"); created.setDuration(45L);
        service.saveMerchantNodeTemplate(created, OWNER, "商家");
        ArgumentCaptor<CmsMemberTemplate> inserted = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).insertCmsMemberTemplate(inserted.capture());
        assertEquals(45L, inserted.getValue().getDuration());

        CmsMemberTemplate existing = existing(OWNER, 0); existing.setMerchantNodeEnabled(1);
        existing.setDuration(30L);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(existing);
        CmsMemberTemplate edited = new CmsMemberTemplate(); edited.setId(TEMPLATE_ID);
        edited.setTitle("改过的据点玩法"); edited.setDuration(60L);
        service.saveMerchantNodeTemplate(edited, OWNER, "商家");
        ArgumentCaptor<CmsMemberTemplate> updated = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).updateCmsMemberTemplate(updated.capture());
        assertEquals(60L, updated.getValue().getDuration());
    }

    @Test
    void merchantNodeDurationOverflowIsRejectedNotTruncated() {
        CmsMemberTemplate input = new CmsMemberTemplate();
        input.setTitle("新据点玩法"); input.setDuration(Long.MAX_VALUE);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.saveMerchantNodeTemplate(input, OWNER, "商家"));
        verify(memberTemplateService, never()).insertCmsMemberTemplate(any());
    }

    private CmsMemberTemplate existing(Long memberId, Integer delFlag) {
        CmsMemberTemplate existing = new CmsMemberTemplate();
        existing.setId(TEMPLATE_ID);
        existing.setMemberId(memberId);
        existing.setDelFlag(delFlag);
        existing.setCreateBy("原作者");
        existing.setCreateTime(new Date(1_600_000_000_000L));
        return existing;
    }

    @Test
    void savingDeletedTemplateAsDraftCannotReviveIt() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 2));
        assertThrows(RuntimeException.class,
                () -> service.saveOrUpdateDraft(request(TEMPLATE_ID), OWNER, "原作者"));
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void editDraftAndDeleteWithdrawExistingMarketProjectionRegardlessOfLegacySyncFlag() {
        for (Integer sync : new Integer[] { null, 0, 1 }) {
            for (String action : new String[] { "edit", "draft", "delete" }) {
                setUp();
                CmsMemberTemplate source = existing(OWNER, 0);
                source.setIsSync(sync);
                when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(source);
                CmsTemplateLibrary projection = new CmsTemplateLibrary();
                projection.setId(171L); projection.setMemberTemplateId(TEMPLATE_ID);
                projection.setDelFlag(0); projection.setStatus(1L); projection.setPublishStatus(1L);
                when(libraryMapper.selectByMemberTemplateForUpdate(TEMPLATE_ID))
                        .thenReturn(Collections.singletonList(projection));
                when(templateLibraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
                if ("edit".equals(action)) service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "作者");
                else if ("draft".equals(action)) service.saveOrUpdateDraft(request(TEMPLATE_ID), OWNER, "作者");
                else assertTrue(service.deleteTemplateWithSync(TEMPLATE_ID, OWNER));

                assertEquals(0L, projection.getPublishStatus(), action + ": isSync=" + sync);
                assertEquals(2L, projection.getStatus());
                assertEquals("delete".equals(action) ? 2 : 0, projection.getDelFlag());
                verify(templateLibraryService).updateCmsTemplateLibrary(projection);
                verify(taskMapper).cancelOpenTemplateTasks("TEMPLATE_LIBRARY", 171L);
                verify(auditTaskService, never()).submitOrRefreshTemplateLibrary(any(), any(), any());
            }
        }
    }

    @Test
    void editingOwnTemplateUpdatesInPlaceInsteadOfCreatingACopy() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 0));

        Long returned = service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "商家甲");

        assertEquals(TEMPLATE_ID, returned, "编辑应返回原模板 id,不是新 id");
        ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
        verify(memberTemplateService, never()).insertCmsMemberTemplate(any());
        assertEquals(TEMPLATE_ID, saved.getValue().getId());
        assertEquals("1997", saved.getValue().getQuestionAnswer(), "改后的答案要落库");
    }

    @Test
    void editingKeepsOriginalAuthorshipAndReSubmitsForReview() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 0));

        service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "商家甲");

        ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
        CmsMemberTemplate written = saved.getValue();
        assertEquals("原作者", written.getCreateBy(), "创建者是出身信息,不接受客户端改写");
        assertEquals(new Date(1_600_000_000_000L), written.getCreateTime(), "创建时间同上");
        assertEquals("商家甲", written.getUpdateBy());
        assertEquals(Long.valueOf(0L), written.getStatus(), "改了内容必须重新过审,不能保留已过审状态");
        assertEquals(Long.valueOf(0L), written.getPublishStatus(), "重审期间不得留在架上");
        verify(auditTaskService).submitOrRefreshTemplate(anyLong(), anyLong(), anyString());
    }

    @Test
    void editingSomeoneElsesTemplateIsRejected() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(STRANGER, 0));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "商家甲"));

        assertTrue(thrown.getMessage().contains("无权修改"),
                "应报无权修改,实际:" + thrown.getMessage());
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
        verify(memberTemplateService, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void editingDeletedTemplateIsRejected() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 2));

        assertThrows(RuntimeException.class,
                () -> service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "商家甲"));
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void editingMissingTemplateIsRejectedWithTheSameMessageAsForeignOnes() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.publishTemplateWithTransaction(request(TEMPLATE_ID), OWNER, "商家甲"));

        // 「不存在」与「不是你的」共用一句话:分开说就等于给出一个模板 id 探测器
        assertTrue(thrown.getMessage().contains("模板不存在或无权修改"), thrown.getMessage());
    }

    @Test
    void editingDoesNotInflateSourceTemplatePopularity() {
        CmsMemberTemplate adopted = existing(OWNER, 0);
        adopted.setOriginalTemplateId(9001L);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(adopted);

        CmsTemplatePublishRequest request = request(TEMPLATE_ID);
        request.setOriginalTemplateId(9001L);
        service.publishTemplateWithTransaction(request, OWNER, "商家甲");

        // 改一次加一次热度,榜单就变成「谁改得勤」而不是「谁被采用得多」
        verify(templateLibraryService, never()).incrementUseNumOnFirstAdoption(anyLong(), anyLong(), anyLong());
    }

    @Test
    void adoptedEditKeepsLocalAnswersMedalAndOriginEvenWhenSourceCannotBeRead() {
        for (boolean draft : new boolean[]{true, false}) {
            setUp();
            CmsMemberTemplate owned = existing(OWNER, 0); owned.setOriginalTemplateId(9001L);
            owned.setMedalName("采用时奖章"); owned.setMedalImg("media-asset:1"); owned.setMedalStyle("enamel");
            when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(owned);
            when(templateLibraryService.selectCmsTemplateLibraryById(anyLong()))
                    .thenThrow(new IllegalStateException("原来源已经不可读"));
            CmsTemplatePublishRequest request = request(TEMPLATE_ID);
            request.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,"
                    + "\"mode\":\"TYPE\",\"title\":\"副本题目\",\"answerText\":\"本地答案\"}}");
            request.setMedalName("客户端替换"); request.setMedalStyle("glow");
            if (draft) service.saveOrUpdateDraft(request, OWNER, "作者");
            else service.publishTemplateWithTransaction(request, OWNER, "作者");
            ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
            verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
            assertEquals(9001L, saved.getValue().getOriginalTemplateId());
            assertTrue(saved.getValue().getAdvancedConfigJson().contains("本地答案"));
            assertEquals("采用时奖章", saved.getValue().getMedalName());
            assertEquals("media-asset:1", saved.getValue().getMedalImg());
            assertEquals("enamel", saved.getValue().getMedalStyle());
            verify(templateLibraryService, never()).selectCmsTemplateLibraryById(anyLong());
        }
    }

    @Test
    void adoptedEditCannotRestoreMissingLocalSecretFromLiveSource() {
        CmsMemberTemplate owned = existing(OWNER, 0); owned.setOriginalTemplateId(9001L);
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(owned);
        CmsTemplatePublishRequest request = request(TEMPLATE_ID);
        request.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,"
                + "\"mode\":\"TYPE\",\"title\":\"缺本地答案\"}}");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveOrUpdateDraft(request, OWNER, "作者"));
        verify(templateLibraryService, never()).selectCmsTemplateLibraryById(anyLong());
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    @Test
    void adoptedEditsKeepClearFieldsButCannotEraseOrSwitchOriginalLibraryIdentity() {
        for (boolean draft : new boolean[]{true, false}) {
            setUp();
            CmsMemberTemplate owned = existing(OWNER, 0); owned.setOriginalTemplateId(9001L);
            when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(owned);
            for (Long replacement : Arrays.asList(0L, 9002L)) {
                CmsTemplatePublishRequest forged = request(TEMPLATE_ID);
                forged.setOriginalTemplateId(replacement);
                assertThrows(IllegalArgumentException.class, () -> {
                    if (draft) service.saveOrUpdateDraft(forged, OWNER, "作者");
                    else service.publishTemplateWithTransaction(forged, OWNER, "作者");
                });
            }
            verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
            CmsTemplatePublishRequest valid = request(TEMPLATE_ID);
            valid.setClearFields(new HashSet<>(Arrays.asList("merchantGuide", "rewardRulesJson", "storyImg")));
            if (draft) service.saveOrUpdateDraft(valid, OWNER, "作者");
            else service.publishTemplateWithTransaction(valid, OWNER, "作者");
            ArgumentCaptor<CmsMemberTemplate> saved = ArgumentCaptor.forClass(CmsMemberTemplate.class);
            verify(memberTemplateService).updateCmsMemberTemplate(saved.capture());
            assertEquals(9001L, saved.getValue().getOriginalTemplateId());
            assertEquals(valid.getClearFields(), saved.getValue().getClearFields());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getMerchantGuide());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getRewardRulesJson());
            org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getStoryImg());
        }
    }

    @Test
    void creatingStillInsertsAndCountsPopularity() {
        CmsTemplatePublishRequest request = request(null);
        request.setOriginalTemplateId(9001L);
        when(templateLibraryService.selectCmsTemplateLibraryById(9001L)).thenReturn(null);

        service.publishTemplateWithTransaction(request, OWNER, "商家甲");

        verify(memberTemplateService).insertCmsMemberTemplate(any());
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
        verify(templateLibraryService).incrementUseNumOnFirstAdoption(org.mockito.ArgumentMatchers.eq(9001L), org.mockito.ArgumentMatchers.eq(OWNER), any());
    }

    @Test
    void editingPersonalTemplateDoesNotTakeMarketProjectionDown() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 0));

        CmsTemplatePublishRequest request = request(TEMPLATE_ID);
        request.setIsSync(0);
        service.publishTemplateWithTransaction(request, OWNER, "商家甲");

        verify(templateLibraryService, never()).updateCmsTemplateLibrary(any());
        verify(templateLibraryService, never()).insertCmsTemplateLibrary(any());
    }

    @Test
    void editingPersonalTemplateWithLegacySyncFlagDoesNotOverwriteMarketProjection() {
        when(memberTemplateService.selectCmsMemberTemplateById(TEMPLATE_ID))
                .thenReturn(existing(OWNER, 0));

        CmsTemplatePublishRequest request = request(TEMPLATE_ID);
        request.setIsSync(1);
        service.publishTemplateWithTransaction(request, OWNER, "商家甲");

        verify(templateLibraryService, never()).updateCmsTemplateLibrary(any());
        verify(templateLibraryService, never()).insertCmsTemplateLibrary(any());
    }
}
