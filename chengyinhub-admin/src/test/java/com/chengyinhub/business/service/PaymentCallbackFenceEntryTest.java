package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentCallbackFenceEntryTest {
    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrations;
    private IWxPaymentCallbackInboxService inbox;
    private Map<String,String> payload;
    @BeforeEach void setup() {
        service = new CmsRegistrationServiceImpl(); registrations = mock(CmsRegistrationMapper.class);
        inbox = mock(IWxPaymentCallbackInboxService.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "wxPaymentCallbackInboxService", inbox);
        CmsRegistration r = new CmsRegistration(); r.setId(11L); r.setRegistrationNo("A-ORIGINAL");
        when(registrations.selectByRegistrationNo("A-ORIGINAL")).thenReturn(r);
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(r);
        payload = new HashMap<>(); payload.put("out_trade_no", "A-ORIGINAL");
        payload.put("trade_state", "USERPAYING"); payload.put("transaction_id", "TX-ORIGINAL");
    }
    @Test void actualEntryAcquiresFenceBeforeReadingAndApplyingOriginalOrder() {
        when(inbox.process(anyString(), any())).thenAnswer(call -> {
            IWxPaymentCallbackInboxService.Mutation mutation = call.getArgument(1);
            WxPaymentCallbackInbox row = new WxPaymentCallbackInbox(); row.setBizId(11L);
            row.setBizType(WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT); row.setEventType("USERPAYING");
            assertTrue(mutation.isCurrent(row)); return mutation.apply(row);
        });
        assertEquals("SUCCESS", service.handleWechatPayCallback(payload));
        org.mockito.InOrder order = inOrder(inbox, registrations);
        order.verify(inbox).register("PAY:TX-ORIGINAL:USERPAYING", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 11L, "USERPAYING");
        order.verify(inbox).process(eq("PAY:TX-ORIGINAL:USERPAYING"), any());
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(11L);
    }
    @Test void busyCallbackKeepsOrderUnchangedAndRequestsRetry() {
        when(inbox.process(anyString(), any())).thenReturn(false);
        assertEquals("FAIL", service.handleWechatPayCallback(payload));
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(anyLong());
        verify(registrations, never()).claimPaymentSuccess(anyLong(), any(), any());
    }
    @Test void unknownChannelStatusDoesNotClaimOrMutate() {
        payload.put("trade_state", "UNCONFIRMED");
        assertEquals("FAIL", service.handleWechatPayCallback(payload)); verifyNoInteractions(inbox);
    }
    @Test void conflictingOriginalInboxIdentityFailsClosed() {
        doThrow(new IllegalStateException("original identity conflict")).when(inbox).register(anyString(), anyInt(), anyLong(), anyString());
        assertEquals("FAIL", service.handleWechatPayCallback(payload)); verify(inbox, never()).process(anyString(), any());
    }
}
