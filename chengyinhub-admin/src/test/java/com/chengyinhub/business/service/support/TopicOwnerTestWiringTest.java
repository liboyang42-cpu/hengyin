package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Assembly regressions only; original business cases continue to exercise the real owners. */
class TopicOwnerTestWiringTest {
    @Test void movedCollaboratorsReachRealQueryCopyGraphAndPublicationOwners() {
        CmsTopicServiceImpl facade = fixture();
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        ISysCategoryService categories = mock(ISysCategoryService.class);
        CmsTopicTemplateMetaMapper metadata = mock(CmsTopicTemplateMetaMapper.class);
        IMerchantChapterApplicationService applications = mock(IMerchantChapterApplicationService.class);
        TopicPublicationTestWiring.set(facade, "cmsMemberTemplateMapper", templates);
        TopicPublicationTestWiring.set(facade, "sysCategoryService", categories);
        TopicPublicationTestWiring.set(facade, "cmsTopicTemplateMetaMapper", metadata);
        TopicPublicationTestWiring.set(facade, "merchantChapterApplicationService", applications);
        TopicGraphTestWiring.wire(facade);
        TopicContentCopyServiceImpl copy = TopicCopyTestWiring.owner(facade);
        TopicPublicationTestWiring.wire(facade);
        Object query = ReflectionTestUtils.getField(facade, "topicQueryService");
        assertInstanceOf(TopicQueryService.class, query);
        assertFalse(mockingDetails(query).isMock());
        assertFalse(mockingDetails(copy).isMock());
        for (String owner : new String[]{"topicQueryService", "publicationContentGuard", "contentGraphAuthoringService", "topicContentCopyService", "contentWriteGuard"})
            assertSame(templates, ReflectionTestUtils.getField(ReflectionTestUtils.getField(facade, owner), "cmsMemberTemplateMapper"), owner);
        assertSame(categories, ReflectionTestUtils.getField(query, "sysCategoryService"));
        assertSame(categories, ReflectionTestUtils.getField(copy, "sysCategoryService"));
        assertSame(categories, ReflectionTestUtils.getField(ReflectionTestUtils.getField(facade, "contentWriteGuard"), "sysCategoryService"));
        assertSame(metadata, ReflectionTestUtils.getField(query, "cmsTopicTemplateMetaMapper"));
        assertSame(applications, ReflectionTestUtils.getField(query, "merchantChapterApplicationService"));
        assertNull(ReflectionUtils.findField(CmsTopicServiceImpl.class, "cmsMemberTemplateMapper"));
        assertNull(ReflectionUtils.findField(CmsTopicServiceImpl.class, "sysCategoryService"));
        verifyNoInteractions(templates, categories, metadata, applications);
    }

    @Test void originalFactoryInitializesMovedDependenciesOnceWithoutReplacingExplicitStubs() {
        CmsTopicServiceImpl facade = fixture();
        ISysCategoryService categories = mock(ISysCategoryService.class);
        TopicPublicationTestWiring.set(facade, "sysCategoryService", categories);
        java.util.Map<String, Object> supplied = new java.util.HashMap<>();
        TopicPublicationTestWiring.wireRelocatedCollaborators(facade, field -> {
            Object value = mock(field.getType());
            assertNull(supplied.put(field.getName(), value), field.getName());
            return value;
        });
        assertFalse(supplied.containsKey("sysCategoryService"));
        assertTrue(supplied.containsKey("cmsMemberTemplateMapper"));
        assertTrue(supplied.containsKey("cmsTopicTemplateMetaMapper"));
        assertTrue(supplied.containsKey("merchantChapterApplicationService"));
        TopicPublicationTestWiring.wireRelocatedCollaborators(facade, field -> {
            fail("Existing collaborator must survive repeated assembly: " + field.getName()); return null;
        });
        assertSame(categories, TopicPublicationTestWiring.get(facade, "sysCategoryService"));
        supplied.forEach((name, value) -> assertSame(value, TopicPublicationTestWiring.get(facade, name), name));
        verifyNoInteractions(categories);
    }

    @Test void replacingFixtureMapperAfterAssemblyReachesEveryActualOwner() {
        CmsTopicServiceImpl facade = fixture();
        TopicGraphTestWiring.wire(facade);
        TopicCopyTestWiring.wire(facade);
        CmsMemberTemplateMapper replacement = mock(CmsMemberTemplateMapper.class);
        TopicPublicationTestWiring.set(facade, "cmsMemberTemplateMapper", replacement);
        TopicPublicationTestWiring.wire(facade);
        for (String owner : new String[]{"topicQueryService", "publicationContentGuard", "contentGraphAuthoringService", "topicContentCopyService", "contentWriteGuard"})
            assertSame(replacement, ReflectionTestUtils.getField(ReflectionTestUtils.getField(facade, owner), "cmsMemberTemplateMapper"), owner);
        verifyNoInteractions(replacement);
    }

    @Test void facadeQueryActuallyReachesTheOriginalMapperThroughTheRealQueryOwner() {
        CmsTopicServiceImpl facade = fixture();
        CmsTopicMapper topics = (CmsTopicMapper) ReflectionTestUtils.getField(facade, "cmsTopicMapper");
        ReflectionTestUtils.setField(facade, "topicQueryService", mock(TopicQueryService.class));
        TopicPublicationTestWiring.wire(facade);
        assertNull(facade.selectPublicCmsTopicById(41L));
        verify(topics).selectPublicCmsTopicById(41L);
        verifyNoMoreInteractions(topics);
    }

    @Test void unknownFieldStillFailsRatherThanCreatingAnUnownedCollaborator() {
        CmsTopicServiceImpl facade = fixture();
        assertThrows(IllegalArgumentException.class, () -> TopicPublicationTestWiring.get(facade, "missingCollaborator"));
        assertThrows(IllegalArgumentException.class, () -> TopicPublicationTestWiring.set(facade, "missingCollaborator", new Object()));
    }

    private CmsTopicServiceImpl fixture() {
        CmsTopicServiceImpl facade = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(facade, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(facade, "cmsTopicChapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(facade, "cmsTopicNodeMapper", mock(CmsTopicNodeMapper.class));
        return facade;
    }
}
