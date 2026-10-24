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
import java.util.concurrent.ScheduledExecutorService;

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
import com.chengyinhub.business.service.impl.WechatPaymentSdkGateway;
import com.chengyinhub.business.service.impl.WechatPaymentIntentStore;
import com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner;
import com.chengyinhub.business.service.impl.WechatPaymentCallbackRouter;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.impl.CoopSettlementClaimServiceImpl;
import com.chengyinhub.business.service.impl.CoopSettlementOriginalLedger;
import com.chengyinhub.business.service.impl.CoopClosedPaymentRecovery;
import com.chengyinhub.business.service.impl.WxPaymentCallbackOutboxServiceImpl;
import com.chengyinhub.business.mapper.WxPaymentCallbackOutboxMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.business.service.support.ChapterApplicationInviteNotice;
import com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper;
import com.chengyinhub.business.task.CommittedOfficialNoticeTask;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import com.chengyinhub.business.service.RegistrationSeatService;
import com.chengyinhub.business.service.RegistrationSeatSettlementReader;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;

import com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real service wiring with Boot 4's circular-reference policy and transaction AOP.
 * There is no component scan, Boot auto-configuration, application property loading,
 * database, Redis connection, scheduler, or external provider. Every collaborator
 * outside the named cycles and explicit payment/ledger/notice closure is an inert singleton;
 * even an unexpected call caught by production code is recorded and fails the test.
 */
class SupportedStackServiceCyclesTest
{
    private record Cycle(String name, Class<?> source, String field, Class<?> target,
                         Class<?>... services)
    {
        Cycle {
            if (Arrays.asList(services).contains(CmsTopicServiceImpl.class)) {
                List<Class<?>> expanded = new ArrayList<>(Arrays.asList(services));
                expanded.add(com.chengyinhub.business.service.impl.TopicPublicationService.class);
                expanded.add(com.chengyinhub.business.service.support.TopicPublicationContentGuard.class);
                expanded.add(com.chengyinhub.business.service.support.TopicSelfHostActivityService.class);
                services = expanded.toArray(Class<?>[]::new);
            }
        }

        boolean outboundSdkEdge() { return target == WechatPaymentSdkGateway.class; }
        boolean eagerAssemblyStarts() { return outboundSdkEdge()
                || "registration/completion/topic/payment".equals(name)
                || "topic/application/node".equals(name); }
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
                new Cycle("invite/payment", CoopInviteServiceImpl.class,
                        "wechatPayService", WechatPaymentSdkGateway.class,
                        CoopInviteServiceImpl.class, CoopDepositRefundService.class, WechatPayServiceImpl.class),
                new Cycle("merchant/payment", MerchantCommerceService.class,
                        "wechatPayService", WechatPaymentSdkGateway.class,
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
            Object managedTarget = context.getBean(cycle.target());
            if (cycle.eagerAssemblyStarts()) {
                // The SDK closure stays real even when this scenario's selected field is a lazy domain edge.
                assertThat(context).hasSingleBean(WechatPaymentSdkGateway.class);
                Object sdk = context.getBean(WechatPaymentSdkGateway.class);
                Object facade = AopTestUtils.getUltimateTargetObject(context.getBean(WechatPayServiceImpl.class));
                assertThat(ReflectionTestUtils.getField(facade, "gateway")).isSameAs(sdk);
                assertThat(Arrays.stream(WechatPaymentSdkGateway.class.getDeclaredFields())
                        .anyMatch(field -> field.getType() == com.chengyinhub.business.service.IWechatPayService.class
                                || field.getType() == WechatPaymentCallbackRouter.class)).isFalse();
            }
            if (cycle.outboundSdkEdge()) {
                // These selected fields are eager outbound SDK references, not lazy domain references.
                assertThat(lazyEdge).isSameAs(managedTarget);
                assertThat(unexpectedCalls).isEmpty();
                return;
            }
            assertThat(AopUtils.isAopProxy(lazyEdge)).isTrue();
            // Resolving a lazy reference must preserve the managed transaction proxy,
            // rather than introducing a raw/self-invoked business-service instance.
            assertThat(((Advised) lazyEdge).getTargetSource().getTarget()).isSameAs(managedTarget);
            assertThat(managedTarget).isInstanceOf(Advised.class);
            assertThat(Arrays.stream(((Advised) managedTarget).getAdvisors())
                    .anyMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor)).isTrue();
            assertThat(unexpectedCalls).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0} keeps the expected dependency contract with eager internal edges")
    @MethodSource("cycles")
    void eagerEdgesPreserveExpectedCycleOrOutboundBoundary(Cycle cycle)
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        runner(unexpectedCalls, cycle, cycle.services()).run(context -> {
            if (cycle.eagerAssemblyStarts()) {
                assertThat(context).hasNotFailed();
                assertThat(((DefaultListableBeanFactory) context.getBeanFactory())
                        .isAllowCircularReferences()).isFalse();
                for (Class<?> service : cycle.services()) {
                    assertThat(context).hasSingleBean(service);
                    Object target = AopTestUtils.getUltimateTargetObject(context.getBean(service));
                    assertThat(Mockito.mockingDetails(target).isMock()).as(service.getName()).isFalse();
                }
                Object source = AopTestUtils.getUltimateTargetObject(context.getBean(cycle.source()));
                Object managedTarget = context.getBean(cycle.target());
                assertThat(ReflectionTestUtils.getField(source, cycle.field())).isSameAs(managedTarget);
                if (!cycle.outboundSdkEdge()) {
                    // A formerly cyclic assembly can start eagerly while its domain target still needs transaction AOP.
                    assertThat(managedTarget).isInstanceOf(Advised.class);
                    assertThat(Arrays.stream(((Advised) managedTarget).getAdvisors())
                            .anyMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor)).isTrue();
                    Object target = AopTestUtils.getUltimateTargetObject(managedTarget);
                    assertThat(ReflectionTestUtils.getField(target, "topicService"))
                            .isSameAs(context.getBean(CmsTopicServiceImpl.class));
                    Object topic = AopTestUtils.getUltimateTargetObject(context.getBean(CmsTopicServiceImpl.class));
                    assertThat(ReflectionTestUtils.getField(topic, "wechatPayService"))
                            .isSameAs(context.getBean(WechatPaymentSdkGateway.class));
                }
            } else {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
            }
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

    @Test
    void paymentFactsOriginalLedgerAndTypedNoticesUseRealManagedDependencies()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        Class<?>[] services = Stream.concat(cycles().flatMap(cycle -> Arrays.stream(cycle.services())),
                Stream.of(RefundServiceImpl.class, CoopSettlementClaimServiceImpl.class,
                        CommittedOfficialNoticeService.class, CommittedOfficialNoticeTask.class))
                .distinct().toArray(Class<?>[]::new);
        runner(unexpectedCalls, null, services).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> service : List.of(WechatVerifiedPaymentFactService.class,
                    JdbcWechatPaymentOrderOwner.class, WechatPaymentSdkGateway.class, CoopSettlementClaimServiceImpl.class,
                    CoopSettlementOriginalLedger.class, CoopClosedPaymentRecovery.class,
                    WxPaymentCallbackOutboxServiceImpl.class, SettlementDebtLedger.class,
                    RegistrationPaymentConsumptionReadService.class,
                    CommittedBusinessNotice.class, CommittedOfficialNoticeService.class,
                    CommittedOfficialNoticeTask.class, ChapterApplicationInviteNotice.class,
                    WxSubscribeMsgServiceImpl.class, ImServiceImpl.class, NotifyServiceImpl.class,
                    InAppNotifyChannel.class, CmsRegistrationServiceImpl.class,
                    TopicSettlementEvidenceService.class, RegistrationSeatService.class,
                    RegistrationSeatSettlementReader.class))
            {
                assertThat(context).hasSingleBean(service);
                Object target = AopTestUtils.getUltimateTargetObject(context.getBean(service));
                assertThat(Mockito.mockingDetails(target).isMock()).as(service.getName()).isFalse();
            }
            Object facts = context.getBean(WechatVerifiedPaymentFactService.class);
            Object reader = AopTestUtils.getUltimateTargetObject(facts);
            assertThat(AopUtils.isAopProxy(facts)).isTrue();
            assertThat(ReflectionTestUtils.getField(reader, "mapper"))
                    .isSameAs(context.getBean(WechatVerifiedPaymentFactMapper.class));
            // B f6f382 reader: the existing PTM is required, not another mocked fact service.
            assertThat(ReflectionTestUtils.getField(reader, "factReadTransactions"))
                    .isSameAs(context.getBean(PlatformTransactionManager.class));
            // B 9d592: the write lane uses the real row-lock owner, never an inert domain success stub.
            assertThat(ReflectionTestUtils.getField(reader, "factWriteTransactions"))
                    .isSameAs(context.getBean(PlatformTransactionManager.class));
            assertThat(ReflectionTestUtils.getField(reader, "factBusinessOwner"))
                    .isSameAs(context.getBean(JdbcWechatPaymentOrderOwner.class));
            assertThat(ReflectionTestUtils.getField(reader, "factIntentJdbc"))
                    .isSameAs(context.getBean(JdbcTemplate.class));
            Object deposit = AopTestUtils.getUltimateTargetObject(context.getBean(CoopInviteServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(deposit, "depositPaymentTransactions"))
                    .isSameAs(context.getBean(PlatformTransactionManager.class));
            assertThat(ReflectionTestUtils.getField(deposit, "depositVerifiedFacts")).isSameAs(facts);
            Object sdk = AopTestUtils.getUltimateTargetObject(context.getBean(WechatPaymentSdkGateway.class));
            assertThat(ReflectionTestUtils.getField(sdk, "verifiedFacts")).isSameAs(facts);
            Object claim = AopTestUtils.getUltimateTargetObject(context.getBean(CoopSettlementClaimServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(claim, "evidenceService"))
                    .isSameAs(context.getBean(TopicSettlementEvidenceService.class));
            Object seats = AopTestUtils.getUltimateTargetObject(context.getBean(RegistrationSeatService.class));
            assertThat(ReflectionTestUtils.getField(seats, "registrationService"))
                    .isSameAs(context.getBean(CmsRegistrationServiceImpl.class));
            Object seatReader = context.getBean(RegistrationSeatSettlementReader.class);
            Object evidence = AopTestUtils.getUltimateTargetObject(context.getBean(TopicSettlementEvidenceService.class));
            assertThat(ReflectionTestUtils.getField(evidence, "registrationSeats")).isSameAs(seatReader);
            assertThat(ReflectionTestUtils.getField(seats, "settlementReader")).isSameAs(seatReader);
            Object seatReaderTarget = AopTestUtils.getUltimateTargetObject(seatReader);
            assertThat(ReflectionTestUtils.getField(seatReaderTarget, "seats"))
                    .isSameAs(context.getBean(RegistrationSeatMapper.class));
            assertThat(seatReader).isInstanceOf(Advised.class);
            TransactionInterceptor seatTransaction = Arrays.stream(((Advised) seatReader).getAdvisors())
                    .map(advisor -> advisor.getAdvice()).filter(TransactionInterceptor.class::isInstance)
                    .map(TransactionInterceptor.class::cast).findFirst().orElseThrow();
            Method seatRead = org.springframework.util.ReflectionUtils.findMethod(RegistrationSeatSettlementReader.class,
                    "verifiedSeatsForGroup", Long.class, Long.class, java.util.Date.class);
            assertThat(seatRead).isNotNull();
            assertThat(seatTransaction.getTransactionAttributeSource()).isNotNull();
            var seatTransactionContract = seatTransaction.getTransactionAttributeSource()
                    .getTransactionAttribute(seatRead, RegistrationSeatSettlementReader.class);
            assertThat(seatTransactionContract).isNotNull();
            assertThat(seatTransactionContract.getPropagationBehavior())
                    .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY);
            Object ledger = context.getBean(CoopSettlementOriginalLedger.class);
            assertThat(ReflectionTestUtils.getField(claim, "originalLedger")).isSameAs(ledger);
            Object recovery = context.getBean(CoopClosedPaymentRecovery.class);
            Object recoveryTarget = AopTestUtils.getUltimateTargetObject(recovery);
            assertThat(ReflectionTestUtils.getField(claim, "closedPaymentRecovery")).isSameAs(recovery);
            assertThat(ReflectionTestUtils.getField(recoveryTarget, "facts")).isSameAs(facts);
            assertThat(ReflectionTestUtils.getField(recoveryTarget, "outbox"))
                    .isSameAs(context.getBean(WxPaymentCallbackOutboxServiceImpl.class));
            ObjectProvider<?> recoverySelf = (ObjectProvider<?>) ReflectionTestUtils.getField(recoveryTarget, "self");
            assertThat(recoverySelf).isNotNull();
            assertThat(recoverySelf.getObject()).isSameAs(recovery);
            Object outbox = AopTestUtils.getUltimateTargetObject(context.getBean(WxPaymentCallbackOutboxServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(outbox, "mapper"))
                    .isSameAs(context.getBean(WxPaymentCallbackOutboxMapper.class));
            assertThat(ReflectionTestUtils.getField(AopTestUtils.<Object>getUltimateTargetObject(ledger), "verifiedFacts"))
                    .isSameAs(facts);
            Object consumptions = context.getBean(RegistrationPaymentConsumptionReadService.class);
            assertThat(ReflectionTestUtils.getField(AopTestUtils.<Object>getUltimateTargetObject(ledger), "paymentConsumptions"))
                    .isSameAs(consumptions);
            assertThat(ReflectionTestUtils.getField(AopTestUtils.<Object>getUltimateTargetObject(consumptions), "mapper"))
                    .isSameAs(context.getBean(CmsRegistrationPaymentFactMapper.class));
            Object registration = AopTestUtils.getUltimateTargetObject(context.getBean(CmsRegistrationServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(registration, "coopSettlementClaimService"))
                    .isSameAs(context.getBean(CoopSettlementClaimServiceImpl.class));
            Object refund = AopTestUtils.getUltimateTargetObject(context.getBean(RefundServiceImpl.class));
            // Ae6's actual new edges must resolve eagerly to the same managed evidence bean.
            assertThat(ReflectionTestUtils.getField(registration, "verifiedPaymentFacts")).isSameAs(facts);
            assertThat(ReflectionTestUtils.getField(refund, "verifiedPaymentFacts")).isSameAs(facts);
            assertThat(ReflectionTestUtils.getField(refund, "coopSettlementClaimService"))
                    .isSameAs(context.getBean(CoopSettlementClaimServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(registration, "systemNotificationIntents"))
                    .isSameAs(context.getBean(ImServiceImpl.class))
                    .isSameAs(context.getBean(ISystemNotificationIntentService.class));
            Object notice = AopTestUtils.getUltimateTargetObject(context.getBean(CommittedBusinessNotice.class));
            assertThat(ReflectionTestUtils.getField(notice, "notifyService"))
                    .isSameAs(context.getBean(NotifyServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(notice, "existingExecutor"))
                    .isSameAs(context.getBean("scheduledExecutorService"));
            TransactionTemplate transaction = (TransactionTemplate) ReflectionTestUtils.getField(notice, "independentTransaction");
            assertThat(transaction).isNotNull();
            assertThat(transaction.getTransactionManager()).isSameAs(context.getBean(PlatformTransactionManager.class));
            Object channel = AopTestUtils.getUltimateTargetObject(context.getBean(InAppNotifyChannel.class));
            assertThat(ReflectionTestUtils.getField(channel, "imService")).isSameAs(context.getBean(ImServiceImpl.class));
            Object official = AopTestUtils.getUltimateTargetObject(context.getBean(CommittedOfficialNoticeService.class));
            assertThat(ReflectionTestUtils.getField(official, "mapper"))
                    .isSameAs(context.getBean(CommittedOfficialNoticeWxMapper.class));
            assertThat(ReflectionTestUtils.getField(official, "intents")).isSameAs(context.getBean(ImServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(official, "im")).isSameAs(context.getBean(ImServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(official, "wx")).isSameAs(context.getBean(WxSubscribeChannel.class));
            assertThat(ReflectionTestUtils.getField(official, "beforeStartWx"))
                    .isSameAs(context.getBean(WxSubscribeMsgServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(official, "executor"))
                    .isSameAs(context.getBean("scheduledExecutorService"));
            TransactionTemplate officialTransaction = (TransactionTemplate) ReflectionTestUtils.getField(official, "independent");
            assertThat(officialTransaction).isNotNull();
            assertThat(officialTransaction.getTransactionManager()).isSameAs(context.getBean(PlatformTransactionManager.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(CommittedOfficialNoticeTask.class), "notices"))
                    .isSameAs(context.getBean(CommittedOfficialNoticeService.class));
            Object chapterApplication = AopTestUtils.getUltimateTargetObject(context.getBean(MerchantChapterApplicationServiceImpl.class));
            Object invitation = context.getBean(ChapterApplicationInviteNotice.class);
            assertThat(ReflectionTestUtils.getField(chapterApplication, "inviteNotice")).isSameAs(invitation);
            Object invitationTarget = AopTestUtils.getUltimateTargetObject(invitation);
            assertThat(ReflectionTestUtils.getField(invitationTarget, "notices"))
                    .isSameAs(context.getBean(CommittedOfficialNoticeService.class));
            // D fe32: these are required Spring collaborators; SQL/diagnostic effects remain explicit inert boundaries.
            assertThat(ReflectionTestUtils.getField(chapterApplication, "auditTaskMapper"))
                    .isSameAs(context.getBean(AuditTaskMapper.class));
            assertThat(ReflectionTestUtils.getField(invitationTarget, "audit"))
                    .isSameAs(context.getBean(BusinessAuditEventMapper.class));
            assertThat(ReflectionTestUtils.getField(invitationTarget, "alarm"))
                    .isSameAs(context.getBean(AlarmService.class));
            assertThat(ReflectionTestUtils.getField(invitationTarget, "executor"))
                    .isSameAs(context.getBean("scheduledExecutorService"));
            assertThat(unexpectedCalls).isEmpty();
        });
    }

    static Stream<Class<?>> requiredFactCollaborators()
    {
        return Stream.of(WechatVerifiedPaymentFactMapper.class, PlatformTransactionManager.class);
    }

    @ParameterizedTest(name = "real fact reader cannot start without {0}")
    @MethodSource("requiredFactCollaborators")
    void realFactReaderDoesNotHideMissingMapperOrTransactionManager(Class<?> dependency)
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        runner(unexpectedCalls, null, WechatVerifiedPaymentFactService.class)
                .withInitializer(context -> ((DefaultListableBeanFactory) context.getBeanFactory())
                        .destroySingleton("inert." + dependency.getName()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining(dependency.getName());
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    @Test
    void realOfficialNoticeCannotStartWithoutItsQualifiedExistingScheduler()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        runner(unexpectedCalls, null, CommittedOfficialNoticeService.class)
                .withInitializer(context -> ((DefaultListableBeanFactory) context.getBeanFactory())
                        .destroySingleton("scheduledExecutorService"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining(ScheduledExecutorService.class.getName())
                            .hasStackTraceContaining("scheduledExecutorService");
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    @Test
    void factReaderCallingBackIntoRealSdkFailsWithCircularReferencesDisabled()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        boolean[] replaced = { false };
        runner(unexpectedCalls, null, WechatPayServiceImpl.class)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    for (String name : factory.getBeanDefinitionNames())
                    {
                        var definition = factory.getBeanDefinition(name);
                        if (WechatVerifiedPaymentFactService.class.getName().equals(definition.getBeanClassName()))
                        {
                            // Same real reader superclass/mapper/PTM/AOP, plus one forbidden eager reverse edge.
                            ((AbstractBeanDefinition) definition).setBeanClass(FactReaderCallingSdk.class);
                            replaced[0] = true;
                        }
                    }
                }))
                .run(context -> {
                    assertThat(replaced[0]).isTrue();
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    @Test
    void invitationNoticeCallingBackIntoItsRealApplicationFailsAsACycle()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        boolean[] replaced = { false };
        runner(unexpectedCalls, null, MerchantChapterApplicationServiceImpl.class)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    for (String name : factory.getBeanDefinitionNames())
                    {
                        var definition = factory.getBeanDefinition(name);
                        if (ChapterApplicationInviteNotice.class.getName().equals(definition.getBeanClassName()))
                        {
                            ((AbstractBeanDefinition) definition).setBeanClass(InviteNoticeCallingApplication.class);
                            replaced[0] = true;
                        }
                    }
                }))
                .run(context -> {
                    assertThat(replaced[0]).isTrue();
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    @Test
    void settlementEvidenceCallingSeatWriterRecreatesTheRealRegistrationCycle()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        boolean[] replaced = { false };
        runner(unexpectedCalls, null, CmsRegistrationServiceImpl.class,
                CoopSettlementClaimServiceImpl.class, RegistrationSeatService.class)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    for (String name : factory.getBeanDefinitionNames())
                    {
                        var definition = factory.getBeanDefinition(name);
                        if (TopicSettlementEvidenceService.class.getName().equals(definition.getBeanClassName()))
                        {
                            ((AbstractBeanDefinition) definition).setBeanClass(EvidenceCallingSeatWriter.class);
                            replaced[0] = true;
                        }
                    }
                }))
                .run(context -> {
                    assertThat(replaced[0]).isTrue();
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class)
                            .hasStackTraceContaining("forbiddenSeatWriter");
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    @Test
    void settlementSeatReaderCallingRegistrationWriterRecreatesTheRealCycle()
    {
        List<Method> unexpectedCalls = new ArrayList<>();
        boolean[] replaced = { false };
        runner(unexpectedCalls, null, CmsRegistrationServiceImpl.class, CoopSettlementClaimServiceImpl.class)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    for (String name : factory.getBeanDefinitionNames())
                    {
                        var definition = factory.getBeanDefinition(name);
                        if (RegistrationSeatSettlementReader.class.getName().equals(definition.getBeanClassName()))
                        {
                            ((AbstractBeanDefinition) definition).setBeanClass(SeatReaderCallingRegistration.class);
                            replaced[0] = true;
                        }
                    }
                }))
                .run(context -> {
                    assertThat(replaced[0]).isTrue();
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class)
                            .hasStackTraceContaining("forbiddenRegistrationWriter");
                    assertThat(unexpectedCalls).isEmpty();
                });
    }

    static class SeatReaderCallingRegistration extends RegistrationSeatSettlementReader
    {
        SeatReaderCallingRegistration(RegistrationSeatMapper seats) { super(seats); }

        @Autowired
        private com.chengyinhub.business.service.ICmsRegistrationService forbiddenRegistrationWriter;
    }

    static class EvidenceCallingSeatWriter extends TopicSettlementEvidenceService
    {
        EvidenceCallingSeatWriter(com.chengyinhub.business.mapper.CmsActivityMapper activities,
                                  com.chengyinhub.business.mapper.CmsRegistrationMapper registrations,
                                  com.chengyinhub.business.mapper.CoopGroupVerifyMapper groups,
                                  com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper evidence)
        {
            super(activities, registrations, groups, evidence);
        }

        @Autowired
        private RegistrationSeatService forbiddenSeatWriter;
    }

    static class InviteNoticeCallingApplication extends ChapterApplicationInviteNotice
    {
        InviteNoticeCallingApplication(CommittedOfficialNoticeService notices) { super(notices); }
        @Autowired
        private MerchantChapterApplicationServiceImpl forbiddenApplicationBackReference;
    }

    static class FactReaderCallingSdk extends WechatVerifiedPaymentFactService
    {
        @Autowired
        private WechatPaymentSdkGateway forbiddenSdkBackReference;
    }

    private Class<?>[] realDependencyClosure(Class<?>... realServices)
    {
        Set<Class<?>> assembly = new LinkedHashSet<>(Arrays.asList(realServices));
        if (assembly.contains(CmsTopicServiceImpl.class)) assembly.add(WechatPayServiceImpl.class);
        if (assembly.contains(WechatPayServiceImpl.class))
        {
            assembly.addAll(List.of(WechatPaymentSdkGateway.class, WechatPaymentIntentStore.class,
                    JdbcWechatPaymentOrderOwner.class, WechatPaymentCallbackRouter.class));
        }
        if (assembly.contains(WechatPaymentSdkGateway.class) || assembly.contains(CmsRegistrationServiceImpl.class)
                || assembly.contains(RefundServiceImpl.class) || assembly.contains(CoopInviteServiceImpl.class)
                || assembly.contains(CmsTopicServiceImpl.class))
        {
            assembly.add(WechatVerifiedPaymentFactService.class);
        }
        if (assembly.contains(CmsRegistrationServiceImpl.class)
                || assembly.contains(RefundServiceImpl.class) || assembly.contains(CoopSettlementClaimServiceImpl.class)
                || assembly.contains(CoopSettlementOriginalLedger.class))
        {
            assembly.addAll(List.of(CoopSettlementClaimServiceImpl.class, CoopSettlementOriginalLedger.class,
                    TopicSettlementEvidenceService.class, RegistrationSeatService.class, RegistrationSeatSettlementReader.class,
                    CmsRegistrationServiceImpl.class, SettlementDebtLedger.class,
                    WechatVerifiedPaymentFactService.class, RegistrationPaymentConsumptionReadService.class,
                    CoopClosedPaymentRecovery.class, WxPaymentCallbackOutboxServiceImpl.class));
        }
        if (assembly.contains(MerchantChapterApplicationServiceImpl.class) || assembly.contains(ChapterApplicationInviteNotice.class))
        {
            assembly.addAll(List.of(ChapterApplicationInviteNotice.class, CommittedOfficialNoticeService.class));
        }
        if (assembly.contains(CommittedOfficialNoticeTask.class))
        {
            assembly.add(CommittedOfficialNoticeService.class);
        }
        if (assembly.contains(CommittedOfficialNoticeService.class))
        {
            assembly.addAll(List.of(ImServiceImpl.class, WxSubscribeChannel.class, WxSubscribeMsgServiceImpl.class));
        }
        if (assembly.contains(CmsRegistrationServiceImpl.class) || assembly.contains(MerchantChapterApplicationServiceImpl.class)
                || assembly.contains(ChapterMerchantNodeServiceImpl.class) || assembly.contains(CommittedBusinessNotice.class))
        {
            assembly.addAll(List.of(CommittedBusinessNotice.class, NotifyServiceImpl.class,
                    InAppNotifyChannel.class, WxSubscribeChannel.class, ImServiceImpl.class));
        }
        // All fact entry points, including the standalone missing-collaborator controls, require the real owner.
        if (assembly.contains(WechatVerifiedPaymentFactService.class))
        {
            assembly.add(JdbcWechatPaymentOrderOwner.class);
        }
        return assembly.toArray(Class<?>[]::new);
    }

    private ApplicationContextRunner runner(List<Method> unexpectedCalls, Cycle eagerCycle,
                                             Class<?>... realServices)
    {
        Class<?>[] assembly = realDependencyClosure(realServices);
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    DefaultListableBeanFactory factory =
                            (DefaultListableBeanFactory) context.getBeanFactory();
                    // ApplicationContextRunner does not apply SpringApplication defaults.
                    factory.setAllowCircularReferences(false);
                    registerInertCollaborators(factory, unexpectedCalls, assembly);
                    if (eagerCycle != null)
                    {
                        context.addBeanFactoryPostProcessor(ignored ->
                                factory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver()
                                {
                                    @Override
                                    protected boolean isLazy(DependencyDescriptor descriptor)
                                    {
                                        Field field = descriptor.getField();
                                        // Every lazy edge in this real cycle must be eager; another edge can still break the loop.
                                        if (field != null && field.isAnnotationPresent(org.springframework.context.annotation.Lazy.class)
                                                && Arrays.asList(eagerCycle.services()).contains(field.getDeclaringClass())
                                                && Arrays.stream(eagerCycle.services())
                                                        .anyMatch(field.getType()::isAssignableFrom))
                                        {
                                            return false;
                                        }
                                        return super.isLazy(descriptor);
                                    }
                                }));
                    }
                })
                .withUserConfiguration(TransactionProxyConfiguration.class)
                .withUserConfiguration(assembly);
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
            // Real CommittedBusinessNotice uses this exact existing executor qualifier.
            // It remains an inert external boundary: no threads, scheduling or provider calls.
            String name = dependency == ScheduledExecutorService.class
                    ? "scheduledExecutorService" : "inert." + dependency.getName();
            factory.registerSingleton(name, inert(dependency, unexpectedCalls));
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
        // Exercise the real WechatPaymentSdkGateway @PostConstruct disabled branch;
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
