package com.chengyinhub.quartz.util;

import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.spring.SpringUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.aop.support.AopUtils;

/** The identity of the actual invocation, independent of job row/group or bean alias. */
public final class CanonicalJobTarget {
    private CanonicalJobTarget() { }

    public static String canonical(String invocation) {
        if (invocation == null || invocation.trim().isEmpty()) throw new ServiceException("任务调用目标不能为空");
        String target = invocation.trim();
        try {
            String bean = JobInvokeUtil.getBeanName(target);
            String method = JobInvokeUtil.getMethodName(target);
            Class<?> type = JobInvokeUtil.isValidClassName(bean) ? Class.forName(bean)
                    : AopUtils.getTargetClass(SpringUtils.getBean(bean));
            List<Object[]> args = JobInvokeUtil.getMethodParams(target);
            Class<?>[] types = args == null ? new Class<?>[0] : JobInvokeUtil.getMethodParamsType(args);
            type.getMethod(method, types);
            StringBuilder key = new StringBuilder(type.getName()).append('#').append(method).append('(');
            if (args != null) for (Object[] arg : args) {
                key.append(((Class<?>) arg[1]).getName()).append(':').append(Base64.getEncoder()
                        .encodeToString(String.valueOf(arg[0]).getBytes(StandardCharsets.UTF_8))).append(';');
            }
            return key.append(')').toString();
        } catch (Exception invalid) {
            throw new ServiceException("任务调用目标或参数无效");
        }
    }
}
