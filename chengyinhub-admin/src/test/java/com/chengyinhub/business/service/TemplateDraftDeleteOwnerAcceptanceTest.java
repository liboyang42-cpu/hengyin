package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LANE AC3 CX-12 服务端半边:「我的玩法」草稿确认删除(subpackageMember/mytemplate → /api/template/delete
 * → {@link TemplatePublishServiceImpl#deleteTemplateWithSync})。客户端「取消不发、确认只删一条」由
 * v6b-mytemplate-delete-refresh.test.js / mytemplate-list-recovery-contract.test.js 钉住;这里钉服务端:
 * 只能删自己的、删前不改任何行、重复确认(已删)被拒且不再写。
 */
class TemplateDraftDeleteOwnerAcceptanceTest {
    private static final Long OWNER = 100L;
    private static final Long STRANGER = 200L;
    private static final Long TEMPLATE_ID = 77L;

    private ICmsMemberTemplateService memberTemplateService;
    private ICmsTemplateLibraryService templateLibraryService;
    private AuditTaskMapper taskMapper;
    private CmsTemplateLibraryMapper libraryMapper;
    private TemplatePublishServiceImpl service;
    private CmsMemberTemplate row;

    @BeforeEach
    void setUp() {
        memberTemplateService = mock(ICmsMemberTemplateService.class);
        templateLibraryService = mock(ICmsTemplateLibraryService.class);
        taskMapper = mock(AuditTaskMapper.class);
        libraryMapper = mock(CmsTemplateLibraryMapper.class);
        CmsMemberTemplateMapper memberMapper = mock(CmsMemberTemplateMapper.class);
        service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "memberTemplateMapper", memberMapper);
        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(service, "templateLibraryService", templateLibraryService);
        ReflectionTestUtils.setField(service, "auditTaskService", mock(IAuditTaskService.class));

        row = new CmsMemberTemplate();
        row.setId(TEMPLATE_ID);
        row.setMemberId(OWNER);
        row.setDelFlag(0);
        row.setPublishStatus(0L);
        when(memberMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.contains(TEMPLATE_ID) ? Collections.singletonList(row) : Collections.emptyList();
        });
        when(memberTemplateService.updateCmsMemberTemplate(any())).thenReturn(1);
        when(templateLibraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
    }

    @Test
    void strangerCannotDeleteSomeoneElsesDraftAndNothingIsWritten() {
        CmsTemplateLibrary projection = projection();
        when(libraryMapper.selectByMemberTemplateForUpdate(TEMPLATE_ID)).thenReturn(Collections.singletonList(projection));

        assertThrows(RuntimeException.class, () -> service.deleteTemplateWithSync(TEMPLATE_ID, STRANGER));
        assertThrows(RuntimeException.class, () -> service.deleteTemplateWithSync(TEMPLATE_ID, null));

        assertEquals(0, row.getDelFlag());
        assertEquals(0, projection.getDelFlag());
        assertEquals(1L, projection.getPublishStatus());
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
        verify(templateLibraryService, never()).updateCmsTemplateLibrary(any());
        verify(taskMapper, never()).cancelOpenTemplateTasks(anyString(), anyLong());
    }

    @Test
    void ownerConfirmDeletesExactlyThatDraftOnceAndRepeatedConfirmIsRefused() {
        when(libraryMapper.selectByMemberTemplateForUpdate(TEMPLATE_ID)).thenReturn(Collections.emptyList());

        assertTrue(service.deleteTemplateWithSync(TEMPLATE_ID, OWNER));
        assertEquals(2, row.getDelFlag());
        assertEquals(0L, row.getPublishStatus());

        // 双击/重放的第二次确认:行已删除,必须拒绝且不再写库。
        assertThrows(RuntimeException.class, () -> service.deleteTemplateWithSync(TEMPLATE_ID, OWNER));
        verify(memberTemplateService, times(1)).updateCmsMemberTemplate(any());
    }

    @Test
    void unknownTemplateIdIsRefusedWithoutAnyWrite() {
        assertThrows(RuntimeException.class, () -> service.deleteTemplateWithSync(999L, OWNER));
        assertThrows(RuntimeException.class, () -> service.deleteTemplateWithSync(0L, OWNER));
        verify(memberTemplateService, never()).updateCmsMemberTemplate(any());
    }

    private CmsTemplateLibrary projection() {
        CmsTemplateLibrary projection = new CmsTemplateLibrary();
        projection.setId(171L);
        projection.setMemberTemplateId(TEMPLATE_ID);
        projection.setDelFlag(0);
        projection.setStatus(1L);
        projection.setPublishStatus(1L);
        return projection;
    }
}
