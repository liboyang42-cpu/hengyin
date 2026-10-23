package com.chengyinhub.framework.config;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.framework.aspectj.CriticalPathInterceptor;

/**
 * O-11 关键路径失败事件切面装配。
 *
 * <p>排在 RateLimiterAspect（DeadlockRetryConfig.ORDER - 1）、死锁重试与事务通知器的外层：
 * 死锁重试后成功的调用不发事件；事务提交阶段的失败也能被看到。
 */
@Configuration
public class CriticalPathConfig
{
    public static final int ORDER = DeadlockRetryConfig.ORDER - 2;

    @Bean
    public static Advisor criticalPathAdvisor()
    {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(CriticalPath.class), new CriticalPathInterceptor());
        advisor.setOrder(ORDER);
        return advisor;
    }
}
