package com.chengyinhub.upgrade;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.chengyinhub.business.notify.InAppNotifyChannel;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.impl.WxSubscribeMsgServiceImpl;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.impl.ClubCorporatePayeeServiceImpl;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.business.service.impl.QuotaServiceImpl;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.NotifyServiceImpl;
import com.chengyinhub.business.service.impl.CoopDepositRefundService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.business.service.impl.ClubPostMediaReviewServiceImpl;
import com.chengyinhub.business.service.impl.ClubPostServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.business.service.impl.UmsMemberBalanceDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.business.service.impl.WechatPayServiceImpl;
import com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real service wiring with Boot 4's circular-reference policy and transaction AOP.
 * There is no component scan, Boot auto-configuration, application property loading,
 * database, Redis connection, scheduler, or external provider. Every collaborator
 * outside the named cycle is registered as an inert singleton before initialization;
 * even an unexpected call caught by production code is recorded and fails the test.
 */
class SupportedStackServiceCyclesTest
{
    private record Cycle(String name, Class<?> source, String field, Class<?> target,
                         Class<?>... services)
    {
        @Override
        public String toString()
        {
            return name;
        }
    }

    static Stream<Cycle> cycles()
    {
        return Stream.of(
                new Cycle("registration/completion/topic/payment", CmsRegistrationServiceImpl.class,
                        "topicCompletionAwardService", TopicCompletionAwardServiceImpl.class,
                        CmsRegistrationServiceImpl.class, TopicCompletionAwardServiceImpl.class,
                        CmsTopicServiceImpl.class, WechatPayServiceImpl.class),
                new Cycle("im/audit/sale-finalization/club-payee/club", ImServiceImpl.class,
                        "auditTaskService", AuditTaskServiceImpl.class,
                        ImServiceImpl.class, AuditTaskServiceImpl.class, TopicSaleFinalizationServiceImpl.class,
                        ClubCorporatePayeeServiceImpl.class, ClubServiceImpl.class),
                new Cycle("audit/quota/topic", AuditTaskServiceImpl.class,
                        "quotaService", QuotaServiceImpl.class,
                        AuditTaskServiceImpl.class, QuotaServiceImpl.class, CmsTopicServiceImpl.class),
                new Cycle("im/audit/quota/topic/payment/notification", AuditTaskServiceImpl.class,
                        "quotaService", QuotaServiceImpl.class,
                        ImServiceImpl.class, AuditTaskServiceImpl.class, QuotaServiceImpl.class,
                        CmsTopicServiceImpl.class, CoopInviteServiceImpl.class, CoopDepositRefundService.class,
                        WechatPayServiceImpl.class, CmsRegistrationServiceImpl.class,
                        NotifyServiceImpl.class, InAppNotifyChannel.class),
                new Cycle("content/activity/audit/post/media-review", WxContentSecurityServiceImpl.class,
                        "cmsActivityService", CmsActivityServiceImpl.class,
                        WxContentSecurityServiceImpl.class, CmsActivityServiceImpl.class, AuditTaskServiceImpl.class,
                        ClubPostServiceImpl.class, ClubPostMediaReviewServiceImpl.class),
                new Cycle("content/topic/invite/payment/registration/wx-notification", WxContentSecurityServiceImpl.class,
                        "cmsTopicService", CmsTopicServiceImpl.class,
                        WxContentSecurityServiceImpl.class, CmsTopicServiceImpl.class,
                        CoopInviteServiceImpl.class, CoopDepositRefundService.class, WechatPayServiceImpl.class,
                        CmsRegistrationServiceImpl.class, NotifyServiceImpl.class,
                        WxSubscribeChannel.class, WxSubscribeMsgServiceImpl.class),
                new Cycle("topic/application/node", MerchantChapterApplicationServiceImpl.class,
                        "chapterMerchantNodeService", ChapterMerchantNodeServiceImpl.class,
                        MerchantChapterApplicationServiceImpl.class, ChapterMerchantNodeServiceImpl.class,
                        CmsTopicServiceImpl.class),
                new Cycle("chapter/application/node", MerchantChapterApplicationServiceImpl.class,
                        "chapterMerchantNodeService", ChapterMerchantNodeServiceImpl.class,
                        MerchantChapterApplicationServiceImpl.class, ChapterMerchantNodeServiceImpl.class,
                        CmsTopicChapterServiceImpl.class),
                new Cycle("member/balance", UmsMemberBalanceDetailServiceImpl.class,
                        "umsMemberService", UmsMemberServiceImpl.class,
                        UmsMemberServiceImpl.class, UmsMemberBalanceDetailServiceImpl.class),
                new Cycle("topic/balance", UmsMemberBalanceDetailServiceImpl.class,
                        "cmsTopicService", CmsTopicServiceImpl.class,
                        CmsTopicServiceImpl.class, UmsMemberBalanceDetailServiceImpl.class),
                new Cycle("invite/payment", WechatPayServiceImpl.class,
                        "coopInviteService", CoopInviteServiceImpl.class,
                        CoopInviteServiceImpl.class, WechatPayServiceImpl.class),
                new Cycle("merchant/payment", WechatPayServiceImpl.class,
                        "merchantCommerceService", MerchantCommerceService.class,
                        MerchantCommerceService.class, WechatPayServiceImpl.class),
                new Cycle("post/media-review/content-security", WxContentSecurityServiceImpl.class,
                        "clubPostService", ClubPostServiceImpl.class,
                        ClubPostServiceImpl.class, ClubPostMediaReviewServiceImpl.class,
                        WxContentSecurityServiceImpl.class));
    }

    @ParameterizedTest(name = "{0} starts with circular references disabled")
    @MethodSource("cycles")
    void realCycleStartsAndLazyEdgeResolvesToManagedTransactionalTarget(Cycle cycle)
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        runner(unexpectedCalls, null, cycle.services()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(((DefaultListableBeanFactory) context.getBeanFactory())
                    .isAllowCircularReferences()).isFalse();
            for (Class<?> service : cycle.services())
            {
                assertThat(context).hasSingleBean(service);
            }

            for (Class<?> channelType : List.of(InAppNotifyChannel.class, WxSubscribeChannel.class))
            {
                if (!Arrays.asList(cycle.services()).contains(channelType)) continue;
                Object notifier = AopTestUtils.getUltimateTargetObject(context.getBean(NotifyServiceImpl.class));
                Object channels = ReflectionTestUtils.getField(notifier, "channels");
                assertThat(channels).isInstanceOf(List.class);
                assertThat(((List<?>) channels).stream()
                        .anyMatch(channel -> channel == context.getBean(channelType))).isTrue();
            }

            Object source = AopTestUtils.getUltimateTargetObject(context.getBean(cycle.source()));
            Object lazyEdge = ReflectionTestUtils.getField(source, cycle.field());
            assertThat(AopUtils.isAopProxy(lazyEdge)).isTrue();
            Object managedTarget = context.getBean(cycle.target());
            // Resolving a lazy reference must preserve the managed transaction proxy,
            // rather than introducing a raw/self-invoked business-service instance.
            assertThat(((Advised) lazyEdge).getTargetSource().getTarget()).isSameAs(managedTarget);
            assertThat(managedTarget).isInstanceOf(Advised.class);
            assertThat(Arrays.stream(((Advised) managedTarget).getAdvisors())
                    .anyMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor)).isTrue();
            assertThat(unexpectedCalls).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0} rejects the original eager edge")
    @MethodSource("cycles")
    void sameRealCycleFailsWhenOnlyItsNewLazyEdgeIsMadeEager(Cycle cycle)
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        runner(unexpectedCalls, cycle, cycle.services()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
            assertThat(unexpectedCalls).isEmpty();
        });
    }

    @Test
    void allProvenCycleServicesStartTogetherWithCircularReferencesDisabled()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        Class<?>[] services = cycles().flatMap(cycle -> Arrays.stream(cycle.services()))
                .distinct().toArray(Class<?>[]::new);
        runner(unexpectedCalls, null, services).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> service : services)
            {
                assertThat(context).hasSingleBean(service);
            }
            assertThat(unexpectedCalls).isEmpty();
        });
    }

    private ApplicationContextRunner runner(List<Method> unexpectedCalls, Cycle eagerEdge,
                                             Class<?>... realServices)
    {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    DefaultListableBeanFactory factory =
                            (DefaultListableBeanFactory) context.getBeanFactory();
                    // ApplicationContextRunner does not apply SpringApplication defaults.
                    factory.setAllowCircularReferences(false);
                    registerInertCollaborators(factory, unexpectedCalls, realServices);
                    if (eagerEdge != null)
                    {
                        context.addBeanFactoryPostProcessor(ignored ->
                                factory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver()
                                {
                                    @Override
                                    protected boolean isLazy(DependencyDescriptor descriptor)
                                    {
                                        Field field = descriptor.getField();
                                        if (field != null && field.getDeclaringClass() == eagerEdge.source()
                                                && field.getName().equals(eagerEdge.field()))
                                        {
                                            return false;
                                        }
                                        return super.isLazy(descriptor);
                                    }
                                }));
                    }
                })
                .withUserConfiguration(TransactionProxyConfiguration.class)
                .withUserConfiguration(realServices);
    }

    private void registerInertCollaborators(DefaultListableBeanFactory factory,
                                            List<Method> unexpectedCalls, Class<?>[] realServices)
    {
        Set<Class<?>> dependencies = new LinkedHashSet<>();
        dependencies.add(PlatformTransactionManager.class);
        for (Class<?> service : realServices)
        {
            for (Field field : service.getDeclaredFields())
            {
                if (field.isAnnotationPresent(Autowired.class)) dependencies.add(field.getType());
            }
            for (Constructor<?> constructor : service.getDeclaredConstructors())
            {
                for (Parameter parameter : constructor.getParameters())
                {
                    if (!parameter.isAnnotationPresent(Value.class)) dependencies.add(parameter.getType());
                }
            }
        }
        for (Class<?> dependency : dependencies)
        {
            if (ObjectProvider.class.isAssignableFrom(dependency)
                    || Arrays.stream(realServices).anyMatch(dependency::isAssignableFrom)) continue;
            factory.registerSingleton("inert." + dependency.getName(), inert(dependency, unexpectedCalls));
        }
    }

    private Object inert(Class<?> type, List<Method> unexpectedCalls)
    {
        if (type.isInterface())
        {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                    (proxy, method, args) -> inertCall(type, proxy, method, args, unexpectedCalls));
        }
        // Mockito does not run the collaborator's constructor or Spring lifecycle.
        return Mockito.mock(type, invocation -> inertCall(type, invocation.getMock(),
                invocation.getMethod(), invocation.getArguments(), unexpectedCalls));
    }

    private Object inertCall(Class<?> type, Object proxy, Method method, Object[] args,
                             List<Method> unexpectedCalls)
    {
        if (method.getDeclaringClass() == Object.class)
        {
            if ("toString".equals(method.getName())) return "inert-" + type.getSimpleName();
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
        }
        // Exercise the real WechatPayServiceImpl @PostConstruct disabled branch;
        // no SDK construction, credential read, certificate download or provider call.
        if (type == WxPayConfig.class && "isWechatPayEnabled".equals(method.getName())) return false;
        unexpectedCalls.add(method);
        throw new AssertionError("Unexpected business/provider call in startup canary: " + method);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionProxyConfiguration
    {
    }
}
