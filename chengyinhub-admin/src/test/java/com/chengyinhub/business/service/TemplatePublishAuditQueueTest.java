package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.service.ICapabilityGuard;
/** B-3:模板发布只能落待审/下架态，并正式送统一审核队列。 */
class TemplatePublishAuditQueueTest {
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper memberMapper;
    private com.chengyinhub.business.mapper.CmsTemplateLibraryMapper libraryMapper;
    private com.chengyinhub.business.mapper.AuditTaskMapper taskMapper;

    private void wireLocks(TemplatePublishServiceImpl service) {
        memberMapper = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        libraryMapper = mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class);
        taskMapper = mock(com.chengyinhub.business.mapper.AuditTaskMapper.class);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", memberMapper);
        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        when(memberMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(inv -> {
            ICmsMemberTemplateService source = (ICmsMemberTemplateService) ReflectionTestUtils.getField(service, "memberTemplateService");
            CmsMemberTemplate row = source.selectCmsMemberTemplateById(((java.util.List<Long>) inv.getArgument(0)).get(0));
            return row == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(row);
        });
        when(libraryMapper.selectByMemberTemplateForUpdate(any())).thenAnswer(inv -> {
            ICmsTemplateLibraryService source = (ICmsTemplateLibraryService) ReflectionTestUtils.getField(service, "templateLibraryService");
            CmsTemplateLibrary query = new CmsTemplateLibrary(); query.setMemberTemplateId(inv.getArgument(0));
            return source.selectCmsTemplateLibraryList(query);
        });
    }


    @Test
    void approvedPersonalTemplateCanSubmitMarketProjectionWhilePersonalListingIsDown() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", templateMapper);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(91L);
        template.setMemberId(7L);
        template.setTitle("个人已过审玩法");
        template.setDraftStatus(1);
        template.setStatus(1L);
        template.setCheckStatus(1);
        template.setPublishStatus(0L);
        template.setDelFlag(0);
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(java.util.Collections.singletonList(91L)))
                .thenReturn(java.util.Collections.singletonList(template));
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.emptyList());
        when(libraryService.insertCmsTemplateLibrary(any())).thenAnswer(invocation -> {
            CmsTemplateLibrary row = invocation.getArgument(0);
            row.setId(191L);
            when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.singletonList(row));
            return 1;
        });
        when(auditService.submitOrRefreshTemplateLibrary(eq(191L), eq(7L), any())).thenReturn(301L);

        assertEquals(301L, service.submitTemplateToMarket(91L, 7L, "member7"));
        verify(libraryService).insertCmsTemplateLibrary(argThat(row -> Long.valueOf(91L).equals(row.getMemberTemplateId())
                && Long.valueOf(0L).equals(row.getStatus()) && Long.valueOf(0L).equals(row.getPublishStatus())
                && Integer.valueOf(0).equals(row.getDelFlag())));
        verify(auditService).submitOrRefreshTemplateLibrary(eq(191L), eq(7L),
                argThat(json -> json.contains("BIZ_TEMPLATE_LIBRARY") || json.contains("TEMPLATE_LIBRARY")));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(templateMapper, libraryService);
        order.verify(templateMapper).selectCmsMemberTemplateByIdsForUpdate(java.util.Collections.singletonList(91L));
        order.verify(libraryService).selectCmsTemplateLibraryList(any());
    }

    /**
     * 负控:去掉 submitTemplateToMarket 里的 ServiceException(M-06 口径)⇒ 本用例红。
     * 抛 RuntimeException 时 controller 只会回「请稍后重试」,作者看不到「先去个人过审」这个原因。
     */
    @Test
    void personalUnapprovedGameCannotBeSubmittedToMarket() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", templateMapper);

        CmsMemberTemplate pending = new CmsMemberTemplate();
        pending.setId(91L);
        pending.setMemberId(7L);
        pending.setDraftStatus(0);
        pending.setStatus(0L);
        pending.setCheckStatus(0);
        pending.setDelFlag(0);
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(java.util.Collections.singletonList(91L)))
                .thenReturn(java.util.Collections.singletonList(pending));

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.submitTemplateToMarket(91L, 7L, "member7"));
        assertEquals("玩法个人审核通过后才能提交模板广场", rejected.getMessage());
        assertEquals(Integer.valueOf(com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST), rejected.getCode());
        verify(libraryService, org.mockito.Mockito.never()).insertCmsTemplateLibrary(any());
        verify(auditService, org.mockito.Mockito.never())
                .submitOrRefreshTemplateLibrary(any(), any(), any());
    }

    /** 归属不符时不得暴露他人玩法是否存在,但仍要把原因透传给前端。 */
    @Test
    void othersGameCannotBeSubmittedToMarket() {
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        ReflectionTestUtils.setField(service, "memberTemplateMapper", templateMapper);
        CmsMemberTemplate foreign = new CmsMemberTemplate();
        foreign.setId(91L);
        foreign.setMemberId(8L);
        foreign.setDraftStatus(1);
        foreign.setStatus(1L);
        foreign.setCheckStatus(1);
        foreign.setDelFlag(0);
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(java.util.Collections.singletonList(91L)))
                .thenReturn(java.util.Collections.singletonList(foreign));

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.submitTemplateToMarket(91L, 7L, "member7"));
        assertEquals("模板不存在或无权提交", rejected.getMessage());
        assertEquals(Integer.valueOf(com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST), rejected.getCode());
    }

    @Test
    void publishCreatesOnlyPersonalAuditAndWaitsForExplicitMarketSubmit() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(91L);
            when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(row);
            return 1;
        });

        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("夜游模板");
        request.setDescription("审核快照内容");
        request.setIsSync(1);

        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));
        verify(memberService).insertCmsMemberTemplate(argThat(row -> Long.valueOf(0L).equals(row.getStatus())
                && Integer.valueOf(0).equals(row.getCheckStatus())
                && Long.valueOf(0L).equals(row.getPublishStatus())));
        verify(libraryService, org.mockito.Mockito.never()).insertCmsTemplateLibrary(any());
        verify(auditService).submitOrRefreshTemplate(eq(91L), eq(7L), argThat(json -> json.contains("夜游模板") && json.contains("审核快照内容")));
    }

    @Test
    void editingPendingTemplateKeepsIdentityAndRefreshesPersonalAuditSnapshot() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        CmsMemberTemplate pending = new CmsMemberTemplate();
        pending.setId(91L); pending.setMemberId(7L); pending.setDelFlag(0);
        pending.setDraftStatus(1); pending.setStatus(0L); pending.setCheckStatus(0); pending.setPublishStatus(0L);
        pending.setCreateBy("member7");
        java.util.Date created = new java.util.Date(1000);
        pending.setCreateTime(created);
        when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(pending);
        when(memberService.updateCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate replacement = invocation.getArgument(0);
            when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(replacement);
            return 1;
        });
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setId(91L); request.setTitle("本人待审模板");
        request.setDescription("修改后的重审内容");

        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));

        verify(memberService).updateCmsMemberTemplate(argThat(row -> Long.valueOf(91L).equals(row.getId())
                && Long.valueOf(7L).equals(row.getMemberId()) && created.equals(row.getCreateTime())
                && "member7".equals(row.getCreateBy()) && Integer.valueOf(1).equals(row.getDraftStatus())
                && Long.valueOf(0L).equals(row.getStatus()) && Integer.valueOf(0).equals(row.getCheckStatus())
                && Long.valueOf(0L).equals(row.getPublishStatus())));
        verify(memberService, org.mockito.Mockito.never()).insertCmsMemberTemplate(any());
        verify(libraryService, org.mockito.Mockito.never()).insertCmsTemplateLibrary(any());
        verify(auditService).submitOrRefreshTemplate(eq(91L), eq(7L),
                argThat(json -> json.contains("修改后的重审内容") && json.contains("本人待审模板")));
    }

    @Test
    void adminCreateAlsoWaitsForExplicitMarketSubmit() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(92L);
            when(memberService.selectCmsMemberTemplateById(92L)).thenReturn(row);
            return 1;
        });

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setMemberId(7L);
        template.setTitle("后台新建模板");
        template.setIsSync(1);

        assertEquals(1, service.createAdminTemplate(template, "operator"));
        verify(memberService).insertCmsMemberTemplate(argThat(row -> Long.valueOf(0L).equals(row.getStatus())
                && Integer.valueOf(0).equals(row.getCheckStatus()) && Long.valueOf(0L).equals(row.getPublishStatus())));
        verify(libraryService, org.mockito.Mockito.never()).insertCmsTemplateLibrary(any());
        verify(auditService).submitOrRefreshTemplate(eq(92L), eq(7L), contains("后台新建模板"));
    }

    @Test
    void adminCreateAndUpdateRejectMalformedSensorConfigurationBeforeWrite() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);

        CmsMemberTemplate malformed = new CmsMemberTemplate();
        malformed.setId(92L);
        malformed.setMemberId(7L);
        malformed.setValidationMethod(7);
        malformed.setSensorType("still");
        malformed.setSensorConfig("{}");
        when(memberService.selectCmsMemberTemplateById(92L)).thenReturn(malformed);

        assertThrows(IllegalArgumentException.class,
                () -> service.createAdminTemplate(malformed, "operator"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateAdminTemplate(malformed, "operator"));
        CmsTemplateLibrary malformedProjection = new CmsTemplateLibrary();
        malformedProjection.setId(191L);
        malformedProjection.setValidationMethod(7);
        malformedProjection.setSensorType("steps");
        malformedProjection.setSensorConfig("{\"targetSteps\":\"120\"}");
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTemplateFromLibraryProjection(malformedProjection, "operator"));
        verify(memberService, org.mockito.Mockito.never()).insertCmsMemberTemplate(any());
        verify(memberService, org.mockito.Mockito.never()).updateCmsMemberTemplate(any());
    }

    @Test
    void libraryEditResetsOnlyProjectionThenResubmitsMarketAudit() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        IAuditTaskService auditService = mock(IAuditTaskService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditService);

        CmsTemplateLibrary persistedProjection = new CmsTemplateLibrary();
        persistedProjection.setId(191L);
        persistedProjection.setMemberTemplateId(91L);
        persistedProjection.setMemberId(7L);
        persistedProjection.setDelFlag(0);
        CmsMemberTemplate canonical = new CmsMemberTemplate();
        canonical.setId(91L);
        canonical.setMemberId(7L);
        canonical.setStatus(1L);
        canonical.setCheckStatus(1);
        canonical.setPublishStatus(1L);
        canonical.setDraftStatus(1);
        canonical.setTitle("旧标题");
        canonical.setDelFlag(0);
        CmsTemplateLibrary edit = new CmsTemplateLibrary();
        edit.setId(191L);
        edit.setMemberTemplateId(91L);
        edit.setTitle("新标题");
        edit.setUseNum(999);
        when(libraryService.selectCmsTemplateLibraryById(191L)).thenReturn(persistedProjection);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.singletonList(persistedProjection));
        when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(canonical);
        when(libraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
        when(libraryService.updateCmsTemplateLibrary(any())).thenAnswer(inv -> {
            CmsTemplateLibrary saved = inv.getArgument(0);
            when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.singletonList(saved));
            return 1;
        });
        when(auditService.submitOrRefreshTemplateLibrary(eq(191L), eq(7L), any())).thenReturn(301L);

        assertEquals(1, service.updateTemplateFromLibraryProjection(edit, "operator"));
        verify(memberService, org.mockito.Mockito.never()).updateCmsMemberTemplate(any());
        verify(libraryService).updateCmsTemplateLibrary(argThat(row -> Long.valueOf(191L).equals(row.getId())
                && Long.valueOf(0L).equals(row.getStatus()) && Long.valueOf(0L).equals(row.getPublishStatus())
                && row.getUseNum() == null));
        verify(auditService).submitOrRefreshTemplateLibrary(eq(191L), eq(7L), contains("新标题"));
    }

    @Test
    void pendingTemplateCannotBePublishedThroughLibraryStatusEndpoint() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(91L);
        template.setMemberId(7L);
        template.setIsSync(1);
        template.setStatus(0L);
        template.setCheckStatus(0);
        template.setDelFlag(0);
        when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(template);

        // M-06:业务拒绝必须是 ServiceException,controller 才会透传原因而不是吞成「请稍后重试」
        ServiceException pending = assertThrows(ServiceException.class,
                () -> service.updateTemplateLibraryStatus(91L, 1, 7L));
        assertEquals("模板审核通过后才能上架到玩法库", pending.getMessage());
        verify(memberService, org.mockito.Mockito.never()).updateCmsMemberTemplate(any());

        // 审核通过但从没提交到模板广场(没有玩法库记录):同样必须透传原因。
        // 本支起上架不再看 isSync —— 玩法库记录由「提交到模板广场」显式生成,有没有记录才是真前提。
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.emptyList());
        template.setIsSync(0);
        template.setStatus(1L);
        template.setCheckStatus(1);
        ServiceException draft = assertThrows(ServiceException.class,
                () -> service.updateTemplateLibraryStatus(91L, 1, 7L));
        assertEquals("该模板还没有提交到模板广场，审核通过后才能上架", draft.getMessage());
        verify(memberService, org.mockito.Mockito.never()).updateCmsMemberTemplate(any());
    }

    @Test
    void approvedMarketProjectionCanToggleWithoutChangingPersonalTemplate() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(91L);
        template.setMemberId(7L);
        template.setIsSync(1);
        template.setStatus(1L);
        template.setCheckStatus(1);
        template.setPublishStatus(0L);
        template.setDelFlag(0);
        CmsTemplateLibrary projection = new CmsTemplateLibrary();
        projection.setId(191L);
        projection.setMemberTemplateId(91L);
        projection.setMemberId(7L);
        projection.setStatus(1L);
        projection.setDelFlag(0);
        when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(template);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Collections.singletonList(projection));
        when(libraryService.updateCmsTemplateLibrary(any())).thenReturn(1);

        // #398 F2:上架不带锁外复检快照一律拒绝(fail closed),且不产生任何写入。
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateTemplateLibraryStatus(91L, 1, 7L));
        verify(libraryService, org.mockito.Mockito.never()).updateCmsTemplateLibrary(any());
        assertTrue(service.updateTemplateLibraryStatus(91L, 1, 7L,
                TemplateLibraryRepublishReview.freeze(91L, 7L, java.util.Collections.singletonList(projection))));
        verify(memberService, org.mockito.Mockito.never()).updateCmsMemberTemplate(any());
        verify(libraryService).updateCmsTemplateLibrary(argThat(row -> Long.valueOf(1L).equals(row.getStatus())
                && Long.valueOf(1L).equals(row.getPublishStatus())));
    }

    @Test
    void projectionStatusFailureAbortsCanonicalStatusUpdateTransaction() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        ICmsTemplateLibraryService libraryService = mock(ICmsTemplateLibraryService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(91L);
        template.setMemberId(7L);
        template.setIsSync(1);
        template.setStatus(1L);
        template.setCheckStatus(1);
        template.setDelFlag(0);
        CmsTemplateLibrary first = new CmsTemplateLibrary();
        first.setId(191L);
        first.setMemberTemplateId(91L);
        first.setMemberId(7L);
        first.setStatus(1L);
        first.setDelFlag(0);
        CmsTemplateLibrary second = new CmsTemplateLibrary();
        second.setId(192L);
        second.setMemberTemplateId(91L);
        second.setMemberId(7L);
        second.setStatus(1L);
        second.setDelFlag(0);
        when(memberService.selectCmsMemberTemplateById(91L)).thenReturn(template);
        when(memberService.updateCmsMemberTemplate(any())).thenReturn(1);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(java.util.Arrays.asList(first, second));
        when(libraryService.updateCmsTemplateLibrary(any())).thenReturn(1, 0);

        assertThrows(RuntimeException.class, () -> service.updateTemplateLibraryStatus(91L, 1, 7L,
                TemplateLibraryRepublishReview.freeze(91L, 7L, java.util.Arrays.asList(first, second))));
        verify(libraryService).updateCmsTemplateLibrary(first);
        verify(libraryService).updateCmsTemplateLibrary(second);
    }

    @Test
    void preferenceTemplateCannotEnterReviewWithInvalidBranchContract() {
        ICmsMemberTemplateService memberService = mock(ICmsMemberTemplateService.class);
        TemplatePublishServiceImpl service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        wireLocks(service);
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(service, "memberTemplateService", memberService);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setValidationMethod(6);
        template.setPreferenceJson("{}");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createAdminTemplate(template, "operator"));

        assertEquals("偏好题缺少维度", error.getMessage());
        verify(memberService, org.mockito.Mockito.never()).insertCmsMemberTemplate(any());
    }
}
