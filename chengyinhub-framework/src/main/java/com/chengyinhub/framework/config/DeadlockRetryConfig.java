package com.chengyinhub.framework.config;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.framework.aspectj.DeadlockRetryInterceptor;

/**
 * 死锁重试切面装配。
 *
 * <p>事务通知器沿用 Boot 自动配置的默认顺序 Ordered.LOWEST_PRECEDENCE（本仓库未声明
 * EnableTransactionManagement），这里取比它高一级的优先级，保证重试包在事务外层。
 */
@Configuration
public class DeadlockRetryConfig
{
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Bean
    public static Advisor deadlockRetryAdvisor()
    {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(DeadlockRetry.class), new DeadlockRetryInterceptor());
        advisor.setOrder(ORDER);
        return advisor;
    }
}
