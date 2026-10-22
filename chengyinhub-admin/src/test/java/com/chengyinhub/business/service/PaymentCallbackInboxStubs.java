package com.chengyinhub.business.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;

/**
 * 支付回调 inbox 的直通桩:register 记下业务身份,process 每次都把 mutation 跑一遍
 * (不记 DONE,让业务自身的 CAS 幂等仍被重复调用覆盖)。inbox 自身的去重/租约/身份冲突语义
 * 由 WxPaymentCallbackInboxServiceImplTest 与 WxPaymentCallbackFencingMySqlIT 覆盖。
 *
 * <p>与真实 {@code WxPaymentCallbackMutationService} 一致:mutation 返回 false 或抛异常时整笔回滚、
 * process 返回 false。传入事务管理器时 mutation 在独立事务里执行。</p>
 */
public final class PaymentCallbackInboxStubs
{
    private PaymentCallbackInboxStubs()
    {
    }

    public static IWxPaymentCallbackInboxService passThrough()
    {
        return passThrough(null);
    }

    public static IWxPaymentCallbackInboxService passThrough(PlatformTransactionManager transactionManager)
    {
        IWxPaymentCallbackInboxService inbox = mock(IWxPaymentCallbackInboxService.class);
        Map<String, WxPaymentCallbackInbox> rows = new ConcurrentHashMap<>();
        when(inbox.register(anyString(), anyInt(), anyLong(), any())).thenAnswer(call -> {
            WxPaymentCallbackInbox row = new WxPaymentCallbackInbox();
            row.setBizType(call.getArgument(1));
            row.setBizId(call.getArgument(2));
            row.setEventType(call.getArgument(3));
            return rows.putIfAbsent(call.getArgument(0), row) == null;
        });
        when(inbox.process(anyString(), any())).thenAnswer(call -> {
            WxPaymentCallbackInbox row = rows.get(call.getArgument(0, String.class));
            IWxPaymentCallbackInboxService.Mutation mutation = call.getArgument(1);
            if (row == null)
            {
                return false;
            }
            try
            {
                if (transactionManager == null)
                {
                    return !mutation.isCurrent(row) || mutation.apply(row);
                }
                return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
                    boolean done = !mutation.isCurrent(row) || mutation.apply(row);
                    if (!done)
                    {
                        status.setRollbackOnly();
                    }
                    return done;
                }));
            }
            catch (RuntimeException failed)
            {
                return false;
            }
        });
        return inbox;
    }
}
