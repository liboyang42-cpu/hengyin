package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicNodeServiceImpl;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Supply the real budget owner using the old fixture's collaborator doubles or real production mappers. */
public final class TopicContentBudgetTestWiring {
    private TopicContentBudgetTestWiring() { }

    public static void chapter(CmsTopicChapterServiceImpl service) {
        wire(service, (CmsTopicMapper) ReflectionTestUtils.getField(service, "cmsTopicMapper"),
                (CmsTopicChapterMapper) ReflectionTestUtils.getField(service, "cmsTopicChapterMapper"),
                Mockito.mock(CmsTopicNodeMapper.class));
    }

    public static void node(CmsTopicNodeServiceImpl service) {
        wire(service, Mockito.mock(CmsTopicMapper.class), Mockito.mock(CmsTopicChapterMapper.class),
                (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper"));
    }

    public static void wire(Object service, CmsTopicMapper topics, CmsTopicChapterMapper chapters, CmsTopicNodeMapper nodes) {
        if (Mockito.mockingDetails(topics).isMock() && !Mockito.mockingDetails(topics).isSpy() && !stubbed(topics, "lockTopicContentBudget"))
            Mockito.when(topics.lockTopicContentBudget(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(call -> call.getArgument(0));
        if (Mockito.mockingDetails(chapters).isMock() && !Mockito.mockingDetails(chapters).isSpy() && !stubbed(chapters, "selectContentByIdForUpdate"))
            Mockito.when(chapters.selectContentByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(call -> chapters.selectCmsTopicChapterById(call.getArgument(0)));
        if (Mockito.mockingDetails(nodes).isMock() && !Mockito.mockingDetails(nodes).isSpy() && !stubbed(nodes, "selectContentByIdForUpdate"))
            Mockito.when(nodes.selectContentByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(call -> nodes.selectCmsTopicNodeById(call.getArgument(0)));
        TopicContentWriteGuard guard = new TopicContentWriteGuard(topics, chapters, null, null, null);
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "contentWriteGuard", guard);
    }

    private static boolean stubbed(Object value, String method) {
        return Mockito.mockingDetails(value).getStubbings().stream()
                .anyMatch(stub -> method.equals(stub.getInvocation().getMethod().getName()));
    }
}
