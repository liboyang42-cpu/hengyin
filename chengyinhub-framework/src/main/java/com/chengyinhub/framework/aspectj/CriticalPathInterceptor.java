package com.chengyinhub.framework.aspectj;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathEvents;

/**
 * {@link CriticalPath} 的实现：方法抛出时发一条关键路径失败事件，再把原异常原样抛出。
 * 不吞异常、不改返回值、不重试；排序见 CriticalPathConfig。
 */
public class CriticalPathInterceptor implements MethodInterceptor
{
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable
    {
        try
        {
            return invocation.proceed();
        }
        catch (Throwable failure)
        {
            CriticalPath annotation = annotation(invocation);
            if (annotation != null)
            {
                CriticalPathEvents.failure(annotation.value(), annotation.op(), failure);
            }
            throw failure;
        }
    }

    private static CriticalPath annotation(MethodInvocation invocation)
    {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        if (target != null)
        {
            method = AopUtils.getMostSpecificMethod(method, target.getClass());
        }
        return AnnotatedElementUtils.findMergedAnnotation(method, CriticalPath.class);
    }
}
