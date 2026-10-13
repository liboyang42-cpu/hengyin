package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.FundActionPreflight;
import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.vo.FundActionPreflightVO;
import com.chengyinhub.business.mapper.FundActionPreflightMapper;
import com.chengyinhub.business.exception.FundActionPreflightException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundActionPreflightServiceImplTest {
    private FundActionPreflightMapper mapper;
    private FundActionPreflightServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(FundActionPreflightMapper.class);
        service = new FundActionPreflightServiceImpl(mapper, new FundChallengeTokenCodec("test-secret"),
                120L, new BigDecimal("500.00"));
    }

    @Test
    void confirmedIntentReplayDoesNotReissueUsedChallengeTokenOrReplaceOriginalContext() {
        BankWithdrawalPreflightRequest request = request("intent-confirmed");
        Date initiatedAt = new Date(System.currentTimeMillis() - 30_000L);
        FundActionPreflight confirmed = row(request, FundActionPreflight.STATE_CONFIRMED, initiatedAt,
                "可信服务端位置", "原始设备");
        when(mapper.selectByIntent(9201L, FundActionPreflightServiceImpl.BANK_WITHDRAWAL,
                request.getRequestId())).thenReturn(confirmed);

        FundActionPreflightVO replay = service.prepareBankWithdrawal(9201L, request, "重放位置", "重放设备");

        assertNull(replay.getChallengeToken(), "已使用的 challenge token 不得再次下发");
        assertEquals(initiatedAt, replay.getContextWhen());
        assertEquals("可信服务端位置", replay.getContextWhere());
        assertEquals("AVAILABLE", replay.getContextWhereStatus());
        assertEquals("原始设备", replay.getContextDevice());
    }

    @Test
    void newChallengePersistsTheInitiatingContextInsteadOfRecomputingItOnResponse() {
        BankWithdrawalPreflightRequest request = request("intent-new");
        when(mapper.selectByIntent(9201L, FundActionPreflightServiceImpl.BANK_WITHDRAWAL,
                request.getRequestId())).thenReturn(null);
        when(mapper.countConsumedByCounterparty(eq(9201L),
                eq(FundActionPreflightServiceImpl.BANK_WITHDRAWAL), any(String.class))).thenReturn(0);
        when(mapper.insert(any(FundActionPreflight.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FundActionPreflight.class).setId(88L);
            return 1;
        });

        FundActionPreflightVO created = service.prepareBankWithdrawal(
                9201L, request, "可信服务端位置", "微信小程序");

        ArgumentCaptor<FundActionPreflight> saved = ArgumentCaptor.forClass(FundActionPreflight.class);
        verify(mapper).insert(saved.capture());
        assertNotNull(created.getChallengeToken());
        assertEquals(saved.getValue().getContextInitiatedAt(), created.getContextWhen());
        assertEquals("可信服务端位置", saved.getValue().getContextWhere());
        assertEquals("微信小程序", saved.getValue().getContextDevice());
        assertEquals("AVAILABLE", created.getContextWhereStatus());
    }

    @Test
    void moneyWriterConsumesConfirmedIntentByStableBindingWithoutReusingChallengeToken() {
        BankWithdrawalPreflightRequest request = request("intent-consume");
        when(mapper.consumeIfConfirmed(eq(55L), eq(9201L),
                eq(FundActionPreflightServiceImpl.BANK_WITHDRAWAL), eq(request.getRequestId()),
                any(String.class), any(Date.class))).thenReturn(1);

        service.consumeBankWithdrawal(9201L, 55L, request);

        verify(mapper).consumeIfConfirmed(eq(55L), eq(9201L),
                eq(FundActionPreflightServiceImpl.BANK_WITHDRAWAL), eq(request.getRequestId()),
                any(String.class), any(Date.class));
    }

    @Test
    void exactConsumedIntentCanReplayButChangedPayloadCannotReuseIt() {
        BankWithdrawalPreflightRequest request = request("intent-consumed");
        FundActionPreflight consumed = row(request, FundActionPreflight.STATE_CONSUMED, new Date(),
                null, "微信小程序");
        when(mapper.selectById(77L)).thenReturn(consumed);
        when(mapper.consumeIfConfirmed(eq(77L), eq(9201L),
                eq(FundActionPreflightServiceImpl.BANK_WITHDRAWAL), eq(request.getRequestId()),
                any(String.class), any(Date.class))).thenReturn(0);

        service.consumeBankWithdrawal(9201L, 77L, request);

        BankWithdrawalPreflightRequest changed = request("different-intent");
        FundActionPreflightException error = org.junit.jupiter.api.Assertions.assertThrows(
                FundActionPreflightException.class,
                () -> service.consumeBankWithdrawal(9201L, 77L, changed));
        assertEquals("PREFLIGHT_BINDING_MISMATCH", error.getBusinessCode());
    }

    @Test
    void missingTrustedWhereSourceIsExplicitInsteadOfAUsableLookingEmptyValue() {
        BankWithdrawalPreflightRequest request = request("intent-no-where");
        FundActionPreflight confirmed = row(request, FundActionPreflight.STATE_CONFIRMED, new Date(),
                null, "微信小程序");
        when(mapper.selectByIntent(9201L, FundActionPreflightServiceImpl.BANK_WITHDRAWAL,
                request.getRequestId())).thenReturn(confirmed);

        FundActionPreflightVO replay = service.prepareBankWithdrawal(9201L, request, null, "微信小程序");

        assertNull(replay.getContextWhere());
        assertEquals("UNAVAILABLE", replay.getContextWhereStatus());
    }

    private BankWithdrawalPreflightRequest request(String requestId) {
        BankWithdrawalPreflightRequest request = new BankWithdrawalPreflightRequest();
        request.setRequestId(requestId);
        request.setAmount(new BigDecimal("25.00"));
        request.setRealname("张三");
        request.setBankName("城瘾银行");
        request.setBankAccount("6222020000001234");
        request.setMobilephone("18800000001");
        return request;
    }

    private FundActionPreflight row(BankWithdrawalPreflightRequest request, int state, Date initiatedAt,
                                    String contextWhere, String contextDevice) {
        FundActionPreflight row = new FundActionPreflight();
        row.setId(77L);
        row.setMemberId(9201L);
        row.setActionType(FundActionPreflightServiceImpl.BANK_WITHDRAWAL);
        row.setRequestId(request.getRequestId());
        row.setRequestFingerprint(FundActionPreflightPolicy.bankWithdrawalFingerprint(9201L, request.getAmount(),
                request.getRealname(), request.getBankName(), request.getBankAccount(), request.getMobilephone()));
        row.setChallengeNonce("stored-nonce");
        row.setState(state);
        row.setCurrency("CNY");
        row.setDisplayName("城瘾银行");
        row.setDisplayAccountMask("****1234");
        row.setDisplayPhoneMask("188****0001");
        row.setRiskLevel("STATIC");
        row.setContextInitiatedAt(initiatedAt);
        row.setContextWhere(contextWhere);
        row.setContextDevice(contextDevice);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        return row;
    }
}
