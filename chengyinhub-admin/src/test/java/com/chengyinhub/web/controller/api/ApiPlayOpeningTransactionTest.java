package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 真实 Spring 事务代理 + H2:开局完成端点在 controller 与 service 双层 @Transactional 下,
 * service 抛出的业务拒绝必须原样回给客户端,不能因为内层已置 rollback-only 而在外层提交期
 * 退化成 UnexpectedRollbackException(客户端看到裸 500)。
 *
 * 纯 new Controller() 的单测没有事务代理,证不出这条,所以必须用真实 TransactionInterceptor。
 */
class ApiPlayOpeningTransactionTest
{
    @Test
    void businessRejectionReachesClientAsMessageInsteadOfUnexpectedRollback()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:opening_tx;MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        ITopicCompletionAwardService awards = mock(ITopicCompletionAwardService.class);
        ApiPlayJourneyActionController controller =
                transactionalController(rejectingRuntime(txManager, awards), txManager);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("topicId", 10L);
        body.put("activityId", 0L);
        body.put("chapterId", 90L);
        body.put("expectedVersion", 0);

        AjaxResult result = controller.completeOpening(body);

        assertNotEquals(200, result.get("code"), "业务拒绝不能以 200 回包");
        assertEquals("开局流程不匹配", result.get("msg"),
                "客户端必须拿到业务文案;退化成 UnexpectedRollbackException 就是裸 500");
        verifyNoInteractions(awards);
    }

    /** 真实 service,mock 到「本局不存在 ⇒ 开局流程不匹配」这条业务拒绝路径上。 */
    private ITopicRouteRuntimeService rejectingRuntime(DataSourceTransactionManager txManager,
            ITopicCompletionAwardService awards)
    {
        TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);

        CmsTopic topic = new CmsTopic();
        topic.setId(10L);
        topic.setDelFlag(0);
        topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L);
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);

        CmsRegistration pass = new CmsRegistration();
        pass.setExpiresAt(null);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));
        // 本局读不到 ⇒ openingSnapshot 为空 ⇒ completeOpening 抛 IllegalArgumentException
        when(routeMapper.selectByOwner(0L, 10L, 7L, "default")).thenReturn(null);

        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper,
                mock(CmsTopicNodeMapper.class), mock(CmsMemberTemplateMapper.class),
                mock(PlayerNodeProgressMapper.class), mock(CmsActivityMapper.class), registrationMapper,
                mock(PlayerActivityRoleMapper.class), () -> 12345L, Clock.systemUTC(),
                RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target, "topicCompletionAwardService", awards);

        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(ITopicRouteRuntimeService.class);
        factory.addAdvice(interceptor(txManager));
        return (ITopicRouteRuntimeService) factory.getProxy();
    }

    /** controller 无接口,走 CGLIB。即便将来有人给它加回 @Transactional,这条断言也会把问题拦住。 */
    private ApiPlayJourneyActionController transactionalController(ITopicRouteRuntimeService runtime,
            DataSourceTransactionManager txManager)
    {
        ApiPlayJourneyActionController target = spy(new ApiPlayJourneyActionController());
        doReturn(7L).when(target).getAppUserId();
        ReflectionTestUtils.setField(target, "topicRouteRuntimeService", runtime);

        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor(txManager));
        return (ApiPlayJourneyActionController) factory.getProxy();
    }

    private TransactionInterceptor interceptor(DataSourceTransactionManager txManager)
    {
        return new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource());
    }
}
