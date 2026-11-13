package com.chengyinhub.framework.aspectj;

import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.ip.IpUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import java.util.Collections;

/** Both invitation lookup and joining consume the same budget, before any code is looked up. */
@Aspect
@Component
@Order(DeadlockRetryConfig.ORDER - 1)
public class TeamInviteAttemptAspect {
    @Autowired private RedisTemplate<Object, Object> redisTemplate;
    @Autowired private RedisScript<Long> limitScript;

    @Before("execution(* com.chengyinhub.business.service.impl.PlayTeamServiceImpl.join(..)) || "
            + "execution(* com.chengyinhub.business.service.impl.PlayTeamServiceImpl.info(..))")
    public void beforeAttempt(JoinPoint point) {
        Object[] args = point.getArgs();
        boolean lookup = "info".equals(point.getSignature().getName());
        if (lookup && (args[1] == null || args[1].toString().trim().isEmpty())) return;
        Long memberId = (Long) args[lookup ? 2 : 1];
        try {
            // Resolve only through the application's trusted-proxy policy, never raw forwarding headers.
            Long ip = consume("ip:" + IpUtils.getIpAddr(), 60);
            Long member = memberId != null && memberId > 0 ? consume("member:" + memberId, 10) : 0L;
            if (ip == null || ip > 60 || member == null || member > 10) {
                throw new ServiceException("访问过于频繁，请稍候再试");
            }
        } catch (ServiceException refused) {
            throw refused;
        } catch (Exception failure) {
            throw new ServiceException("服务器限流异常，请稍候再试");
        }
    }

    private Long consume(String identity, int count) {
        return redisTemplate.execute(limitScript,
                Collections.<Object>singletonList("rate_limit:team-invite:" + identity), count, 60);
    }
}
