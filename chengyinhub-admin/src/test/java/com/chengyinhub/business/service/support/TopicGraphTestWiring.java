package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl;
import com.chengyinhub.system.service.ISysCategoryService;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Test assembly only: the real graph owners receive exactly the facade fixture's existing dependencies. */
public final class TopicGraphTestWiring {
    private TopicGraphTestWiring() { }

    public static <T extends ICmsTopicService> T wire(T service) {
        shared(service);
        CmsTopicServiceImpl facade = AopTestUtils.getUltimateTargetObject(service);
        TopicContentGraphAuthoringServiceImpl graph = new TopicContentGraphAuthoringServiceImpl();
        for (String name : new String[]{"cmsTopicMapper", "cmsTopicChapterMapper", "cmsTopicNodeMapper",
                "cmsMemberTemplateMapper", "cmsMemberTemplateService", "topicRouteRuntimeService"}) {
            ReflectionTestUtils.setField(graph, name, com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(facade, name));
        }
        ReflectionTestUtils.setField(graph, "contentWriteGuard", field(facade, "contentWriteGuard"));
        ReflectionTestUtils.setField(graph, "routeOutcomeReader", field(facade, "routeOutcomeReader"));
        ReflectionTestUtils.setField(facade, "contentGraphAuthoringService", graph);
        return com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service);
    }

    /** Publication keeps its old orchestration and needs only the moved shared content checks/read owner. */
    public static <T extends ICmsTopicService> T shared(T service) {
        CmsTopicServiceImpl facade = AopTestUtils.getUltimateTargetObject(service);
        TopicContentWriteGuard guard = new TopicContentWriteGuard(
                field(facade, "cmsTopicMapper"), field(facade, "cmsTopicChapterMapper"),
                field(facade, "cmsMemberTemplateMapper"), field(facade, "productRoleService"),
                field(facade, "sysCategoryService"));
        TopicRouteOutcomeReader reader = new TopicRouteOutcomeReader(
                field(facade, "cmsTopicNodeMapper"), field(facade, "cmsMemberTemplateMapper"));
        ReflectionTestUtils.setField(facade, "contentWriteGuard", guard);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(facade, "routeOutcomeReader", reader);
        return com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service);
    }

    public static TopicContentGraphAuthoringServiceImpl graph(ICmsTopicService service) {
        wire(service);
        return field(AopTestUtils.getUltimateTargetObject(service), "contentGraphAuthoringService");
    }

    public static TopicContentWriteGuard guard(ICmsTopicService service) {
        shared(service);
        return field(AopTestUtils.getUltimateTargetObject(service), "contentWriteGuard");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        return (T) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(owner, name);
    }
}
