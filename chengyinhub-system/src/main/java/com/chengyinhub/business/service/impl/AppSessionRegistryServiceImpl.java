package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.uuid.IdUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AppSessionRegistryServiceImpl implements IAppSessionRegistryService
{
    private static final long INITIAL_EPOCH = 1L;
    private static final long MUTATION_TTL_MINUTES = 30L;
    private static final DefaultRedisScript<Long> RELEASE_MUTATION_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> CURRENT_EPOCH_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return -1 end "
                    + "local current = redis.call('get', KEYS[2]) "
                    + "if not current then redis.call('set', KEYS[2], ARGV[1]); return tonumber(ARGV[1]) end "
                    + "return tonumber(current)",
            Long.class);
    private static final DefaultRedisScript<Long> VERIFY_EPOCH_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return -1 end "
                    + "local current = redis.call('get', KEYS[2]) "
                    + "if not current then return -2 end return tonumber(current)",
            Long.class);

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public long currentOrCreateEpoch(Long memberId)
    {
        Long current = stringRedisTemplate.execute(CURRENT_EPOCH_SCRIPT,
                Arrays.asList(mutationKey(memberId), epochKey(memberId)), Long.toString(INITIAL_EPOCH));
        if (current == null || current < 0)
        {
            throw new ServiceException("会员认证状态正在变更，请稍后重试");
        }
        return current;
    }

    @Override
    public boolean isCurrentEpoch(Long memberId, Long epoch)
    {
        if (memberId == null || epoch == null)
        {
            return false;
        }
        Long current = stringRedisTemplate.execute(VERIFY_EPOCH_SCRIPT,
                Arrays.asList(mutationKey(memberId), epochKey(memberId)));
        return current != null && current >= 0 && current.longValue() == epoch;
    }

    @Override
    public void registerToken(Long memberId, String token, long ttlMillis)
    {
        String key = memberTokensKey(memberId);
        SetOperations<Object, Object> tokens = redisTemplate.opsForSet();
        tokens.add(key, token);
        Boolean expires = redisTemplate.expire(key, ttlMillis, TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(expires))
        {
            tokens.remove(key, token);
            throw new ServiceException("会话登记失败，请重新登录");
        }
    }

    @Override
    public void unregisterToken(Long memberId, String token)
    {
        if (memberId != null && token != null)
        {
            redisTemplate.opsForSet().remove(memberTokensKey(memberId), token);
        }
    }

    @Override
    public void revokeAll(Long memberId)
    {
        String epochKey = epochKey(memberId);
        ValueOperations<String, String> values = stringRedisTemplate.opsForValue();
        values.setIfAbsent(epochKey, Long.toString(INITIAL_EPOCH));
        Long epoch = values.increment(epochKey);
        if (epoch == null)
        {
            throw new ServiceException("会话撤销失败");
        }
        String indexKey = memberTokensKey(memberId);
        Set<Object> tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null && !tokens.isEmpty())
        {
            List<Object> tokenKeys = new ArrayList<>();
            for (Object token : tokens)
            {
                tokenKeys.add(CacheConstants.LOGIN_APP_TOKEN_KEY + token);
            }
            redisTemplate.delete(tokenKeys);
        }
        redisTemplate.delete(indexKey);
    }

    @Override
    public String beginMemberMutation(Long memberId)
    {
        String mutationId = IdUtils.fastUUID();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                mutationKey(memberId), mutationId, MUTATION_TTL_MINUTES, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired))
        {
            throw new ServiceException("会员认证状态正在变更，请稍后重试");
        }
        try
        {
            revokeAll(memberId);
            return mutationId;
        }
        catch (RuntimeException e)
        {
            try
            {
                finishMemberMutation(memberId, mutationId);
            }
            catch (RuntimeException cleanupFailure)
            {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    @Override
    public void finishMemberMutation(Long memberId, String mutationId)
    {
        Long released = stringRedisTemplate.execute(RELEASE_MUTATION_SCRIPT,
                Collections.singletonList(mutationKey(memberId)), mutationId);
        if (!Long.valueOf(1L).equals(released))
        {
            throw new ServiceException("会员认证状态变更门闩释放失败");
        }
    }

    @Override
    public void finishMemberMutationAfterTransaction(Long memberId, String mutationId)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            finishMemberMutation(memberId, mutationId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCompletion(int status)
            {
                finishMemberMutation(memberId, mutationId);
            }
        });
    }

    private String epochKey(Long memberId)
    {
        if (memberId == null)
        {
            throw new ServiceException("会话会员不能为空");
        }
        return CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + memberId;
    }

    private String memberTokensKey(Long memberId)
    {
        if (memberId == null)
        {
            throw new ServiceException("会话会员不能为空");
        }
        return CacheConstants.LOGIN_APP_MEMBER_TOKENS_KEY + memberId;
    }

    private String mutationKey(Long memberId)
    {
        if (memberId == null)
        {
            throw new ServiceException("会话会员不能为空");
        }
        return CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + memberId;
    }

}
