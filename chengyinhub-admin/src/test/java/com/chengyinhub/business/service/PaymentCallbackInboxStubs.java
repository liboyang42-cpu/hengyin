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
        return passThrough(transactionManager, null);
    }

    /** Focused business-CAS fixture only. Evidence reader may be real SQL; inbox here remains a stub, never DONE proof. */
    public static IWxPaymentCallbackInboxService passThrough(PlatformTransactionManager transactionManager,
            com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService verifiedFacts)
    {
        return passThrough(transactionManager, verifiedFacts, null);
    }

    /** Optional test-only failure observation; mutation, rollback and false-result semantics remain unchanged. */
    public static IWxPaymentCallbackInboxService passThrough(PlatformTransactionManager transactionManager,
            com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService verifiedFacts,
            java.util.function.Consumer<RuntimeException> failureObserver)
    {
        IWxPaymentCallbackInboxService inbox = mock(IWxPaymentCallbackInboxService.class);
        Map<String, WxPaymentCallbackInbox> rows = new ConcurrentHashMap<>();
        Map<String, com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference> references = new ConcurrentHashMap<>();
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
                if (failureObserver != null) failureObserver.accept(failed);
                return false;
            }
        });
        when(inbox.registerVerified(anyString(), anyInt(), anyLong(), any(), any())).thenAnswer(call -> {
            if (verifiedFacts == null) throw new IllegalStateException("Verified fixture needs an explicit fact reader");
            String key = call.getArgument(0);
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference = call.getArgument(4);
            var stored = verifiedFacts.requireVerified(reference);
            int expected = stored.facts().kind() == com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT ? 1 : 2;
            if (call.getArgument(1, Integer.class) != expected || !"SUCCESS".equals(call.getArgument(3)))
                throw new IWxPaymentCallbackInboxService.IdentityConflictException("Fixture fact kind mismatch");
            synchronized (rows) {
                WxPaymentCallbackInbox existing = rows.get(key);
                if (existing != null && (!references.containsKey(key)
                        || !java.util.Objects.equals(existing.getBizType(), expected)
                        || !java.util.Objects.equals(existing.getBizId(), call.getArgument(2))
                        || !"SUCCESS".equals(existing.getEventType())))
                    throw new IWxPaymentCallbackInboxService.IdentityConflictException("Fixture original inbox mismatch");
                var previous = references.putIfAbsent(key, reference);
                if (previous != null && !previous.equals(reference))
                    throw new IWxPaymentCallbackInboxService.IdentityConflictException("Fixture original reference mismatch");
                return inbox.register(key, expected, call.getArgument(2), "SUCCESS");
            }
        });
        when(inbox.processVerified(anyString(), any(), any())).thenAnswer(call -> {
            String key = call.getArgument(0);
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference = call.getArgument(1);
            if (verifiedFacts == null || reference == null || !reference.equals(references.get(key))) return false;
            verifiedFacts.requireVerified(reference);
            return inbox.process(key, call.getArgument(2));
        });
        return inbox;
    }
}
