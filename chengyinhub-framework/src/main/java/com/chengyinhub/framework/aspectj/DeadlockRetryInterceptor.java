package com.chengyinhub.framework.aspectj;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.common.annotation.DeadlockRetry;

/**
 * {@link DeadlockRetry} 的实现：必须排在事务拦截器外层（见 DeadlockRetryConfig 的 order），
 * 这样每次调用都是一笔新事务，失败那次已由事务拦截器整笔回滚。
 *
 * <p>只认 DeadlockLoserDataAccessException（MySQL 1213）与 CannotAcquireLockException（1205）；
 * 最多调用 2 次；进入时已有活动事务（本方法只是内层参与者）则不重试，直接透传给外层边界。
 */
public class DeadlockRetryInterceptor implements MethodInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(DeadlockRetryInterceptor.class);

    static final int MAX_ATTEMPTS = 2;
    private static final int MIN_BACKOFF_MS = 10;
    private static final int MAX_BACKOFF_MS = 60;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable
    {
        if (!isAnnotated(invocation) || TransactionSynchronizationManager.isActualTransactionActive())
        {
            return invocation.proceed();
        }
        for (int attempt = 1; ; attempt++)
        {
            // 每次都从拦截链的副本重新进入，下一跳就是事务拦截器 → 新事务。
            MethodInvocation call = invocation instanceof ProxyMethodInvocation
                    ? ((ProxyMethodInvocation) invocation).invocableClone() : invocation;
            try
            {
                return call.proceed();
            }
            catch (DeadlockLoserDataAccessException | CannotAcquireLockException lockFailure)
            {
                if (attempt >= MAX_ATTEMPTS)
                {
                    throw lockFailure;
                }
                log.warn("事务因锁冲突回滚，整笔重试一次 method={} attempt={} cause={}",
                        invocation.getMethod().getName(), attempt, lockFailure.getClass().getSimpleName());
                backoff(lockFailure);
            }
        }
    }

    private static boolean isAnnotated(MethodInvocation invocation)
    {
        Method method = invocation.getMethod();
        if (AnnotatedElementUtils.hasAnnotation(method, DeadlockRetry.class))
        {
            return true;
        }
        Object target = invocation.getThis();
        if (target == null)
        {
            return false;
        }
        Method specific = AopUtils.getMostSpecificMethod(method, AopUtils.getTargetClass(target));
        return AnnotatedElementUtils.hasAnnotation(specific, DeadlockRetry.class);
    }

    private static void backoff(RuntimeException lockFailure)
    {
        try
        {
            Thread.sleep(ThreadLocalRandom.current().nextInt(MIN_BACKOFF_MS, MAX_BACKOFF_MS + 1));
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw lockFailure;
        }
    }
}
