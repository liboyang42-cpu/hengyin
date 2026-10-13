package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConfigTemplateCopyTest {
    private TemplatePublishServiceImpl service;
    private ICmsMemberTemplateService members;
    private ICmsTemplateLibraryService library;
    private ICapabilityGuard capability;
    private CmsTemplateLibrary projection;
    private CmsMemberTemplate canonical;
    @BeforeEach void setup() {
        service=new TemplatePublishServiceImpl(); members=mock(ICmsMemberTemplateService.class);
        library=mock(ICmsTemplateLibraryService.class); capability=mock(ICapabilityGuard.class);
        ReflectionTestUtils.setField(service,"memberTemplateService",members);
        ReflectionTestUtils.setField(service,"templateLibraryService",library);
        ReflectionTestUtils.setField(service,"capabilityGuard",capability);
        projection=new CmsTemplateLibrary(); projection.setId(77L); projection.setMemberTemplateId(88L);
        projection.setDelFlag(0); projection.setStatus(1L); projection.setPublishStatus(1L);
        canonical=new CmsMemberTemplate(); canonical.setId(88L); canonical.setMemberId(42L);
        canonical.setDelFlag(0); canonical.setStatus(1L); canonical.setPublishStatus(1L);
        canonical.setTitle("来源玩法"); canonical.setValidationMethod(1); canonical.setQuestionAnswer("冻结答案");
        canonical.setDuration(5L);
        when(library.selectCmsTemplateLibraryById(77L)).thenReturn(projection);
        when(members.selectCmsMemberTemplateById(88L)).thenReturn(canonical);
        when(members.insertCmsMemberTemplate(any())).thenAnswer(i->{((CmsMemberTemplate)i.getArgument(0)).setId(500L);return 1;});
    }
    @Test void sameOwnerStillReceivesIndependentUnpublishedDraftAndSourceIsUntouched() {
        assertEquals(500L,service.copyPublicTemplateToDraft(77L,42L,"creator"));
        ArgumentCaptor<CmsMemberTemplate> cap=ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(members).insertCmsMemberTemplate(cap.capture()); CmsMemberTemplate own=cap.getValue();
        assertEquals(42L,own.getMemberId()); assertEquals(77L,own.getOriginalTemplateId());
        assertEquals(0,own.getDraftStatus()); assertEquals(0L,own.getPublishStatus()); assertEquals(0L,own.getStatus());
        assertEquals("冻结答案",own.getQuestionAnswer()); assertEquals(5L,own.getDuration());
        canonical.setQuestionAnswer("更新答案"); assertEquals("冻结答案",own.getQuestionAnswer());
        assertEquals(88L,canonical.getId()); verify(library,never()).incrementUseNum(anyLong());
    }
    @Test void publicReadAloneDoesNotAllowCopyAndExternalReferencesAreNotSilentlyDropped() {
        projection.setPublishStatus(0L);
        assertThrows(IllegalArgumentException.class,()->service.copyPublicTemplateToDraft(77L,42L,"creator"));
        projection.setPublishStatus(1L); canonical.setStatus(0L);
        assertThrows(IllegalArgumentException.class,()->service.copyPublicTemplateToDraft(77L,42L,"creator"));
        canonical.setStatus(1L);
        for(String raw:new String[]{"{\"enter\":{\"npcId\":9}}","{\"couponId\":9}","{\"when\":[{\"var\":\"relation.other\"}]}"}) {
            canonical.setAdvancedConfigJson(raw);
            assertThrows(IllegalArgumentException.class,()->service.copyPublicTemplateToDraft(77L,42L,"creator"));
        }
        verify(members,never()).insertCmsMemberTemplate(any());
    }
    @Test void existingCapabilityValidationRemainsAuthoritative() {
        when(capability.checkAdvancedMechanics(eq(42L),any())).thenReturn("未解锁");
        assertThrows(IllegalArgumentException.class,()->service.copyPublicTemplateToDraft(77L,42L,"creator"));
        verify(members,never()).insertCmsMemberTemplate(any());
    }
}
