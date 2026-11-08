package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

/** Bind new dependencies to each test's actual mapper facts, without constructing an Evaluation. */
public final class TopicPublicationTestFixtures {
    private TopicPublicationTestFixtures() {}
    private static <T> T mapper(CmsTopicServiceImpl service, String name, Class<T> type) {
        Object existing = TopicPublicationTestWiring.get(service, name);
        if (existing != null) return type.cast(existing);
        T empty = mock(type);
        TopicPublicationTestWiring.set(service, name, empty);
        return empty;
    }
    public static void wire(CmsTopicServiceImpl service) {
        CmsTopicMapper topics = mapper(service,"cmsTopicMapper",CmsTopicMapper.class);
        CmsTopicChapterMapper chapters = mapper(service,"cmsTopicChapterMapper",CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodes = mapper(service,"cmsTopicNodeMapper",CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templates = mapper(service,"cmsMemberTemplateMapper",CmsMemberTemplateMapper.class);
        OmsTicketMapper tickets = mapper(service,"omsTicketMapper",OmsTicketMapper.class);
        CommercialTopicPolicy actual = new CommercialTopicPolicy(tickets,chapters,mock(MerchantChapterOfferMapper.class),topics);
        CommercialTopicPolicy configured = (CommercialTopicPolicy) ReflectionTestUtils.getField(service,"commercialTopicPolicy");
        if (configured == null) ReflectionTestUtils.setField(service,"commercialTopicPolicy",actual);
        else if (mockingDetails(configured).isMock() && mockingDetails(configured).getStubbings().stream()
                .noneMatch(stub -> "evaluate".equals(stub.getInvocation().getMethod().getName()))) {
            when(configured.evaluate(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> actual.evaluate(call.getArgument(0)));
        }
        if (ReflectionTestUtils.getField(service,"topicCommercialReviewService") == null)
            ReflectionTestUtils.setField(service,"topicCommercialReviewService",mock(ITopicCommercialReviewService.class));
        if (ReflectionTestUtils.getField(service,"mediaPublicationGuard") == null
                || mockingDetails(ReflectionTestUtils.getField(service,"mediaPublicationGuard")).isMock()) {
            AppMediaPublicationGuard publication = MediaPublicationTestFixtures.template(7L,1L,2L,3L);
            ReflectionTestUtils.setField(publication,"topics",topics);
            ReflectionTestUtils.setField(publication,"chapters",chapters);
            ReflectionTestUtils.setField(publication,"nodes",nodes);
            ReflectionTestUtils.setField(publication,"templates",templates);
            ReflectionTestUtils.setField(service,"mediaPublicationGuard",publication);
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service);
        // B3 状态投影新增的依赖:发布路径会经 publicationStatus 读未结审核单。
        // 手工装配的夹具里它是空的,发布就 NPE —— 与上面几处「不在场就是 NPE 假红」同性质。
        // 只补空位,不覆盖测试自己设的 mock。
        if (com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(service, "auditTaskService") == null) {
            com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(
                    service, "auditTaskService", mock(com.chengyinhub.business.service.IAuditTaskService.class));
        }
    }
}
