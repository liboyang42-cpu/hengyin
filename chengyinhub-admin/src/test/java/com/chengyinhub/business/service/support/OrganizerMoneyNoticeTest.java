package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MC-034:主办方钱相关站内信 —— 提交后投递、走系统会话卡片、任何失败都不外抛。 */
class OrganizerMoneyNoticeTest {

    private final IImService im = mock(IImService.class);
    private final CommittedBusinessNotice committed = mock(CommittedBusinessNotice.class);

    @SuppressWarnings("unchecked")
    private OrganizerMoneyNotice notice() {
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(im);
        // 让「独立事务投递」直接执行 delivery,便于断言实际发出的卡片
        doAnswer(inv -> ((Supplier<Boolean>) inv.getArgument(2)).get())
                .when(committed).dispatchInNewTransaction(anyString(), any(), any());
        return new OrganizerMoneyNotice(provider, committed);
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void settledNoticeIsASystemCardWithFormattedAmount() {
        when(im.sendSystem(eq(9L), eq(ImMessage.MSG_CARD), anyString(), anyString())).thenReturn(1L);
        notice().activitySettled(9L, 5L, "周末夜跑", new BigDecimal("12.5"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(im).sendSystem(eq(9L), eq(ImMessage.MSG_CARD), eq("活动分润已到账"), json.capture());
        assertTrue(json.getValue().contains("活动「周末夜跑」已结算，分润 ¥12.50 已到账"), json.getValue());
    }

    @Test
    void zeroAmountOrMissingRecipientSendsNothing() {
        OrganizerMoneyNotice n = notice();
        n.activitySettled(9L, 5L, "x", BigDecimal.ZERO);
        n.priceLocked(null, 5L, "x");
        verify(committed, never()).dispatchInNewTransaction(anyString(), any(), any());
    }

    @Test
    void insideTransactionItWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        notice().priceLocked(9L, 5L, "城市定向");
        verify(committed, never()).dispatchInNewTransaction(anyString(), any(), any());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCommit();
        verify(im).sendSystem(eq(9L), eq(ImMessage.MSG_CARD), eq("终价已锁定"), anyString());
    }

    @Test
    void deliveryFailureNeverEscapes() {
        doThrow(new IllegalStateException("restore mode"))
                .when(committed).dispatchInNewTransaction(anyString(), any(), any());
        @SuppressWarnings("unchecked")
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        OrganizerMoneyNotice n = new OrganizerMoneyNotice(provider, committed);
        assertDoesNotThrow(() -> n.ticketPurchased(9L, "ACTIVITY", 5L, 77L, "周末夜跑"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        n.ticketPurchased(9L, "ACTIVITY", 5L, 77L, "周末夜跑");
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            assertDoesNotThrow(s::afterCommit);
        }
        assertEquals("¥0.10", OrganizerMoneyNotice.moneyText(new BigDecimal("0.1")));
        verify(im, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }
}
