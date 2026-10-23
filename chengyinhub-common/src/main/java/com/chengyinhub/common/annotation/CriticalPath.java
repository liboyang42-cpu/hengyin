package com.chengyinhub.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.chengyinhub.common.observability.CriticalPathCode;

/**
 * O-11 关键路径失败事件：方法抛出时发一条固定码事件（见 {@link com.chengyinhub.common.observability.CriticalPathEvents}），
 * 原异常原样抛出，不改业务结果。
 *
 * <p>切面排在 @DeadlockRetry 与事务拦截器外层：重试后成功不发事件，提交失败也能看到。
 * 同一个异常实例沿嵌套的关键路径方法往外抛时只发一次（最内层的码）。
 * <p>实现见 chengyinhub-framework 的 CriticalPathInterceptor / CriticalPathConfig。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CriticalPath
{
    /** 稳定事件码 */
    CriticalPathCode value();

    /** 子操作标记，固定字面量，匹配 [a-z][a-z0-9_]{0,31}；不得放业务数据 */
    String op();
}
