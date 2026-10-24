package com.chengyinhub.framework.web.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
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

/**
 * token验证处理
 * 
 * @author ruoyi
 */
@Component
public class TokenService
{
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    // 令牌自定义标识
    @Value("${token.header}")
    private String header;

    // 令牌秘钥
    @Value("${token.secret}")
    private String secret;

    // 令牌有效期（默认30分钟）
    @Value("${token.expireTime}")
    private int expireTime;

    protected static final long MILLIS_SECOND = 1000;

    protected static final long MILLIS_MINUTE = 60 * MILLIS_SECOND;

    private static final Long MILLIS_MINUTE_TWENTY = 20 * 60 * 1000L;

    @Autowired
    private RedisCache redisCache;
    @Autowired
    private com.chengyinhub.system.mapper.SysUserMapper sysUserMapper;
    @Autowired
    private SysPermissionService sysPermissionService;
    private static final long ABSOLUTE_SESSION_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * 获取用户身份信息
     * 
     * @return 用户信息
     */
    public LoginUser getLoginUser(HttpServletRequest request)
    {
        // 获取请求携带的令牌
        String token = getToken(request);
        if (StringUtils.isNotEmpty(token))
        {
            try
            {
                Claims claims = parseToken(token);
                // 解析对应的权限以及用户信息
                String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
                String userKey = getTokenKey(uuid);
                LoginUser user = redisCache.getCacheObject(userKey);
                if (user == null) return null;
                long now = System.currentTimeMillis();
                Long absolute = user.getAbsoluteExpireTime();
                if (absolute == null && user.getLoginTime() != null) absolute = user.getLoginTime() + ABSOLUTE_SESSION_MILLIS;
                com.chengyinhub.common.core.domain.entity.SysUser current = sysUserMapper.selectUserById(user.getUserId());
                if (absolute == null || now >= absolute || current == null
                        || !"0".equals(current.getStatus()) || !"0".equals(current.getDelFlag())) {
                    redisCache.deleteObject(userKey);
                    return null;
                }
                user.setAbsoluteExpireTime(absolute);
                user.setUser(current);
                user.setPermissions(sysPermissionService.getMenuPermission(current));
                return user;
            }
            catch (Exception e)
            {
                // 与 AppTokenService 同因:客户端送来无法解析的 token 是客户端错误,不是服务端故障。
                // 记 WARN 且不带堆栈 —— 堆栈全落在 jjwt 内部与 servlet 调用链上,对定位零帮助,
                // 却是日志刷屏的主要来源(2026-08-01 生产 3.1GB 日志的成因之一)。
                log.warn("认证失败-JWT解析异常: uri={}, errorType={}",
                        request.getRequestURI(), e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * 设置用户身份信息
     */
    public void setLoginUser(LoginUser loginUser)
    {
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNotEmpty(loginUser.getToken()))
        {
            refreshToken(loginUser);
        }
    }

    /**
     * 删除用户身份信息
     */
    public void delLoginUser(String token)
    {
        if (StringUtils.isNotEmpty(token))
        {
            String userKey = getTokenKey(token);
            redisCache.deleteObject(userKey);
        }
    }

    /**
     * 创建令牌
     * 
     * @param loginUser 用户信息
     * @return 令牌
     */
    public String createToken(LoginUser loginUser)
    {
        String token = IdUtils.fastUUID();
        loginUser.setToken(token);
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setAbsoluteExpireTime(loginUser.getLoginTime() + ABSOLUTE_SESSION_MILLIS);
        setUserAgent(loginUser);
        refreshToken(loginUser);

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, token);
        claims.put(Constants.JWT_USERNAME, loginUser.getUsername());
        return createToken(claims);
    }

    /**
     * 验证令牌有效期，相差不足20分钟，自动刷新缓存
     * 
     * @param loginUser 登录信息
     * @return 令牌
     */
    public void verifyToken(LoginUser loginUser)
    {
        long expireTime = loginUser.getExpireTime();
        long currentTime = System.currentTimeMillis();
        if (expireTime - currentTime <= MILLIS_MINUTE_TWENTY)
        {
            refreshToken(loginUser);
        }
    }

    public void refreshToken(LoginUser loginUser)
    {
        long now = System.currentTimeMillis();
        if (loginUser.getLoginTime() == null) loginUser.setLoginTime(now);
        Long absolute = loginUser.getAbsoluteExpireTime();
        if (absolute == null) absolute = loginUser.getLoginTime() + ABSOLUTE_SESSION_MILLIS;
        loginUser.setAbsoluteExpireTime(absolute);
        String userKey = getTokenKey(loginUser.getToken());
        if (now >= absolute) {
            redisCache.deleteObject(userKey);
            return;
        }
        loginUser.setExpireTime(Math.min(now + expireTime * MILLIS_MINUTE, absolute));
        long remainingSeconds = Math.max(1, (loginUser.getExpireTime() - now) / 1000);
        redisCache.setCacheObject(userKey, loginUser, (int) remainingSeconds, TimeUnit.SECONDS);
    }

    /**
     * 设置用户代理信息
     * 
     * @param loginUser 登录信息
     */
    public void setUserAgent(LoginUser loginUser)
    {
        UserAgent userAgent = UserAgent.parseUserAgentString(ServletUtils.getRequest().getHeader("User-Agent"));
        String ip = IpUtils.getIpAddr();
        loginUser.setIpaddr(ip);
        loginUser.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        loginUser.setBrowser(userAgent.getBrowser().getName());
        loginUser.setOs(userAgent.getOperatingSystem().getName());
    }

    /**
     * 从数据声明生成令牌
     *
     * @param claims 数据声明
     * @return 令牌
     */
    private String createToken(Map<String, Object> claims)
    {
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
    private Claims parseToken(String token)
    {
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
    public String getUsernameFromToken(String token)
    {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    /**
     * 获取请求token
     *
     * @param request
     * @return token
     */
    private String getToken(HttpServletRequest request)
    {
        String token = request.getHeader(header);
        if (StringUtils.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX))
        {
            token = token.replace(Constants.TOKEN_PREFIX, "");
        }
        return token;
    }

    private String getTokenKey(String uuid)
    {
        return CacheConstants.LOGIN_TOKEN_KEY + uuid;
    }
}
