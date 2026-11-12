package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopSnapshotDraftTest {
    @Test void purchasedSnapshotUsesOriginalSaveValidationsWithoutReloadingChangedPublicSecrets() {
        var service=new TemplatePublishServiceImpl();
        var members=mock(ICmsMemberTemplateService.class);var library=mock(ICmsTemplateLibraryService.class);
        var capabilities=mock(ICapabilityGuard.class);var media=mock(AppMediaPublicationGuard.class);
        var libraryMapper=mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class);
        var memberMapper=mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(service,"libraryMapper",libraryMapper);
        ReflectionTestUtils.setField(service,"memberTemplateMapper",memberMapper);
        ReflectionTestUtils.setField(service,"memberTemplateService",members);
        ReflectionTestUtils.setField(service,"templateLibraryService",library);
        ReflectionTestUtils.setField(service,"capabilityGuard",capabilities);
        ReflectionTestUtils.setField(service,"mediaPublicationGuard",media);
        when(members.insertCmsMemberTemplate(any())).thenAnswer(call -> {CmsMemberTemplate row=call.getArgument(0);row.setId(90L);return 1;});
        var snapshot=new CmsTemplatePublishRequest();snapshot.setOriginalTemplateId(8L);snapshot.setTitle("purchased title");
        snapshot.setAdoptSourceOnly(true);
        snapshot.setValidationMethod(1);snapshot.setQuestionName("frozen question");snapshot.setQuestionAnswer("frozen answer");
        snapshot.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"frozen question\",\"options\":[{\"id\":\"a\",\"label\":\"right\",\"correct\":true},{\"id\":\"b\",\"label\":\"wrong\",\"correct\":false}]}}");
        assertEquals(90L,service.installWorkshopSnapshot(snapshot,7L,"buyer"));
        var saved=org.mockito.ArgumentCaptor.forClass(CmsMemberTemplate.class);verify(members).insertCmsMemberTemplate(saved.capture());
        assertEquals("frozen answer",saved.getValue().getQuestionAnswer());assertEquals(7L,saved.getValue().getMemberId());
        assertEquals(8L,saved.getValue().getOriginalTemplateId());
        assertFalse(snapshot.getAdoptSourceOnly());
        assertEquals(0,saved.getValue().getDraftStatus());assertEquals(0L,saved.getValue().getStatus());assertEquals(0L,saved.getValue().getPublishStatus());
        verify(capabilities).checkAdvancedMechanics(eq(7L),anyString());verify(media).requireTemplate(eq(7L),any(),eq(false));
        verifyNoInteractions(library,libraryMapper,memberMapper);
    }

    @Test void snapshotCannotOverwriteTargetOrSkipExistingCapabilityGuard() {
        var service=new TemplatePublishServiceImpl();var snapshot=new CmsTemplatePublishRequest();snapshot.setId(30L);snapshot.setOriginalTemplateId(8L);
        assertThrows(IllegalArgumentException.class,()->service.installWorkshopSnapshot(snapshot,7L,"buyer"));
        var capabilities=mock(ICapabilityGuard.class);ReflectionTestUtils.setField(service,"capabilityGuard",capabilities);
        when(capabilities.checkAdvancedMechanics(any(),any())).thenReturn("mechanic unavailable");
        snapshot.setId(null);snapshot.setTitle("frozen");snapshot.setValidationMethod(1);
        assertEquals("mechanic unavailable",assertThrows(IllegalArgumentException.class,()->service.installWorkshopSnapshot(snapshot,7L,"buyer")).getMessage());
    }
}
