package com.chengyinhub.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 最外层事务边界上的死锁重试：InnoDB 选为死锁牺牲者（或锁等待超时）时，整笔事务已回滚，
 * 在事务拦截器外层再完整调用一次（共 2 次，间隔短随机抖动）。
 *
 * <p>只在进入时没有活动事务才生效；作为内层参与者被调用时原样抛出，交给真正的外层边界处理。
 * <p>⚠️ 只能标在**整段只写数据库**的方法上：被重试的范围内若有 Redis 写、外部 HTTP、消息、文件等
 * 不随事务回滚的副作用，重试会重复它们（事务同步 afterCompletion 回收的除外）。
 * 也不要与 @Log / @RateLimiter 等同为最低优先级的切面叠在同一方法上，它们会落在重试外层或内层不定。
 * <p>实现见 chengyinhub-framework 的 DeadlockRetryInterceptor / DeadlockRetryConfig。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DeadlockRetry
{
}
