package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationSignupNotificationServiceTest {
    private RegistrationSignupNotificationService service;
    private ICmsActivityService activities;
    private ISystemNotificationIntentService intents;
    @BeforeEach void setup() {
        service = new RegistrationSignupNotificationService();
        activities = mock(ICmsActivityService.class); intents = mock(ISystemNotificationIntentService.class);
        ReflectionTestUtils.setField(service, "activityService", activities);
        ReflectionTestUtils.setField(service, "notificationIntentService", intents);
    }
    private CmsRegistration registration(long ownerType) {
        CmsRegistration r = new CmsRegistration(); r.setId(8L); r.setMemberId(7L);
        r.setOwnerType(ownerType); r.setOwnerId(9L); return r;
    }
    @Test void firstActivitySignupPersistsOriginalKeyAndPayload() {
        CmsActivity activity = new CmsActivity(); activity.setId(9L); activity.setTopicId(10L);
        when(activities.selectCmsActivityById(9L)).thenReturn(activity);
        when(intents.enqueue(eq(7L),eq(ImMessage.MSG_CARD),eq("报名成功"),anyString(),eq("registration-signup:8"))).thenReturn(100L);
        service.recordIntent(7L, registration(2L));
        verify(intents).enqueue(7L, ImMessage.MSG_CARD, "报名成功", "{\"cardType\":\"signup\",\"topicId\":10}", "registration-signup:8");
    }
    @Test void selfPlaySignupKeepsOriginalGenericNotice() {
        when(intents.enqueue(eq(7L),anyInt(),anyString(),anyString(),anyString())).thenReturn(100L);
        service.recordIntent(7L, registration(1L));
        verify(intents).enqueue(7L, ImMessage.MSG_CARD, "报名成功", RegistrationCheckoutServiceImpl.signupNoticeExtra(null), "registration-signup:8");
        verify(activities, never()).selectCmsActivityById(any());
    }
    @Test void failedIntentThrowsAndCannotReportSuccessfulSignupIntent() {
        assertThrows(IllegalStateException.class, () -> service.recordIntent(7L, registration(1L)));
    }
    @Test void differentRecipientCannotReuseOriginalRegistration() {
        assertThrows(IllegalArgumentException.class, () -> service.recordIntent(6L, registration(2L)));
        verify(intents, never()).enqueue(any(),any(),any(),any(),any());
    }
    @Test void replayReadsOriginalIntentAndDoesNotRebuildLiveActivityPayload() {
        when(intents.findIntentId("registration-signup:8")).thenReturn(100L);
        service.recordIntent(7L, registration(2L));
        verify(activities, never()).selectCmsActivityById(any());
        verify(intents, never()).enqueue(any(),any(),any(),any(),any());
    }
    @Test void notificationPersistenceFailureRollsBackOriginalSignupTransaction() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:signup_intent_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("create table original_signup(id bigint primary key)");
        jdbc.execute("create table signup_intent(event_key varchar(100) primary key)");
        org.springframework.jdbc.datasource.DataSourceTransactionManager transactions =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        RegistrationSignupNotificationService transactional = (RegistrationSignupNotificationService)proxy.getProxy();
        when(intents.enqueue(any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            jdbc.update("insert into signup_intent values(?)", invocation.<String>getArgument(4));
            throw new IllegalStateException("persistence response failure");
        });
        assertThrows(IllegalStateException.class, () ->
                new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                    jdbc.update("insert into original_signup values(8)");
                    transactional.recordIntent(7L, registration(1L));
                    return null;
                }));
        assertEquals(0,jdbc.queryForObject("select count(*) from original_signup",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from signup_intent",Integer.class));
    }
    @Test void signupIntentRequiresExistingBusinessTransaction() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:signup_mandatory_" + System.nanoTime());
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        RegistrationSignupNotificationService transactional = (RegistrationSignupNotificationService)proxy.getProxy();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> transactional.recordIntent(7L,registration(1L)));
        verify(intents,never()).enqueue(any(),any(),any(),any(),any());
    }
}
