package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.service.ICapabilityGuard;
/**
 * 模板市场热度:「从库模板起草→发布」(publish/temp 链路带 originalTemplateId=库模板 id)
 * 必须给来源库模板 use_num +1;白手起家的发布(originalTemplateId 空/0)不得计数。
 */
class TemplateMarketUseCountTest {

    private ICmsMemberTemplateService memberService;
    private ICmsTemplateLibraryService libraryService;
    private IAuditTaskService auditService;
    private TemplatePublishServiceImpl service;

    @BeforeEach
    void setUp() {
        memberService = mock(ICmsMemberTemplateService.class);
        libraryService = mock(ICmsTemplateLibraryService.class);
        auditService = mock(IAuditTaskService.class);
        service = new TemplatePublishServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L, 1L, 2L, 3L));
        // X03:publish/draft 现在会先过身份能力位闸(分支/博弈/勋章)。
        // mock 默认返回 null = 放行,本用例断言的不是能力位,行为与接闸前一致。
        ReflectionTestUtils.setField(service, "capabilityGuard", mock(ICapabilityGuard.class));
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
        when(memberService.insertCmsMemberTemplate(any())).thenAnswer(invocation -> {
            CmsMemberTemplate row = invocation.getArgument(0);
            row.setId(91L);
            when(memberService.selectCmsMemberTemplateById(row.getId())).thenReturn(row);
            return 1;
        });
    }

    @Test
    void publishFromLibraryTemplate_countsUseOnSource() {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("从今晚的暗号起草");
        request.setOriginalTemplateId(9001L);

        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));
        verify(libraryService).incrementUseNum(eq(9001L));
    }

    @Test
    void publishFromScratch_doesNotCount() {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("白手起家的玩法");

        assertEquals(91L, service.publishTemplateWithTransaction(request, 7L, "member7"));
        verify(libraryService, never()).incrementUseNum(anyLong());
    }
}
