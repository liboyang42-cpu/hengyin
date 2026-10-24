package com.chengyinhub.business.service.workshop;

import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * C10 exclusive H5 session primitive gate. Not a service and not mounted on a route.
 *
 * <p>Framework types stay on their side of the module cycle. The public methods
 * {@code JwtAuthenticationTokenFilter.doFilter}, {@code AppTokenService.getLoginAppUser},
 * and {@code AppTokenService.verifyToken} are invoked on the instances already passed in.
 * {@code createAppToken} is not called. Buyer-license names {@code licenses}, {@code license},
 * {@code forLicense}, {@code history}, and {@code read} are not on these collaborators, so
 * that check stays closed. No shell host, approval flag, or session TTL is introduced.
 * The main session token is not returned as an H5 ticket, and a ticket is not placed in a URL.
 * Issuance is not executed: {@code setIfAbsent}, {@code compareAndSet}, and
 * {@code compareAndDelete} are not called.
 */
public final class H5SessionPrimitiveLanding {

    private static final String AUTH_FILTER = "com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter";

    private static final String APP_TOKENS = "com.chengyinhub.framework.web.service.AppTokenService";

    private static final String[] ABSENT_LICENSE_METHODS = {
        "licenses", "license", "forLicense", "history", "read"
    };

    private final Object authFilter;
    private final Object appTokenService;
    private final BaseController actors;
    private final IAppSessionRegistryService epochs;
    private final RedisCache redis;

    public H5SessionPrimitiveLanding(Object authFilter, Object appTokenService, BaseController actors,
                                     IAppSessionRegistryService epochs, RedisCache redis) {
        this.authFilter = authFilter;
        this.appTokenService = appTokenService;
        this.actors = actors;
        this.epochs = epochs;
        this.redis = redis;
    }

    public enum Outcome {
        CLOSED
    }

    /**
     * Always closed. {@code launchTicket}, {@code shellUrl}, {@code parentOrigin}, and
     * {@code returnedTtlSeconds} stay empty: this type does not issue.
     */
    public record Decision(
            Outcome outcome,
            String launchTicket,
            String shellUrl,
            String parentOrigin,
            boolean liveEnablementBlocked,
            Integer returnedTtlSeconds,
            String reason) {

        public Decision {
            outcome = Outcome.CLOSED;
            launchTicket = null;
            shellUrl = null;
            parentOrigin = null;
            liveEnablementBlocked = true;
            returnedTtlSeconds = null;
            reason = reason == null ? "" : reason;
        }
    }

    public Decision createSession(HttpServletRequest request, HttpServletResponse response,
                                  String run, String objective, String version) {
        if (authFilter == null || appTokenService == null || actors == null || epochs == null
                || redis == null || request == null || response == null
                || !isType(authFilter, AUTH_FILTER) || !isType(appTokenService, APP_TOKENS)) {
            return closed("primitive missing");
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(Constants.APP_URI)) {
            return closed("auth filter route missing");
        }
        try {
            call(authFilter, "doFilter",
                    new Class<?>[] {ServletRequest.class, ServletResponse.class, FilterChain.class},
                    new Object[] {request, response, (FilterChain) (ignoredRequest, ignoredResponse) -> { }});
        } catch (RuntimeException ex) {
            return closed("auth filter result missing");
        }
        final LoginAppUser authenticated;
        try {
            Object raw = call(appTokenService, "getLoginAppUser",
                    new Class<?>[] {HttpServletRequest.class}, new Object[] {request});
            authenticated = raw instanceof LoginAppUser login ? login : null;
        } catch (RuntimeException ex) {
            return closed("AppTokenService result missing");
        }
        if (authenticated == null) {
            return closed("AppTokenService result missing");
        }
        try {
            Object verified = call(appTokenService, "verifyToken",
                    new Class<?>[] {LoginAppUser.class}, new Object[] {authenticated});
            if (!(verified instanceof Boolean ok) || !ok.booleanValue()) {
                return closed("AppTokenService result missing");
            }
        } catch (RuntimeException ex) {
            return closed("AppTokenService result missing");
        }
        String mainSessionToken = authenticated.getToken();
        if (carries(run, mainSessionToken) || carries(objective, mainSessionToken) || carries(version, mainSessionToken)) {
            return closed("main jwt is not the h5 ticket");
        }
        final LoginAppUser actorUser;
        final Long appUserId;
        try {
            actorUser = actors.getLoginAppUser();
            appUserId = actors.getAppUserId();
        } catch (RuntimeException ex) {
            return closed("actor missing");
        }
        if (actorUser == null || appUserId == null || appUserId.longValue() <= 0 || actorUser.getUserId() == null
                || actorUser.getUserId().longValue() != appUserId.longValue()) {
            return closed("actor missing");
        }
        if (actorUser.getSessionEpoch() == null) {
            return closed("epoch missing");
        }
        final boolean currentEpoch;
        try {
            currentEpoch = epochs.isCurrentEpoch(appUserId, actorUser.getSessionEpoch());
        } catch (RuntimeException ex) {
            return closed("epoch missing");
        }
        if (!currentEpoch) {
            return closed("epoch missing");
        }
        if (!present(run) || !present(objective) || !present(version)) {
            return closed("run objective version missing");
        }
        String key = redisReadKey(appUserId.longValue(), version);
        if (key == null || carries(key, mainSessionToken)) {
            return closed("limited ttl missing");
        }
        final long reportedTtl;
        try {
            reportedTtl = redis.getExpire(key);
        } catch (RuntimeException ex) {
            return closed("limited ttl missing");
        }
        if (reportedTtl <= 0 || reportedTtl > Integer.MAX_VALUE) {
            return closed("limited ttl missing");
        }
        try {
            redis.getCacheObject(key);
        } catch (RuntimeException ex) {
            return closed("redis read missing");
        }
        if (!licensePrimitivePresent(authFilter, appTokenService, actors, epochs, redis)) {
            return closed("license missing");
        }
        return closed("issuance not executed");
    }

    /**
     * Read key for an already-authenticated actor and the version on the call.
     * It is not a URL and it is not a launch ticket. The license-bound key cannot be
     * formed while {@code licenses}, {@code license}, {@code forLicense}, {@code history},
     * and {@code read} are absent, so this read never supplies a TTL to a write.
     */
    static String redisReadKey(long appUserId, String version) {
        if (appUserId <= 0 || !present(version) || version.contains("://")) {
            return null;
        }
        return appUserId + "\u001f" + version;
    }

    private static boolean licensePrimitivePresent(Object authFilter, Object appTokenService, BaseController actors,
                                                   IAppSessionRegistryService epochs, RedisCache redis) {
        Object[] targets = {authFilter, appTokenService, actors, epochs, redis};
        for (Object target : targets) {
            if (target == null) {
                continue;
            }
            for (Method method : target.getClass().getMethods()) {
                for (String name : ABSENT_LICENSE_METHODS) {
                    if (name.equals(method.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isType(Object value, String className) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (className.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object[] args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(name, cause);
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            throw new IllegalStateException(name, ex);
        }
    }

    private static Decision closed(String reason) {
        return new Decision(Outcome.CLOSED, null, null, null, true, null, reason);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** True when a value is the main session token. A short secret matches only by equality. */
    private static boolean carries(String value, String secret) {
        if (value == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (value.equals(secret)) {
            return true;
        }
        return secret.length() >= 8 && value.contains(secret);
    }
}
