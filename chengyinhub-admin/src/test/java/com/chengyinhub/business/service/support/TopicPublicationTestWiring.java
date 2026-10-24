package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicPublicationService;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import org.mockito.Mockito;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

/** Assemble the real publication owners with the fixture's original collaborator doubles. */
public final class TopicPublicationTestWiring {
    private TopicPublicationTestWiring() { }

    private static CmsTopicServiceImpl facade(Object value) {
        Object target = AopTestUtils.getUltimateTargetObject(value);
        return target instanceof CmsTopicServiceImpl ? (CmsTopicServiceImpl) target : null;
    }

    private static List<Object> owners(CmsTopicServiceImpl facade) {
        TopicPublicationService publication = (TopicPublicationService) ReflectionTestUtils.getField(facade, "topicPublicationService");
        TopicPublicationContentGuard content = (TopicPublicationContentGuard) ReflectionTestUtils.getField(facade, "publicationContentGuard");
        TopicSelfHostActivityService activity = (TopicSelfHostActivityService) ReflectionTestUtils.getField(facade, "selfHostActivityService");
        if (publication == null || Mockito.mockingDetails(publication).isMock()) publication = new TopicPublicationService();
        if (content == null || Mockito.mockingDetails(content).isMock()) content = new TopicPublicationContentGuard();
        if (activity == null || Mockito.mockingDetails(activity).isMock()) activity = new TopicSelfHostActivityService();
        ReflectionTestUtils.setField(facade, "topicPublicationService", publication);
        ReflectionTestUtils.setField(facade, "publicationContentGuard", content);
        ReflectionTestUtils.setField(facade, "selfHostActivityService", activity);
        ReflectionTestUtils.setField(publication, "publicationContentGuard", content);
        ReflectionTestUtils.setField(publication, "selfHostActivityService", activity);
        return List.of(publication, content, activity);
    }

    public static <T> T wire(T value) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) return value;
        var topicMapper = (com.chengyinhub.business.mapper.CmsTopicMapper) ReflectionTestUtils.getField(facade, "cmsTopicMapper");
        // Existing unit fixtures predate the narrow parent-lock query. Keep their original topic doubles;
        // only supply the newly introduced lock acknowledgement, never override an explicit new stub.
        if (topicMapper != null && Mockito.mockingDetails(topicMapper).isMock()
                && !Mockito.mockingDetails(topicMapper).isSpy()
                && Mockito.mockingDetails(topicMapper).getStubbings().stream().noneMatch(stub ->
                "lockTopicContentBudget".equals(stub.getInvocation().getMethod().getName()))) {
            Mockito.when(topicMapper.lockTopicContentBudget(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(call -> call.getArgument(0));
        }
        TopicContentWriteGuard guard = (TopicContentWriteGuard) ReflectionTestUtils.getField(facade, "contentWriteGuard");
        if (guard == null) {
            guard = new TopicContentWriteGuard(topicMapper,
                    (com.chengyinhub.business.mapper.CmsTopicChapterMapper) ReflectionTestUtils.getField(facade, "cmsTopicChapterMapper"),
                    (com.chengyinhub.business.mapper.CmsMemberTemplateMapper) ReflectionTestUtils.getField(facade, "cmsMemberTemplateMapper"),
                    (com.chengyinhub.business.service.IProductRoleService) ReflectionTestUtils.getField(facade, "productRoleService"),
                    (com.chengyinhub.system.service.ISysCategoryService) ReflectionTestUtils.getField(facade, "sysCategoryService"));
            ReflectionTestUtils.setField(facade, "contentWriteGuard", guard);
        }
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", ReflectionTestUtils.getField(facade, "cmsTopicNodeMapper"));
        for (Object owner : owners(facade)) {
            for (Field field : owner.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Field original = ReflectionUtils.findField(CmsTopicServiceImpl.class, field.getName());
                if (original != null) ReflectionTestUtils.setField(owner, field.getName(), ReflectionTestUtils.getField(facade, field.getName()));
            }
        }
        return value;
    }

    /** Preserve the old "mock every injected collaborator" fixtures after fields move to their real owner. */
    public static void wireRelocatedCollaborators(Object value, java.util.function.Function<Field, Object> originalFactory) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) throw new IllegalArgumentException("Expected the real topic fixture target");
        Set<String> relocated = Set.of("quotaService", "omsTicketService", "npcProfileMapper",
                "topicStateKeyRefService", "topicRouteRuntimeService", "routeOutcomeReader");
        for (Object owner : owners(facade)) for (Field field : owner.getClass().getDeclaredFields()) {
            if (relocated.contains(field.getName())
                    && field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                ReflectionTestUtils.setField(owner, field.getName(), originalFactory.apply(field));
        }
        wire(value);
    }

    public static void set(Object value, String name, Object collaborator) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null || ReflectionUtils.findField(CmsTopicServiceImpl.class, name) != null) {
            ReflectionTestUtils.setField(facade == null ? value : facade, name, collaborator);
            return;
        }
        boolean found = false;
        for (Object owner : owners(facade)) if (ReflectionUtils.findField(owner.getClass(), name) != null) {
            ReflectionTestUtils.setField(owner, name, collaborator);
            found = true;
        }
        if (!found) throw new IllegalArgumentException("No original publication collaborator: " + name);
    }

    public static Object get(Object value, String name) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null || ReflectionUtils.findField(CmsTopicServiceImpl.class, name) != null)
            return ReflectionTestUtils.getField(facade == null ? value : facade, name);
        for (Object owner : owners(facade)) if (ReflectionUtils.findField(owner.getClass(), name) != null)
            return ReflectionTestUtils.getField(owner, name);
        throw new IllegalArgumentException("No original publication collaborator: " + name);
    }

    public static Object owner(Object value, String method) {
        wire(value);
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) return value;
        for (Object owner : owners(facade)) for (java.lang.reflect.Method candidate : owner.getClass().getDeclaredMethods())
            if (candidate.getName().equals(method)) return owner;
        return value;
    }
}
