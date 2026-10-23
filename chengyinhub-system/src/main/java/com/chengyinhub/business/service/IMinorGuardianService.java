package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

/**
 * DOC2 F7 监护授权：监护人本人账号发起（拿到一次性邀请码）→ 被监护人本人账号确认 → ACTIVE。
 * 业务拒绝抛 {@link com.chengyinhub.business.service.minor.MinorGuardianException}。
 */
public interface IMinorGuardianService {
    /**
     * 监护人发起一项授权。同 requestId 重放：仍 PENDING 时换发新邀请码（旧码作废），已确认则不再给码。
     * 返回视图含 inviteToken（仅此一次明文）。
     */
    Map<String, Object> createInvite(Long guardianMemberId, String requestId, String purpose, String relation,
                                     String policyVersion, Integer validDays);

    /** 被监护人确认邀请码，绑定 ward 并生效。 */
    Map<String, Object> accept(Long wardMemberId, String inviteToken, String policyVersion);

    /** 监护人或被监护人任一方撤回。 */
    Map<String, Object> revoke(Long memberId, Long authorizationId);

    /** 本人作为监护人或被监护人的授权列表。 */
    List<Map<String, Object>> listMine(Long memberId);

    /** 当前政策版本与规则摘要，供前端展示同意文本时绑定版本。 */
    Map<String, Object> policy();
}
