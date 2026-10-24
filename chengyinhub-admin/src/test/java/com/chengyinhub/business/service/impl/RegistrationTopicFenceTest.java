package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Wiring/order unit tests only; physical MySQL exclusion is covered separately. */
class RegistrationTopicFenceTest {
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private RefundApplicationMapper refunds;
    private WxPaymentCallbackInboxMapper inbox;
    private CmsRegistration original;
    private WxPaymentCallbackInbox event;
    private WxPaymentCallbackMutationService callbacks;
    private IWxPaymentCallbackInboxService.Mutation mutation;
    private final WxPaymentCallbackClaim claim = WxPaymentCallbackClaim.acquired("hash", "owner", 1);

    @BeforeEach void setup() {
        registrations = mock(CmsRegistrationMapper.class); activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class); refunds = mock(RefundApplicationMapper.class);
        inbox = mock(WxPaymentCallbackInboxMapper.class);
        original = registration(1L, 11L); original.setOwnerType(1L); original.setOwnerId(7L);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(original);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(original);
        CmsTopic topic = new CmsTopic(); topic.setId(7L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        event = new WxPaymentCallbackInbox(); event.setBizType(1); event.setBizId(1L);
        when(inbox.selectOwnedForUpdate("hash", "owner", 1)).thenReturn(event);
        when(inbox.lockBusinessTarget(1, 1L)).thenReturn(1L);
        when(inbox.markDone("hash", "owner", 1)).thenReturn(1);
        callbacks = new WxPaymentCallbackMutationService();
        ReflectionTestUtils.setField(callbacks, "mapper", inbox);
        ReflectionTestUtils.setField(callbacks, "registrationMapper", registrations);
        ReflectionTestUtils.setField(callbacks, "activityMapper", activities);
        ReflectionTestUtils.setField(callbacks, "topicMapper", topics);
        ReflectionTestUtils.setField(callbacks, "refundMapper", refunds);
        mutation = mock(IWxPaymentCallbackInboxService.Mutation.class);
        when(mutation.isCurrent(event)).thenReturn(true); when(mutation.apply(event)).thenReturn(true);
    }

    private CmsRegistration registration(Long id, Long member) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setMemberId(member);
        row.setRegistrationNo("REG" + id); return row;
    }
    private CmsActivity activity(Long topic) {
        CmsActivity row = new CmsActivity(); row.setId(5L); row.setTopicId(topic); return row;
    }
    private RefundApplication refund(String amount) {
        RefundApplication row = new RefundApplication(); row.setId(9L); row.setSourceType("registration");
        row.setSourceId(1L); row.setRefundNo("RF1"); row.setMemberId(11L); row.setOwnerType(1L);
        row.setOwnerId(7L); row.setRefundAmount(new BigDecimal(amount)); row.setStatus(0); return row;
    }

    @Test void paymentInboxLocksTopicBeforeAnyRegistrationTargetAndBeforeMutation() {
        assertTrue(callbacks.apply(claim, mutation));
        var order = inOrder(inbox, topics, registrations, mutation);
        order.verify(inbox).selectOwnedForUpdate("hash", "owner", 1);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        order.verify(inbox).lockBusinessTarget(1, 1L);
        order.verify(mutation).apply(event); order.verify(inbox).markDone("hash", "owner", 1);
    }

    @Test void staleInboxOwnerNeverAcquiresBusinessFenceOrInvokesMutation() {
        when(inbox.selectOwnedForUpdate("hash", "owner", 1)).thenReturn(null);
        assertFalse(callbacks.apply(claim, mutation));
        verifyNoInteractions(registrations, topics, activities, refunds, mutation);
        verify(inbox, never()).lockBusinessTarget(anyInt(), anyLong());
    }

    @Test void activityUsesItsRealParentTopicThenActivityThenRegistration() {
        original.setOwnerType(2L); original.setOwnerId(5L);
        when(activities.selectCmsActivityById(5L)).thenReturn(activity(7L));
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenReturn(activity(7L));
        assertSame(original, RegistrationTopicFence.lock(original, registrations, activities, topics).registration());
        var order = inOrder(topics, activities, registrations);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        verify(topics, never()).selectCmsTopicByIdForUpdate(5L);
    }

    @Test void standaloneActivityNeverBorrowsANumericallyEqualTopicId() {
        original.setOwnerType(2L); original.setOwnerId(5L);
        when(activities.selectCmsActivityById(5L)).thenReturn(activity(null));
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenReturn(activity(null));
        assertSame(original, RegistrationTopicFence.lock(original, registrations, activities, topics).registration());
        verifyNoInteractions(topics);
    }

    @Test void changedActivityParentRejectsBeforeRegistrationOrBusinessWrite() {
        original.setOwnerType(2L); original.setOwnerId(5L);
        when(activities.selectCmsActivityById(5L)).thenReturn(activity(7L));
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenReturn(activity(8L));
        assertThrows(ServiceException.class, () -> RegistrationTopicFence.lock(original, registrations, activities, topics));
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
        verify(registrations, never()).claimPaymentSuccess(anyLong(), anyString(), any());
    }

    @Test void changedRegistrationMemberRejectsWithoutApplyingOrCompletingInbox() {
        CmsRegistration changed = registration(1L, 22L); changed.setOwnerType(1L); changed.setOwnerId(7L);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(changed);
        assertThrows(ServiceException.class, () -> callbacks.apply(claim, mutation));
        verifyNoInteractions(mutation); verify(inbox, never()).markDone(anyString(), anyString(), anyLong());
    }

    @Test void parentOrderCurrentRowsAreLockedTogetherBeforeTheSelectedRegistration() {
        original.setParentOrderNo("P1");
        CmsRegistration sibling = registration(2L, 12L); sibling.setOwnerType(1L); sibling.setOwnerId(7L); sibling.setParentOrderNo("P1");
        when(registrations.selectByParentOrderNoForUpdate("P1", null)).thenReturn(List.of(original, sibling));
        var locked = RegistrationTopicFence.lock(original, registrations, activities, topics);
        assertEquals(List.of(original, sibling), locked.rows());
        var order = inOrder(topics, registrations);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectByParentOrderNoForUpdate("P1", null);
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
    }

    @Test void foreignParentOrderMemberCannotBeSilentlyFilteredOut() {
        original.setParentOrderNo("P1");
        CmsRegistration foreign = registration(2L, 12L); foreign.setOwnerType(1L); foreign.setOwnerId(8L); foreign.setParentOrderNo("P1");
        when(registrations.selectByParentOrderNoForUpdate("P1", null)).thenReturn(List.of(original, foreign));
        assertThrows(ServiceException.class, () -> callbacks.apply(claim, mutation));
        verifyNoInteractions(mutation); verify(inbox, never()).markDone(anyString(), anyString(), anyLong());
    }

    @Test void refundInboxLocksOriginalRegistrationHistoryBeforeRefundTarget() {
        event.setBizType(2); event.setBizId(9L);
        when(refunds.selectRefundApplicationById(9L)).thenReturn(refund("10"));
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund("10.00")));
        when(inbox.lockBusinessTarget(2, 9L)).thenReturn(9L);
        assertTrue(callbacks.apply(claim, mutation));
        var order = inOrder(topics, registrations, refunds, inbox, mutation);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        order.verify(refunds).selectRegistrationHistoryForUpdate(1L);
        order.verify(inbox).lockBusinessTarget(2, 9L); order.verify(mutation).apply(event);
    }

    @Test void changedRefundAmountCannotBindCallbackToAnotherOriginal() {
        event.setBizType(2); event.setBizId(9L);
        when(refunds.selectRefundApplicationById(9L)).thenReturn(refund("10"));
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund("11")));
        assertThrows(IllegalStateException.class, () -> callbacks.apply(claim, mutation));
        verifyNoInteractions(mutation); verify(inbox, never()).lockBusinessTarget(anyInt(), anyLong());
    }

    @Test void failedMutationNeverWritesInboxDone() {
        when(mutation.apply(event)).thenReturn(false);
        assertThrows(WxPaymentCallbackMutationService.PaymentCallbackTransactionException.class,
                () -> callbacks.apply(claim, mutation));
        verify(inbox, never()).markDone(anyString(), anyString(), anyLong());
    }

    @Test void zeroPaymentRechecksOriginalMemberBeforeAnyPaymentCas() {
        CmsRegistrationServiceImpl payments = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(payments, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(payments, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(payments, "cmsTopicMapper", topics);
        when(registrations.selectByRegistrationNo("REG1")).thenReturn(original);
        CmsRegistration changed = registration(1L, 22L); changed.setOwnerType(1L); changed.setOwnerId(7L);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(changed);
        assertThrows(ServiceException.class, () -> payments.completeZeroAmountPayment("REG1"));
        verify(registrations, never()).claimPaymentSuccess(anyLong(), anyString(), any());
    }

    @Test void reviewLocksTopicAndCurrentRefundBeforeStatusCasWithoutChangingEligibility() {
        RefundServiceImpl review = new RefundServiceImpl();
        ReflectionTestUtils.setField(review, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(review, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(review, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(review, "cmsTopicMapper", topics);
        when(refunds.selectRefundApplicationById(9L)).thenReturn(refund("10"));
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund("10")));
        when(refunds.updateRefundStatusIfPending(eq(9L), eq(2), eq(21L), any(), eq("denied"))).thenReturn(1);
        RefundReviewDTO request = new RefundReviewDTO(); request.setId(9L); request.setStatus(2); request.setReviewReason("denied");
        assertEquals(1, review.reviewRefund(request, 21L));
        var order = inOrder(topics, registrations, refunds);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        order.verify(refunds).selectRegistrationHistoryForUpdate(1L);
        order.verify(refunds).updateRefundStatusIfPending(eq(9L), eq(2), eq(21L), any(), eq("denied"));
    }
}
