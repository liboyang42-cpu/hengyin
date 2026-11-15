package com.chengyinhub.business.push;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.push.PushMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 推送设备登记（/api/push/device/register、/api/push/device/unregister）。
 * 同一 token+环境 只属于一个会员：换账号登录后再次登记会把 token 改绑到当前会员。
 */
@Service
public class PushDeviceService
{
    static final Set<String> REGISTER_KEYS = Set.of("platform", "token", "environment", "bundleId", "locale");
    static final Set<String> UNREGISTER_KEYS = Set.of("token");

    private final PushMapper mapper;

    public PushDeviceService(PushMapper mapper)
    {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Long memberId, Map<String, Object> body)
    {
        long member = requireMember(memberId);
        if (body == null || !REGISTER_KEYS.containsAll(body.keySet())) throw bad("请求字段不正确");
        String platform = text(body.get("platform"), "platform");
        if (!"IOS".equals(platform)) throw bad("platform 只支持 IOS");
        String token = token(body.get("token"));
        String environment = text(body.get("environment"), "environment");
        if (!"SANDBOX".equals(environment) && !"PRODUCTION".equals(environment)) throw bad("environment 只能是 SANDBOX 或 PRODUCTION");
        String bundleId = text(body.get("bundleId"), "bundleId");
        if (bundleId.length() > 128 || !bundleId.matches("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")) throw bad("bundleId 格式不正确");
        Object rawLocale = body.get("locale");
        String locale = rawLocale == null ? null : text(rawLocale, "locale");
        if (locale != null && (locale.length() > 35 || !locale.matches("[A-Za-z]{2,8}([-_][A-Za-z0-9]{1,8}){0,4}"))) throw bad("locale 格式不正确");
        mapper.upsertDevice(member, platform, token, environment, bundleId, locale);
        Long id = mapper.selectDeviceId(token, environment);
        if (id == null) throw new ServiceException("设备登记失败，请稍后重试", HttpStatus.ERROR);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", id);
        out.put("enabled", true);
        return out;
    }

    /** 只停用属于调用者的 token；不存在或已停用也算成功（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unregister(Long memberId, Map<String, Object> body)
    {
        long member = requireMember(memberId);
        if (body == null || !UNREGISTER_KEYS.equals(body.keySet())) throw bad("请求字段不正确");
        mapper.disableOwnedToken(member, token(body.get("token")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", false);
        return out;
    }

    static String token(Object raw)
    {
        String token = text(raw, "token");
        if (token.length() < 64 || token.length() > 200 || !token.matches("[0-9A-Fa-f]+") || token.length() % 2 != 0)
            throw bad("token 必须是 64–200 位十六进制");
        return token.toLowerCase(Locale.ROOT);
    }

    private static String text(Object raw, String field)
    {
        if (!(raw instanceof String s) || s.isBlank()) throw bad(field + " 不能为空");
        return s.trim();
    }

    private static long requireMember(Long memberId)
    {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        return memberId;
    }

    private static ServiceException bad(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
