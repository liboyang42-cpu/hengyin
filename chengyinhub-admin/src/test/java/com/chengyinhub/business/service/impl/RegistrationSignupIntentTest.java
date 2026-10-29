package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Producer decisions only. Manual synchronization here does not establish JDBC commit/rollback. */
class RegistrationSignupIntentTest {
    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrations;
    private CmsTopicMapper topics;
    private ISystemNotificationIntentService intents;
    private IImService im;
    private INotifyService wx;
    private CommittedBusinessNotice notices;
    private CmsRegistration paid;

    @BeforeEach void fixture() {
        service = new CmsRegistrationServiceImpl();
        registrations = mock(CmsRegistrationMapper.class);
        topics = mock(CmsTopicMapper.class);
        intents = mock(ISystemNotificationIntentService.class);
        im = mock(IImService.class);
        wx = mock(INotifyService.class);
        notices = mock(CommittedBusinessNotice.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(service, "imService", im);
        ReflectionTestUtils.setField(service, "notifyService", wx);
        ReflectionTestUtils.setField(service, "committedBusinessNotice", notices);
        paid = new CmsRegistration(); paid.setId(99L); paid.setMemberId(9L);
        paid.setOwnerType(1L); paid.setOwnerId(88L); paid.setPaymentStatus(2);
        when(registrations.selectCmsRegistrationById(99L)).thenReturn(paid);
        CmsTopic topic = new CmsTopic(); topic.setId(88L);
        when(topics.selectCmsTopicById(88L)).thenReturn(topic);
        when(intents.findIntentId(anyString())).thenReturn(null); // boxed Long default 0 is not absence
        when(intents.enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenReturn(501L);
        when(im.deliverSystemNotification(501L)).thenReturn(701L);
        doAnswer(call -> {
            if ("registration-signup".equals(call.getArgument(0))) ((Supplier<?>) call.getArgument(2)).get();
            return null;
        }).when(notices).dispatchInNewTransaction(anyString(), any(), any());
    }

    @AfterEach void releaseFixtureSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    private void register(List<Long> ids, Long member, boolean fresh) {
        ReflectionTestUtils.invokeMethod(service, "registerPaymentAfterCommit", ids, member, "原活动标题", fresh);
    }
    private void commitCallbacks() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCommit();
    }

    @Test void outsideTransactionRejectsBeforeIntentOrDelivery() {
        assertThrows(ServiceException.class, () -> register(List.of(99L), 9L, true));
        verifyNoInteractions(intents, im, wx, notices);
    }
    @Test void freshWinnerUsesOriginalFirstIdAndOneCardOnlyAfterCommit() {
        begin(); register(List.of(99L, 98L), 9L, true);
        verify(intents).enqueue(9L, ImMessage.MSG_CARD, "报名成功", "{\"cardType\":\"signup\",\"topicId\":88}", "registration-signup:99");
        verifyNoInteractions(im, wx, notices);
        commitCallbacks();
        verify(im, times(1)).deliverSystemNotification(501L);
        verify(im, never()).sendSystem(any(), any(), any(), any());
        verify(notices).dispatchInNewTransaction(eq("registration-qrcode"), eq(99L), any());
        verify(notices).dispatchInNewTransaction(eq("registration-qrcode"), eq(98L), any());
        verify(wx).notifySignupSuccess(9L, "原活动标题");
    }
    @Test void historyWithoutOriginalIntentNeverCreatesOrSendsSignup() {
        begin(); register(List.of(99L), 9L, false); commitCallbacks();
        verify(intents).findIntentId("registration-signup:99");
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        verifyNoInteractions(im, registrations, topics);
        verify(wx).notifySignupSuccess(9L, "原活动标题"); // existing WX branch is independent of the IM cut
    }
    @Test void historyWithOriginalIntentOnlyWakesThatIdentityWithoutLiveRebuild() {
        when(intents.findIntentId("registration-signup:99")).thenReturn(501L);
        begin(); register(List.of(99L), 9L, false); commitCallbacks();
        verify(im).deliverSystemNotification(501L);
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        verifyNoInteractions(registrations, topics);
    }
    @Test void existingFreshIntentKeepsFrozenPayloadDespiteLiveChanges() {
        when(intents.findIntentId("registration-signup:99")).thenReturn(501L);
        paid.setMemberId(90L); paid.setOwnerId(888L);
        begin(); register(List.of(99L), 9L, true); commitCallbacks();
        verify(im).deliverSystemNotification(501L);
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        verifyNoInteractions(registrations, topics);
    }
    @Test void missingOrMismatchedOriginalPaidRecipientRejectsBeforeEnqueue() {
        begin(); paid.setMemberId(19L);
        assertThrows(ServiceException.class, () -> register(List.of(99L), 9L, true));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        verifyNoInteractions(im, wx, notices);
    }
    @Test void invalidIdentityRejectsWithoutAnyIntentLookup() {
        begin();
        for (List<Long> ids : List.of(Collections.<Long>emptyList(), List.of(0L), Arrays.asList((Long) null)))
            assertThrows(ServiceException.class, () -> register(ids, 9L, true));
        assertThrows(ServiceException.class, () -> register(List.of(99L), 0L, true));
        verifyNoInteractions(intents, im, wx, notices);
    }
    @Test void enqueueConflictCannotRegisterSuccessWakeOrExternalActions() {
        when(intents.enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString()))
                .thenThrow(new ServiceException("原通知内容冲突"));
        begin(); assertThrows(ServiceException.class, () -> register(List.of(99L), 9L, true));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(im, wx, notices);
    }
    @Test void missingOrInvalidIntentReceiptCannotBecomeAcceptedWake() {
        when(intents.enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenReturn(null, 0L);
        begin();
        assertThrows(ServiceException.class, () -> register(List.of(99L), 9L, true));
        assertThrows(ServiceException.class, () -> register(List.of(99L), 9L, true));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(im, wx, notices);
    }
}
