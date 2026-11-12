package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.domain.dto.ChannelGrantTopic;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.FulfillmentCommand;
import com.chengyinhub.business.service.FulfillmentOutcome;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.GrantLine;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import com.chengyinhub.business.service.PaymentFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 渠道 grant 适配器：真正调 M2-F，typed CHANNEL + 分摊/有效期映射，不写第二套订单。 */
class ChannelRegistrationGrantAdapterTest {

    private IMarketingProductFulfillmentService fulfillment;
    private CmsRegistrationMapper registrationMapper;
    private ChannelRegistrationGrantAdapter adapter;

    @BeforeEach
    void setUp() {
        fulfillment = mock(IMarketingProductFulfillmentService.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        adapter = new ChannelRegistrationGrantAdapter(fulfillment, registrationMapper);
        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = "ORDER-1";
        outcome.registrationIds = Collections.singletonList(800L);
        outcome.pendingActivation = false;
        when(fulfillment.grant(any())).thenReturn(outcome);
        CmsRegistration primary = new CmsRegistration();
        primary.setId(800L);
        primary.setRegistrationNo("T202611080001");
        when(registrationMapper.selectCmsRegistrationById(800L)).thenReturn(primary);
    }

    @Test
    void mapsTypedChannelFactAndAllocationIntoM2Command() {
        ChannelGrantResult result = adapter.grant(command());

        ArgumentCaptor<FulfillmentCommand> captor = ArgumentCaptor.forClass(FulfillmentCommand.class);
        verify(fulfillment).grant(captor.capture());
        FulfillmentCommand command = captor.getValue();
        assertEquals("req-1", command.requestId);
        assertEquals("ORDER-1", command.parentOrderNo);
        assertEquals(PaymentFacts.MODE_CHANNEL, command.payment.mode);
        assertEquals(2, command.quantity);
        assertEquals(4, command.seatCount);
        assertEquals(11L, command.payment.externalFactId);
        assertEquals("raw-hash", command.payment.externalFactHash);
        assertEquals("acme", command.payment.sourceProviderKey);
        assertEquals(new BigDecimal("20.00"), command.payment.verifiedAmount);
        assertEquals(1, command.lines.size());
        assertEquals(new BigDecimal("20.00"), command.lines.get(0).payableAmount);
        assertEquals(new BigDecimal("20.00"), command.lines.get(0).listAmount);
        assertEquals(GrantLine.VALIDITY_RELATIVE_DAYS, command.lines.get(0).validityKind);
        assertEquals(Long.valueOf(800L), result.getPrimaryRegistrationId());
        assertEquals("T202611080001", result.getPrimaryRegistrationNo());
    }

    @Test
    void pendingActivationIsNotReportedAsCompleted() {
        FulfillmentOutcome outcome = new FulfillmentOutcome();
        outcome.parentOrderNo = "ORDER-1";
        outcome.pendingActivation = true;
        when(fulfillment.grant(any())).thenReturn(outcome);

        ChannelVoucherException rejection = assertThrows(ChannelVoucherException.class,
                () -> adapter.grant(command()));
        assertEquals("GRANT_INCOMPLETE", rejection.getReasonCode());
    }

    @Test
    void revokeMapsRefundRequiredWithoutClaimingAccessWasRevoked() {
        ChannelRevocationOutcome outcome = new ChannelRevocationOutcome();
        outcome.parentOrderNo = "ORDER-1";
        outcome.registrationIds = Collections.singletonList(800L);
        outcome.revocable = true;
        outcome.reasonCode = "REFUND_REQUIRED";
        when(fulfillment.revokeChannel(eq("ORDER-1"), eq("渠道取消"), any())).thenReturn(outcome);

        ChannelGrantResult result = adapter.revoke(revocation());

        assertEquals(Boolean.TRUE, result.getRevocable());
        assertEquals("REFUND_REQUIRED", result.getReasonCode());
        assertEquals(Long.valueOf(800L), result.getPrimaryRegistrationId());
    }

    @Test
    void usedRegistrationIsNotReportedAsRevocable() {
        ChannelRevocationOutcome outcome = new ChannelRevocationOutcome();
        outcome.revocable = false;
        outcome.reasonCode = "ALREADY_VERIFIED";
        when(fulfillment.revokeChannel(eq("ORDER-1"), eq("渠道取消"), any())).thenReturn(outcome);

        ChannelGrantResult result = adapter.revoke(revocation());

        assertEquals(Boolean.FALSE, result.getRevocable());
        assertEquals("ALREADY_VERIFIED", result.getReasonCode());
    }

    private ChannelGrantRevocation revocation() {
        ChannelGrantRevocation revocation = new ChannelGrantRevocation();
        revocation.setChannelOrderNo("ORDER-1");
        revocation.setReason("渠道取消");
        revocation.setRequestId("rev-1");
        return revocation;
    }

    private ChannelGrantCommand command() {
        ChannelGrantCommand command = new ChannelGrantCommand();
        command.setSourceType(ChannelGrantCommand.SOURCE_CHANNEL);
        command.setExternalFactId(11L);
        command.setExternalFactHash("raw-hash");
        command.setProviderKey("acme");
        command.setChannelOrderNo("ORDER-1");
        command.setExternalAmount(new BigDecimal("20.00"));
        command.setExternalCurrency("CNY");
        command.setMemberId(7L);
        command.setProductId(42L);
        command.setProductRevision(3L);
        command.setQuantity(2);
        command.setSeatCount(4);
        command.setValidityKind(ChannelGrantCommand.VALIDITY_RELATIVE_DAYS);
        command.setValidDays(30);
        command.setGrantedAt(new Date());
        command.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        command.setRequestId("req-1");
        command.setRequestFingerprint("fp-1");
        ChannelGrantTopic topic = new ChannelGrantTopic();
        topic.setTopicId(7L);
        topic.setListAmount(new BigDecimal("20.00"));
        topic.setAllocatedAmount(new BigDecimal("20.00"));
        command.setTopics(Collections.singletonList(topic));
        return command;
    }
}
