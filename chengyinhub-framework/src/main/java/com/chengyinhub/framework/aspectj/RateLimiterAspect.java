package com.chengyinhub.framework.aspectj;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import com.chengyinhub.framework.config.DeadlockRetryConfig;

/**
 * 限流处理
 *
 * <p>排在 @DeadlockRetry 外层：死锁重试整笔重来时不再计一次限流（Redis 计数不随事务回滚）。
 *
 * @author ruoyi
 */
@Aspect
@Component
@Order(DeadlockRetryConfig.ORDER - 1)
public class RateLimiterAspect
{
    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private RedisTemplate<Object, Object> redisTemplate;

    private RedisScript<Long> limitScript;

    @Autowired
    public void setRedisTemplate1(RedisTemplate<Object, Object> redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    @Autowired
    public void setLimitScript(RedisScript<Long> limitScript)
    {
        this.limitScript = limitScript;
    }

    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) throws Throwable
    {
        int time = rateLimiter.time();
        int count = rateLimiter.count();

        String combineKey = getCombineKey(rateLimiter, point);
        List<Object> keys = Collections.singletonList(combineKey);
        try
        {
            Long number = redisTemplate.execute(limitScript, keys, count, time);
            if (StringUtils.isNull(number) || number.intValue() > count)
            {
                throw new ServiceException("访问过于频繁，请稍候再试");
            }
            log.info("限制请求'{}',当前请求'{}',缓存key'{}'", count, number.intValue(), combineKey);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            // 保留根因：Redis 连接/池异常要能被 RuntimeFailureAlarmFilter 识别为 runtime.redis.connection 告警，
            // 而不是一律记成无类型的 RuntimeException。对用户的文案不变。
            throw new RuntimeException("服务器限流异常，请稍候再试", e);
        }
    }

    public String getCombineKey(RateLimiter rateLimiter, JoinPoint point)
    {
        StringBuffer stringBuffer = new StringBuffer(rateLimiter.key());
        if (rateLimiter.limitType() == LimitType.IP)
        {
            stringBuffer.append(IpUtils.getIpAddr()).append("-");
        }
        else if (rateLimiter.limitType() == LimitType.MEMBER)
        {
            stringBuffer.append(currentMemberKey()).append("-");
        }
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = method.getDeclaringClass();
        stringBuffer.append(targetClass.getName()).append("-").append(method.getName());
        return stringBuffer.toString();
    }

    /**
     * MEMBER 限流的计数单位:小程序登录账号。取不到登录态时降级为 IP,
     * 保证注解不会因为未登录调用而静默失效(退化成全局限流)。
     */
    private String currentMemberKey()
    {
        try
        {
            LoginAppUser appUser = SecurityUtils.getLoginAppUser();
            if (appUser != null && appUser.getAppUser() != null && appUser.getAppUser().getUserId() != null)
            {
                return "m" + appUser.getAppUser().getUserId();
            }
        }
        catch (Exception ignored)
        {
            // 未登录 / 令牌无效,走 IP 降级
        }
        return "ip" + IpUtils.getIpAddr();
    }
}
