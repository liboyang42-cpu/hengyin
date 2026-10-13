package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.ServletUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.AddressUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import eu.bitwalker.useragentutils.UserAgent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * token验证处理
 *
 * @author ruoyi
 */
@Component
public class AppTokenService
{
    private static final Logger log = LoggerFactory.getLogger(AppTokenService.class);

    // 令牌自定义标识
    @Value("${token.header}")
    private String header;

    // 令牌秘钥
    @Value("${token.secret}")
    private String secret;

    // App 会话空闲有效期（分钟）
    @Value("${token.app.idleTime}")
    private int idleTime;

    // App 会话绝对有效期（分钟）
    @Value("${token.app.absoluteTime}")
    private int absoluteTime;

    protected static final long MILLIS_SECOND = 1000;

    protected static final long MILLIS_MINUTE = 60 * MILLIS_SECOND;

    private static final Long MILLIS_MINUTE_TEN = 20 * 60 * 1000L;

    @Resource
    private RedisCache redisCache;

    @Resource
    private IAppSessionRegistryService appSessionRegistryService;

    /**
     * 获取用户身份信息
     *
     * @return 用户信息
     */
    public LoginAppUser getLoginAppUser(HttpServletRequest request) {
        // 获取请求携带的令牌
        String token = getToken(request);
        if (StringUtils.isEmpty(token)) {
            log.warn("认证失败-无token: uri={}, headerName={}", request.getRequestURI(), header);
            return null;
        }
        try {
            Claims claims = parseToken(token);
            // 解析对应的权限以及用户信息
            String uuid = (String) claims.get(Constants.LOGIN_APP_USER_KEY);
            String userKey = getTokenKey(uuid);
            LoginAppUser loginAppUser = redisCache.getCacheObject(userKey);
            if (loginAppUser == null) {
                log.warn("认证失败-Redis找不到token: uri={}, uuid={}, userKey={}", request.getRequestURI(), uuid, userKey);
            }
            return loginAppUser;
        } catch (Exception e) {
            // 客户端送来无法解析的 token 是客户端错误,不是服务端故障:记 WARN 且不带堆栈。
            // 堆栈全都落在 jjwt 内部与 servlet 调用链上,对定位零帮助,却是日志刷屏的主要来源
            // (2026-08-01 实测:20 万行样本里 1757 条 MalformedJwtException 堆栈,
            //  来源是开发版小程序把 'dev-token' 打到了生产)。
            log.warn("认证失败-JWT解析异常: uri={}, tokenPrefix={}, errorType={}, error={}",
                    request.getRequestURI(),
                    token.length() > 20 ? token.substring(0, 20) + "..." : token,
                    e.getClass().getSimpleName(),
                    e.getMessage());
        }
        return null;
    }

    /**
     * 设置用户身份信息
     */
    public void setLoginAppUser(LoginAppUser loginAppUser) {
        if (StringUtils.isNotNull(loginAppUser) && StringUtils.isNotEmpty(loginAppUser.getToken())) {
            refreshToken(loginAppUser);
        }
    }

    /**
     * 删除用户身份信息
     */
    public void delLoginAppUser(String token) {
        if (StringUtils.isNotEmpty(token)) {
            String userKey = getTokenKey(token);
            LoginAppUser loginAppUser = redisCache.getCacheObject(userKey);
            redisCache.deleteObject(userKey);
            if (loginAppUser != null) {
                appSessionRegistryService.unregisterToken(loginAppUser.getUserId(), token);
            }
        }
    }

    /** 撤销指定会员的全部 App/小程序会话。 */
    public void revokeAllMemberSessions(Long memberId) {
        appSessionRegistryService.revokeAll(memberId);
    }

    /**
     * 创建令牌
     *
     * @param loginAppUser 用户信息
     * @return 令牌
     */
    public String createAppToken(LoginAppUser loginAppUser) {
        Long credentialEpoch = loginAppUser.getSessionEpoch();
        if (credentialEpoch == null) {
            throw new com.chengyinhub.common.exception.ServiceException("登录状态已变更，请重新登录");
        }
        String token = IdUtils.fastUUID();
        loginAppUser.setToken(token);
        setUserAgent(loginAppUser);
        long epoch = appSessionRegistryService.currentOrCreateEpoch(loginAppUser.getUserId());
        if (credentialEpoch.longValue() != epoch) {
            throw new com.chengyinhub.common.exception.ServiceException("登录状态已变更，请重新登录");
        }
        loginAppUser.setSessionEpoch(epoch);
        loginAppUser.setAbsoluteExpireTime(System.currentTimeMillis() + absoluteTime * MILLIS_MINUTE);
        refreshToken(loginAppUser);
        long registryTtl = loginAppUser.getAbsoluteExpireTime() - System.currentTimeMillis();
        boolean registered = false;
        try {
            appSessionRegistryService.registerToken(loginAppUser.getUserId(), token, registryTtl);
            registered = true;
            if (!appSessionRegistryService.isCurrentEpoch(loginAppUser.getUserId(), epoch)) {
                throw new com.chengyinhub.common.exception.ServiceException("登录状态已变更，请重新登录");
            }
        } catch (RuntimeException e) {
            redisCache.deleteObject(getTokenKey(token));
            if (registered) {
                appSessionRegistryService.unregisterToken(loginAppUser.getUserId(), token);
            }
            throw e;
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_APP_USER_KEY, token);
        return createToken(claims);
    }

    /**
     * 验证令牌有效期，相差不足20分钟，自动刷新缓存
     *
     * @param loginAppUser
     * @return 当前会话是否仍在绝对有效期内
     */
    public boolean verifyToken(LoginAppUser loginAppUser) {
        long currentTime = System.currentTimeMillis();
        if (loginAppUser.getAbsoluteExpireTime() == null
                || loginAppUser.getAbsoluteExpireTime() <= currentTime) {
            delLoginAppUser(loginAppUser.getToken());
            return false;
        }
        if (loginAppUser.getSessionEpoch() == null
                || !appSessionRegistryService.isCurrentEpoch(loginAppUser.getUserId(), loginAppUser.getSessionEpoch())) {
            delLoginAppUser(loginAppUser.getToken());
            return false;
        }
        long expireTime = loginAppUser.getExpireTime();
        if (expireTime - currentTime <= MILLIS_MINUTE_TEN) {
            refreshToken(loginAppUser);
        }
        return true;
    }

    /**
     * 刷新令牌有效期
     *
     * @param loginAppUser 登录信息
     */
    public void refreshToken(LoginAppUser loginAppUser) {
        long currentTime = System.currentTimeMillis();
        if (loginAppUser.getAbsoluteExpireTime() == null) {
            delLoginAppUser(loginAppUser.getToken());
            return;
        }
        loginAppUser.setLoginTime(currentTime);
        loginAppUser.setExpireTime(Math.min(currentTime + idleTime * MILLIS_MINUTE,
                loginAppUser.getAbsoluteExpireTime()));
        long remainingMillis = loginAppUser.getExpireTime() - currentTime;
        if (remainingMillis <= 0) {
            delLoginAppUser(loginAppUser.getToken());
            return;
        }
        // 根据uuid将loginUser缓存
        String userKey = getTokenKey(loginAppUser.getToken());
        redisCache.setCacheObject(userKey, loginAppUser,
                (int) Math.min(remainingMillis, Integer.MAX_VALUE), TimeUnit.MILLISECONDS);
    }

    /**
     * 设置用户代理信息
     *
     * @param loginAppUser 登录信息
     */
    public void setUserAgent(LoginAppUser loginAppUser) {
        UserAgent userAgent = UserAgent.parseUserAgentString(ServletUtils.getRequest().getHeader("User-Agent"));
        String ip = IpUtils.getIpAddr();
        loginAppUser.setIpaddr(ip);
        loginAppUser.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        loginAppUser.setBrowser(userAgent.getBrowser().getName());
        loginAppUser.setOs(userAgent.getOperatingSystem().getName());
    }

    /**
     * 从数据声明生成令牌
     *
     * @param claims 数据声明
     * @return 令牌
     */
    private String createToken(Map<String, Object> claims) {
        String token = Jwts.builder()
                .setClaims(claims)
                .signWith(SignatureAlgorithm.HS512, secret).compact();
        return token;
    }

    /**
     * 从令牌中获取数据声明
     *
     * @param token 令牌
     * @return 数据声明
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(secret)
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 从令牌中获取用户名
     *
     * @param token 令牌
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    /**
     * 获取请求token
     *
     * @param request
     * @return token
     */
    private String getToken(HttpServletRequest request) {
        String token = request.getHeader(header);
        if (StringUtils.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX)) {
            token = token.replace(Constants.TOKEN_PREFIX, "");
        }
        return token;
    }

    private String getTokenKey(String uuid) {
        return CacheConstants.LOGIN_APP_TOKEN_KEY + uuid;
    }
}
