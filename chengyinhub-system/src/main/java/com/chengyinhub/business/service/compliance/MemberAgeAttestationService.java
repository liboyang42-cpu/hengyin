package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.mapper.MemberAgeAttestationMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1b SEC-14：注册年龄确认。只记「本人确认已满 18 周岁」与确认时间，不收生日、证件或任何年龄数字。
 * 回答「未满 18 岁」时不落库（不为未成年人留存个人信息），直接拒绝。
 */
@Service
public class MemberAgeAttestationService {
    public static final String UNDER_AGE = "AGE_UNDER_18";
    public static final String UNDER_AGE_MESSAGE = "城瘾目前只面向 18 周岁及以上的玩家，暂时无法为你提供服务。";

    private final MemberAgeAttestationMapper mapper;
    private final AgeGatePolicy policy;

    public MemberAgeAttestationService(MemberAgeAttestationMapper mapper, AgeGatePolicy policy) {
        this.mapper = mapper;
        this.policy = policy;
    }

    /** 本人当前状态：政策、是否已确认、确认时间。 */
    public Map<String, Object> status(long memberId) {
        Map<String, Object> found = memberId > 0 ? mapper.selectByMember(memberId) : null;
        Map<String, Object> row = found == null || found.get("confirmedAt") == null ? null : found;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agePolicy", policy.effective());
        data.put("configuredPolicy", policy.configured());
        data.put("guardianFlowAvailable", policy.guardianFlowAvailable());
        data.put("minimumAge", AgeGatePolicy.ADULT_AGE);
        data.put("confirmed", row != null);
        data.put("confirmedAt", row == null ? null : row.get("confirmedAt"));
        return data;
    }

    /**
     * 记录确认。adult=false 抛 {@link UnderAgeException}，不写任何行；adult=true 幂等写入（首次时间保留）。
     */
    public Map<String, Object> confirm(long memberId, boolean adult, String source, String clientIp) {
        if (memberId <= 0) throw new IllegalArgumentException("请先登录");
        if (!adult) throw new UnderAgeException();
        mapper.insertIfAbsent(memberId, policy.effective(), new Date(), normalizeSource(source), trimIp(clientIp));
        Map<String, Object> data = status(memberId);
        if (!Boolean.TRUE.equals(data.get("confirmed"))) throw new IllegalStateException("年龄确认未保存，请重试");
        return data;
    }

    static String normalizeSource(String source) {
        if (source == null) return "app";
        String value = source.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[a-z_]{1,32}") ? value : "app";
    }

    private static String trimIp(String ip) {
        if (ip == null) return "";
        return ip.length() > 64 ? ip.substring(0, 64) : ip;
    }

    public static final class UnderAgeException extends RuntimeException {
        public UnderAgeException() {
            super(UNDER_AGE_MESSAGE);
        }
    }
}
