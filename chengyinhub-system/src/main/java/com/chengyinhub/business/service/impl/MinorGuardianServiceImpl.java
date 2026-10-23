package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MinorGuardianAuthorization;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MinorGuardianAuthorizationMapper;
import com.chengyinhub.business.service.IMinorGuardianService;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.GuardianPurpose;
import com.chengyinhub.business.service.minor.MinorGuardianException;
import com.chengyinhub.business.service.minor.MinorPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DOC2 F7 监护授权实现。
 *
 * <p>★ 监护证明只有「监护人本人账号（自填出生日期为成年）发起 + 被监护人本人账号确认」，
 * 短信验证码不参与、也不能单独构成证明；不收身份证、不收生物特征。
 * <p>★ 一条授权只绑一个用途；PURCHASE 与 INFO_PROCESS 分开发起、分开确认。
 * <p>★ 有效期不晚于被监护人满 18 周岁当天 0 点；政策版本变化后旧授权不再被执行挂点认可。
 */
@Service
public class MinorGuardianServiceImpl implements IMinorGuardianService {
    static final Set<String> RELATIONS = Set.of("PARENT", "GUARDIAN");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MinorGuardianAuthorizationMapper mapper;
    private final IMinorProtectionService protectionService;
    private final IUmsMemberService memberService;
    private Clock clock = Clock.system(MinorPolicy.ZONE);

    public MinorGuardianServiceImpl(MinorGuardianAuthorizationMapper mapper,
                                    IMinorProtectionService protectionService,
                                    IUmsMemberService memberService) {
        this.mapper = mapper;
        this.protectionService = protectionService;
        this.memberService = memberService;
    }

    /** 测试注入固定时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createInvite(Long guardianMemberId, String requestId, String purposeRaw,
                                            String relation, String policyVersion, Integer validDays) {
        requireMember(guardianMemberId);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new MinorGuardianException(MinorGuardianException.ARGUMENT_INVALID, "请求标识不合法");
        }
        GuardianPurpose purpose = GuardianPurpose.parse(purposeRaw);
        if (purpose == null) throw new MinorGuardianException(MinorGuardianException.PURPOSE_INVALID, "授权用途不合法");
        if (relation == null || !RELATIONS.contains(relation)) {
            throw new MinorGuardianException(MinorGuardianException.RELATION_INVALID, "监护关系不合法");
        }
        requireCurrentPolicy(policyVersion);
        if (validDays == null || validDays < 1 || validDays > MinorPolicy.MAX_VALID_DAYS) {
            throw new MinorGuardianException(MinorGuardianException.ARGUMENT_INVALID,
                    "有效期须为 1-" + MinorPolicy.MAX_VALID_DAYS + " 天");
        }
        if (protectionService.ageBand(guardianMemberId) != AgeBand.ADULT) {
            throw new MinorGuardianException(MinorGuardianException.GUARDIAN_NOT_ADULT, "监护人须为已填写出生日期的成年人");
        }

        Instant now = clock.instant();
        String token = newToken();
        Date inviteExpiresAt = Date.from(now.plus(MinorPolicy.INVITE_TTL_HOURS, ChronoUnit.HOURS));

        MinorGuardianAuthorization replay = mapper.selectByRequest(guardianMemberId, requestId);
        if (replay != null) {
            if (!purpose.name().equals(replay.getPurpose()) || !relation.equals(replay.getRelation())
                    || !validDays.equals(replay.getValidDays())) {
                throw new MinorGuardianException(MinorGuardianException.ARGUMENT_INVALID, "请求标识已用于另一项授权");
            }
            if (!MinorGuardianAuthorization.STATUS_PENDING.equals(replay.getStatus())) {
                return view(replay, guardianMemberId);
            }
            if (mapper.rotateInvite(replay.getId(), guardianMemberId, sha256(token), inviteExpiresAt, Date.from(now)) != 1) {
                throw new MinorGuardianException(MinorGuardianException.STATE_CONFLICT, "授权状态已变化，请刷新");
            }
            return withToken(view(mapper.selectById(replay.getId()), guardianMemberId), token, inviteExpiresAt);
        }

        MinorGuardianAuthorization row = new MinorGuardianAuthorization();
        row.setGuardianMemberId(guardianMemberId);
        row.setPurpose(purpose.name());
        row.setPolicyVersion(MinorPolicy.POLICY_VERSION);
        row.setRelation(relation);
        row.setEvidenceMethod(MinorGuardianAuthorization.EVIDENCE_MUTUAL_ACCOUNT_CONFIRM);
        row.setStatus(MinorGuardianAuthorization.STATUS_PENDING);
        row.setRequestId(requestId);
        row.setInviteTokenHash(sha256(token));
        row.setInviteExpiresAt(inviteExpiresAt);
        row.setValidDays(validDays);
        row.setCreateTime(Date.from(now));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new MinorGuardianException(MinorGuardianException.STATE_CONFLICT, "授权正在处理，请稍后刷新");
        }
        return withToken(view(row, guardianMemberId), token, inviteExpiresAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accept(Long wardMemberId, String inviteToken, String policyVersion) {
        requireMember(wardMemberId);
        requireCurrentPolicy(policyVersion);
        if (inviteToken == null || inviteToken.isBlank() || inviteToken.length() > 128) {
            throw new MinorGuardianException(MinorGuardianException.INVITE_INVALID, "邀请码无效或已过期");
        }
        String hash = sha256(inviteToken.trim());
        MinorGuardianAuthorization row = mapper.selectByInviteHashForUpdate(hash);
        Instant now = clock.instant();
        if (row == null || !MinorGuardianAuthorization.STATUS_PENDING.equals(row.getStatus())
                || row.getInviteExpiresAt() == null || !row.getInviteExpiresAt().toInstant().isAfter(now)) {
            throw new MinorGuardianException(MinorGuardianException.INVITE_INVALID, "邀请码无效或已过期");
        }
        if (row.getGuardianMemberId().equals(wardMemberId)) {
            throw new MinorGuardianException(MinorGuardianException.SELF_GUARDIAN, "不能为自己确认监护授权");
        }
        if (!MinorPolicy.POLICY_VERSION.equals(row.getPolicyVersion())) {
            throw new MinorGuardianException(MinorGuardianException.POLICY_VERSION_MISMATCH, "政策已更新，请监护人重新发起");
        }
        AgeBand wardBand = protectionService.ageBand(wardMemberId);
        if (wardBand == AgeBand.UNKNOWN) {
            throw new MinorGuardianException(MinorGuardianException.WARD_AGE_UNKNOWN, "请先在个人资料中填写出生日期");
        }
        if (!wardBand.isMinor()) {
            throw new MinorGuardianException(MinorGuardianException.WARD_NOT_MINOR, "仅未成年人需要监护授权");
        }
        if (GuardianPurpose.SOLO_DAYTIME_PLAY.name().equals(row.getPurpose()) && wardBand != AgeBand.AGE_16_17) {
            throw new MinorGuardianException(MinorGuardianException.PURPOSE_INVALID, "单独参与授权仅适用于 16-17 周岁");
        }
        if (protectionService.ageBand(row.getGuardianMemberId()) != AgeBand.ADULT) {
            throw new MinorGuardianException(MinorGuardianException.GUARDIAN_NOT_ADULT, "监护人须为已填写出生日期的成年人");
        }

        Date validFrom = Date.from(now);
        Date validUntil = validUntil(now, row.getValidDays(), wardMemberId);
        if (mapper.activate(row.getId(), hash, wardMemberId, validFrom, validUntil, validFrom) != 1) {
            throw new MinorGuardianException(MinorGuardianException.STATE_CONFLICT, "授权状态已变化，请刷新");
        }
        mapper.supersedeActive(wardMemberId, row.getGuardianMemberId(), row.getPurpose(), row.getId(), validFrom);
        return view(mapper.selectById(row.getId()), wardMemberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revoke(Long memberId, Long authorizationId) {
        requireMember(memberId);
        MinorGuardianAuthorization row = authorizationId == null ? null : mapper.selectById(authorizationId);
        if (row == null || !isParty(row, memberId)) {
            throw new MinorGuardianException(MinorGuardianException.NOT_FOUND, "授权不存在");
        }
        if (mapper.revoke(row.getId(), memberId, Date.from(clock.instant())) != 1) {
            throw new MinorGuardianException(MinorGuardianException.STATE_CONFLICT, "该授权已失效或已撤回");
        }
        return view(mapper.selectById(row.getId()), memberId);
    }

    @Override
    public List<Map<String, Object>> listMine(Long memberId) {
        requireMember(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MinorGuardianAuthorization row : mapper.selectByMember(memberId)) out.add(view(row, memberId));
        return out;
    }

    @Override
    public Map<String, Object> policy() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyVersion", MinorPolicy.POLICY_VERSION);
        p.put("daytimeStart", MinorPolicy.DAYTIME_START.toString());
        p.put("daytimeEnd", MinorPolicy.DAYTIME_END.toString());
        p.put("maxValidDays", MinorPolicy.MAX_VALID_DAYS);
        p.put("inviteTtlHours", MinorPolicy.INVITE_TTL_HOURS);
        List<String> purposes = new ArrayList<>();
        for (GuardianPurpose purpose : GuardianPurpose.values()) purposes.add(purpose.name());
        p.put("purposes", purposes);
        p.put("relations", List.of("PARENT", "GUARDIAN"));
        return p;
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 有效期 = min(确认时刻 + validDays 天, 被监护人满 18 周岁当天 0 点)。 */
    private Date validUntil(Instant now, Integer validDays, Long wardMemberId) {
        Instant byDays = now.plus(validDays, ChronoUnit.DAYS);
        UmsMember ward = memberService.selectUmsMemberById(wardMemberId);
        LocalDate birthday = ward == null ? null : MinorPolicy.toLocalDate(ward.getBirthday());
        if (birthday == null) return Date.from(byDays);
        Instant adulthood = birthday.plusYears(18).atStartOfDay(MinorPolicy.ZONE).toInstant();
        return Date.from(adulthood.isBefore(byDays) ? adulthood : byDays);
    }

    private static boolean isParty(MinorGuardianAuthorization row, Long memberId) {
        return memberId.equals(row.getGuardianMemberId()) || memberId.equals(row.getWardMemberId());
    }

    /** 对外视图：不含邀请码摘要、请求标识。 */
    static Map<String, Object> view(MinorGuardianAuthorization row, Long viewerMemberId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", row.getId());
        v.put("role", viewerMemberId != null && viewerMemberId.equals(row.getGuardianMemberId()) ? "GUARDIAN" : "WARD");
        v.put("guardianMemberId", row.getGuardianMemberId());
        v.put("wardMemberId", row.getWardMemberId());
        v.put("purpose", row.getPurpose());
        v.put("relation", row.getRelation());
        v.put("policyVersion", row.getPolicyVersion());
        v.put("evidenceMethod", row.getEvidenceMethod());
        v.put("status", row.getStatus());
        v.put("validDays", row.getValidDays());
        v.put("validFrom", row.getValidFrom());
        v.put("validUntil", row.getValidUntil());
        v.put("createTime", row.getCreateTime());
        return v;
    }

    private static Map<String, Object> withToken(Map<String, Object> view, String token, Date expiresAt) {
        view.put("inviteToken", token);
        view.put("inviteExpiresAt", expiresAt);
        return view;
    }

    private static void requireCurrentPolicy(String policyVersion) {
        if (!MinorPolicy.POLICY_VERSION.equals(policyVersion)) {
            throw new MinorGuardianException(MinorGuardianException.POLICY_VERSION_MISMATCH, "政策版本不一致，请刷新后重新确认");
        }
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new MinorGuardianException(MinorGuardianException.ARGUMENT_INVALID, "请先登录");
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
