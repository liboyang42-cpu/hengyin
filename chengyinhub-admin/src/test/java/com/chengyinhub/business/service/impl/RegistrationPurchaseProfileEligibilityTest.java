package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** R29 F01: real checkout/order/gate code, mocked persistence/provider ports; no payment acceptance proof. */
class RegistrationPurchaseProfileEligibilityTest {
    private static final long MEMBER = 7L, REG = 77L;
    private final UmsMemberMapper members = mock(UmsMemberMapper.class);
    private final UmsMemberAddressMapper participants = mock(UmsMemberAddressMapper.class);
    private final ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
    private final IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
    private final IWechatPayService payment = mock(IWechatPayService.class);
    private final ICmsTopicService topics = mock(ICmsTopicService.class);
    private RegistrationOrderCreateGateImpl gate;
    private RegistrationCheckoutServiceImpl checkout;
    private UmsMember member;
    private CmsRegistration original;
    private RegistrationRequest request;

    @BeforeEach
    void fixture() {
        member = new UmsMember(); member.setId(MEMBER); member.setPhone("13800001111");
        member.setName("城瘾用户"); member.setNickname("显示昵称");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(participants.selectOwnedAddressList(MEMBER)).thenReturn(List.of(participant(MEMBER, "报名人", 0)));
        gate = new RegistrationOrderCreateGateImpl();
        ReflectionTestUtils.setField(gate, "umsMemberMapper", members);
        ReflectionTestUtils.setField(gate, "umsMemberAddressMapper", participants);
        ReflectionTestUtils.setField(gate, "registrationService", registrations);
        ReflectionTestUtils.setField(gate, "topicService", topics);
        ReflectionTestUtils.setField(gate, "wechatPayService", payment);
        ReflectionTestUtils.setField(gate, "merchantBusinessStatusService", mock(IMerchantBusinessStatusService.class));
        ReflectionTestUtils.setField(gate, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(gate, "capabilityService", mock(ICapabilityService.class));
        CmsTopic topic = new CmsTopic(); topic.setSelfPlayPrice(BigDecimal.ZERO);
        when(topics.selectCmsTopicById(99L)).thenReturn(topic);
        RegistrationOrderServiceImpl orders = new RegistrationOrderServiceImpl();
        ReflectionTestUtils.setField(orders, "registrationService", registrations);
        ReflectionTestUtils.setField(orders, "registrationCreationTransaction", creation);
        ReflectionTestUtils.setField(orders, "registrationOrderCreateGate", gate);
        checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrations);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", orders);
        ReflectionTestUtils.setField(checkout, "registrationOrderCreateGate", gate);
        ReflectionTestUtils.setField(checkout, "wechatPayService", payment);
        request = new RegistrationRequest(); request.setOwnerType(1); request.setOwnerId(99L);
        request.setRequestId("r29-f01"); request.setRealName("报名人"); request.setPhone("13800001111");
        original = new CmsRegistration(); original.setId(REG); original.setMemberId(MEMBER);
        original.setOwnerType(1L); original.setRealName("报名人"); original.setRegistrationNo("R29-F01-77");
        original.setPaymentStatus(0); original.setPayableAmount(BigDecimal.ZERO);
        when(creation.create(eq(MEMBER), same(request), anyString(), anyString())).thenReturn(List.of(original));
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(original);
        when(registrations.completeZeroAmountPaymentForRetry(REG, MEMBER)).thenReturn(null);
        when(registrations.completeZeroAmountPayment("R29-F01-77")).thenReturn("SUCCESS");
        when(registrations.beginPaymentAttempt(REG, MEMBER)).thenReturn(original);
        when(payment.isReady()).thenReturn(true);
        when(payment.createOrder(original)).thenReturn(Map.of("channel", "JSAPI"));
        when(payment.createAppOrder(original)).thenReturn(Map.of("channel", "APP"));
    }

    @Test
    void submittedPhoneCannotReplaceMissingOrBlankBoundPhoneForEitherPurchaseKind() {
        for (String phone : new String[]{null, "", "   "}) {
            member.setPhone(phone);
            for (int ownerType : new int[]{1, 2}) {
                request.setOwnerType(ownerType);
                RegistrationCheckoutException rejected = assertThrows(RegistrationCheckoutException.class,
                        () -> checkout.createOrReplay(MEMBER, request));
                assertEquals(400, rejected.getCode());
                assertEquals("请先绑定手机号，再购买", rejected.getMessage());
            }
        }
        verifyNoInteractions(creation, payment);
        verify(registrations, never()).completeZeroAmountPayment(anyString());
    }

    @Test
    void nicknameAndAutogeneratedMemberNameDoNotReplaceASavedParticipant() {
        when(participants.selectOwnedAddressList(MEMBER)).thenReturn(List.of());
        for (String name : new String[]{"报名人", "显示昵称", "城瘾用户"}) {
            request.setRealName(name);
            assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
        }
        verifyNoInteractions(creation, payment);
    }

    @Test
    void wrongOwnerDeletedBlankOrMismatchedParticipantCannotSatisfyTheNameGate() {
        for (UmsMemberAddress participant : List.of(participant(8L, "报名人", 0),
                participant(MEMBER, "报名人", 2), participant(MEMBER, "   ", 0),
                participant(MEMBER, "另一个人", 0))) {
            when(participants.selectOwnedAddressList(MEMBER)).thenReturn(List.of(participant));
            assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
        }
        verifyNoInteractions(creation, payment);
    }

    @Test
    void missingMemberOrFailedProfileReadCannotCreateAnOrder() {
        when(members.selectUmsMemberById(MEMBER)).thenReturn(null);
        assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
        when(members.selectUmsMemberById(MEMBER)).thenThrow(new IllegalStateException("profile read failed"));
        assertThrows(IllegalStateException.class, () -> checkout.createOrReplay(MEMBER, request));
        verifyNoInteractions(creation, payment);
    }

    @Test
    void validSavedParticipantAndBoundPhoneAllowFreeAndPaidCreationOnBothChannels() {
        checkout.createOrReplay(MEMBER, request);
        verify(registrations).completeZeroAmountPayment("R29-F01-77");
        original.setPayableAmount(new BigDecimal("19.90"));
        assertEquals(Map.of("channel", "JSAPI"), checkout.createOrReplay(MEMBER, request).getData().get("payParams"));
        request.setPayChannel("APP");
        assertEquals(Map.of("channel", "APP"), checkout.createOrReplay(MEMBER, request).getData().get("payParams"));
        assertEquals(new BigDecimal("19.90"), original.getPayableAmount());
        assertEquals("R29-F01-77", original.getRegistrationNo());
        verify(members, times(3)).selectUmsMemberById(MEMBER);
    }

    @Test
    void unpaidRequestReplayCannotCompleteAFreeOrderWithMissingProfile() {
        IRegistrationOrderService replay = mock(IRegistrationOrderService.class);
        when(replay.createOrReplay(MEMBER, request)).thenReturn(new RegistrationOrderResult(List.of(original), true));
        ReflectionTestUtils.setField(checkout, "registrationOrderService", replay);
        member.setPhone("");
        assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
        verify(registrations, never()).completeZeroAmountPayment(anyString());
        verifyNoInteractions(payment);
    }

    @Test
    void unpaidPaidReplayAlsoRejectsMissingProfileWithoutReissuingPayment() {
        original.setPayableAmount(new BigDecimal("19.90")); original.setPaymentStatus(1);
        IRegistrationOrderService replay = mock(IRegistrationOrderService.class);
        when(replay.createOrReplay(MEMBER, request)).thenReturn(new RegistrationOrderResult(List.of(original), true));
        ReflectionTestUtils.setField(checkout, "registrationOrderService", replay);
        when(participants.selectOwnedAddressList(MEMBER)).thenReturn(List.of());
        assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verifyNoInteractions(payment);
    }

    @Test
    void retryRejectsMissingPhoneBeforeZeroCompletionOrNewOrReusedProviderAttemptOnBothChannels() {
        member.setPhone("");
        for (int state : new int[]{0, 1}) {
            original.setPaymentStatus(state);
            for (boolean app : new boolean[]{false, true}) {
                assertThrows(RegistrationCheckoutException.class, () -> checkout.retryPayment(MEMBER, REG, app));
            }
        }
        verify(registrations, never()).completeZeroAmountPaymentForRetry(anyLong(), anyLong());
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verifyNoInteractions(payment);
    }

    @Test
    void retryRechecksSavedNameInsteadOfTrustingTheOldOrderSnapshot() {
        when(participants.selectOwnedAddressList(MEMBER)).thenReturn(List.of());
        assertThrows(RegistrationCheckoutException.class, () -> checkout.retryPayment(MEMBER, REG));
        verify(registrations, never()).completeZeroAmountPaymentForRetry(anyLong(), anyLong());
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verifyNoInteractions(payment);
    }

    @Test
    void retryKeepsOriginalOwnershipCheckAheadOfProfileAndPayment() {
        original.setMemberId(8L);
        RegistrationCheckoutException rejected = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.retryPayment(MEMBER, REG));
        assertEquals(403, rejected.getCode());
        verifyNoInteractions(members, participants, payment);
        verify(registrations, never()).completeZeroAmountPaymentForRetry(anyLong(), anyLong());
    }

    @Test
    void eligibleRetryReturnsOriginalAttemptOnBothChannelsWithoutChangingMoney() {
        original.setPayableAmount(new BigDecimal("19.90")); original.setPaymentStatus(1);
        assertEquals(Map.of("channel", "JSAPI"), checkout.retryPayment(MEMBER, REG).getData().get("payParams"));
        assertEquals(Map.of("channel", "APP"), checkout.retryPayment(MEMBER, REG, true).getData().get("payParams"));
        verify(payment).createOrder(original); verify(payment).createAppOrder(original);
        assertEquals(new BigDecimal("19.90"), original.getPayableAmount());
        assertEquals("R29-F01-77", original.getRegistrationNo());
    }

    @Test
    void eligiblePendingFreeRetryCompletesWithoutConsultingPaymentProvider() {
        CmsRegistration completed = new CmsRegistration();
        completed.setId(REG); completed.setRegistrationNo("R29-F01-77"); completed.setPaymentStatus(2);
        completed.setRegistrationStatus(2);
        when(registrations.completeZeroAmountPaymentForRetry(REG, MEMBER)).thenReturn(List.of(completed));
        assertEquals(2, checkout.retryPayment(MEMBER, REG).getData().get("paymentStatus"));
        verifyNoInteractions(payment);
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
    }

    @Test
    void completedReplayRemainsReadableWithoutCurrentParticipantOrPhone() {
        original.setPaymentStatus(2);
        IRegistrationOrderService replay = mock(IRegistrationOrderService.class);
        when(replay.createOrReplay(MEMBER, request)).thenReturn(new RegistrationOrderResult(List.of(original), true));
        ReflectionTestUtils.setField(checkout, "registrationOrderService", replay);
        member.setPhone("");
        assertEquals(REG, checkout.createOrReplay(MEMBER, request).getData().get("registrationId"));
        when(registrations.completeZeroAmountPaymentForRetry(REG, MEMBER)).thenReturn(List.of(original));
        assertEquals(REG, checkout.retryPayment(MEMBER, REG, true).getData().get("registrationId"));
        verifyNoInteractions(members, participants, payment);
    }

    @Test
    void quotingDoesNotRequireCompletedPurchaseProfile() {
        member.setPhone("");
        checkout.quote(MEMBER, request);
        verifyNoInteractions(members, participants, payment, creation);
    }

    private static UmsMemberAddress participant(long owner, String name, int deleted) {
        UmsMemberAddress participant = new UmsMemberAddress();
        participant.setMemberId(owner); participant.setFullName(name); participant.setDelFlag(deleted);
        return participant;
    }
}
