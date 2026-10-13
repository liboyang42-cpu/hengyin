package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * d7593ff37 起结算不再同步调渠道退款,原 CoopSettlementTest「退款失败保留冻结并告警」的职责
 * 迁到 CoopDepositRefundService。CoopDepositRefundMybatisIntegrationTest 需 CHENGYIN_FUNDS_MYSQL_IT=1,
 * CI 不跑,所以这里用纯 mock 在 Backend tests 里钉住:渠道结果未到手时不得记已退,且必须告警。
 */
class CoopDepositRefundAlarmTest {

    private CoopInviteMapper mapper;
    private IWechatPayService wechat;
    private AlarmService alarms;
    private CoopDepositRefundService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CoopInviteMapper.class);
        wechat = mock(IWechatPayService.class);
        alarms = mock(AlarmService.class);
        service = new CoopDepositRefundService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "wechatPayService", wechat);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "alarmService", alarms);

        AtomicReference<String> lease = new AtomicReference<>();
        when(mapper.claimDepositRefundDispatch(eq(90L), anyString(), anyBoolean())).thenAnswer(call -> {
            lease.set(call.getArgument(1));
            return 1;
        });
        when(mapper.selectDepositFactByIdForUpdate(90L)).thenAnswer(call -> {
            CoopInvite row = new CoopInvite();
            row.setId(90L); row.setDelFlag(0); row.setDepositStatus(2);
            row.setDepositOutTradeNo("DEP90T100"); row.setDepositRefundNo("RDEP90T100");
            row.setDepositAmount(new BigDecimal("50.00")); row.setDepositRefundAmount(new BigDecimal("50.00"));
            row.setDepositRefundState("PENDING"); row.setDepositRefundLeaseToken(lease.get());
            return row;
        });
        when(wechat.queryRefundFacts("RDEP90T100")).thenReturn(Collections.singletonMap("status", "NOT_FOUND"));
        when(mapper.renewDepositRefundDispatch(eq(90L), anyString())).thenReturn(1);
        when(mapper.finishDepositRefundDispatch(eq(90L), anyString(), anyString())).thenReturn(1);
    }

    @Test
    void unknownChannelResultKeepsDepositHeldAndRaisesActionableAlarm() {
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), anyString())).thenReturn(RefundRequestResult.UNKNOWN);

        service.refresh(90L, true);

        verify(mapper).finishDepositRefundDispatch(eq(90L), anyString(), eq("UNKNOWN"));
        verify(mapper, never()).confirmDepositRefundResult(any(), any(), any(), any(), any());
        verify(alarms).alarm(eq("合作保证金退款待处理"), contains("inviteId=90"));
    }

    @Test
    void definitiveChannelFailureGoesManualAndRaisesActionableAlarm() {
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), anyString())).thenReturn(RefundRequestResult.DEFINITIVE_FAILED);

        service.refresh(90L, true);

        verify(mapper).finishDepositRefundDispatch(eq(90L), anyString(), eq("MANUAL"));
        verify(mapper, never()).confirmDepositRefundResult(any(), any(), any(), any(), any());
        verify(alarms).alarm(eq("合作保证金退款待处理"), contains("inviteId=90"));
    }

    /** 负控:渠道已受理只记处理中,不得误告警(否则告警会被当噪音屏蔽)。 */
    @Test
    void acceptedChannelResultIsProcessingWithoutAlarm() {
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), anyString())).thenReturn(RefundRequestResult.ACCEPTED);

        service.refresh(90L, true);

        verify(mapper).finishDepositRefundDispatch(eq(90L), anyString(), eq("PROCESSING"));
        verify(alarms, never()).alarm(anyString(), anyString());
    }
}
