package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MerchantOperatorInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.MerchantInviteCreationVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorVO;
import com.chengyinhub.business.domain.vo.MerchantTeamVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMerchantOperatorService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MerchantOperatorServiceImpl implements IMerchantOperatorService {
    private static final long INVITE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private MerchantOperatorMapper merchantOperatorMapper;
    @Autowired private MerchantOperatorInviteMapper merchantOperatorInviteMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private MmsMerchantMapper mmsMerchantMapper;
    @Autowired private UmsMemberMapper umsMemberMapper;
    @Autowired private ClubLeaderMapper clubLeaderMapper;
    @Autowired private ClubMapper clubMapper;
    @Value("${token.secret}") private String tokenSecret;

    @Override
    public MerchantTeamVO team(Long principalMemberId) {
        MerchantAccessContext access = ownerAccess(principalMemberId);
        List<MerchantOperatorVO> operators = new ArrayList<>();
        List<MerchantOperator> rows = merchantOperatorMapper.selectTeamByMerchantId(access.getMerchantId());
        if (rows != null) for (MerchantOperator row : rows) operators.add(MerchantOperatorVO.from(row));
        List<MerchantOperatorInviteVO> invites = new ArrayList<>();
        List<MerchantOperatorInvite> inviteRows = merchantOperatorInviteMapper.selectTeamInvites(access.getMerchantId());
        Date now = new Date();
        if (inviteRows != null) {
            for (MerchantOperatorInvite row : inviteRows) {
                if (MerchantOperatorInvite.STATUS_PENDING.equals(row.getStatus())
                        && row.getExpiresAt() != null && !row.getExpiresAt().after(now)) {
                    row.setStatus(MerchantOperatorInvite.STATUS_EXPIRED);
                }
                invites.add(MerchantOperatorInviteVO.from(row));
            }
        }
        return new MerchantTeamVO(operators, invites);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantInviteCreationVO invite(Long principalMemberId, String roleCode, String requestId) {
        MerchantAccessContext access = lockCurrentOwner(principalMemberId);
        requireOperatorRole(roleCode);
        String normalizedRequestId = normalizeRequestId(requestId);
        if (requestId != null && !requestId.trim().isEmpty()) {
            MerchantOperatorInvite existing = merchantOperatorInviteMapper.selectByRequestId(
                    access.getMerchantId(), normalizedRequestId);
            if (existing != null) return exactReplay(existing, access, roleCode, normalizedRequestId);
        }

        Date now = new Date();
        MerchantOperatorInvite invite = new MerchantOperatorInvite();
        invite.setMerchantId(access.getMerchantId());
        invite.setRoleCode(roleCode);
        invite.setStatus(MerchantOperatorInvite.STATUS_PENDING);
        invite.setInvitedByMemberId(access.getPrincipalMemberId());
        invite.setInvitedAt(now);
        invite.setExpiresAt(new Date((now.getTime() + INVITE_TTL_MILLIS) / 1000L * 1000L));
        invite.setRequestId(normalizedRequestId);
        invite.setVersion(0);
        invite.setCreateTime(now);
        invite.setUpdateTime(now);
        String token = inviteToken(invite);
        invite.setTokenHash(hashToken(token));
        try {
            if (merchantOperatorInviteMapper.insertMerchantOperatorInvite(invite) != 1) {
                throw conflict("邀请创建失败，请重试");
            }
        } catch (DuplicateKeyException duplicate) {
            MerchantOperatorInvite winner = merchantOperatorInviteMapper.selectByRequestIdForUpdate(
                    access.getMerchantId(), normalizedRequestId);
            return exactReplay(winner, access, roleCode, normalizedRequestId);
        }
        audit(access, "MERCHANT_OPERATOR_INVITED", "MERCHANT_OPERATOR_INVITE", invite.getId(),
                invite.getId(), null, roleJson(roleCode), null, normalizedRequestId);
        return new MerchantInviteCreationVO(MerchantOperatorInviteVO.from(invite), token);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantOperatorVO accept(Long principalMemberId, String rawToken, String requestId) {
        if (principalMemberId == null || principalMemberId <= 0 || rawToken == null
                || rawToken.trim().length() < 16 || rawToken.length() > 256) {
            throw badRequest("邀请无效或已失效");
        }
        String normalizedRequestId = normalizeRequestId(requestId);
        String originalTokenHash = hashToken(rawToken.trim());
        MerchantOperatorInvite preview = merchantOperatorInviteMapper.selectByTokenHash(originalTokenHash);
        if (preview == null) {
            MerchantOperatorInvite acceptedPreview = merchantOperatorInviteMapper.selectAcceptedByConsumedToken(
                    principalMemberId, originalTokenHash);
            if (acceptedPreview == null) throw conflict("邀请无效或已失效");
            Long acceptedMerchantId = previewMerchantId(acceptedPreview);
            MmsMerchant merchant = lockActiveMerchant(acceptedMerchantId);
            MerchantOperatorInvite accepted = merchantOperatorInviteMapper.selectAcceptedByConsumedTokenForUpdate(
                    principalMemberId, originalTokenHash);
            requireSameInvite(acceptedPreview, accepted, acceptedMerchantId);
            requireCurrentInviter(accepted, merchant);
            lockActiveMember(principalMemberId);
            requireNoActiveClubIdentity(principalMemberId);
            return acceptedReplay(accepted, principalMemberId);
        }
        Long merchantId = previewMerchantId(preview);
        MmsMerchant merchant = lockActiveMerchant(merchantId);
        MerchantOperatorInvite invite = merchantOperatorInviteMapper.selectByTokenHashForUpdate(originalTokenHash);
        requireSameInvite(preview, invite, merchantId);
        if (!MerchantOperatorInvite.STATUS_PENDING.equals(invite.getStatus())
                || invite.getId() == null || invite.getMerchantId() == null
                || invite.getVersion() == null || !MerchantRoleCode.isOperatorRole(invite.getRoleCode())) {
            throw conflict("邀请无效或已失效");
        }
        requireCurrentInviter(invite, merchant);
        Date now = new Date();
        if (invite.getExpiresAt() == null || !invite.getExpiresAt().after(now)) throw conflict("邀请已过期");
        if (principalMemberId.equals(merchant.getMemberId())) throw conflict("商家 Owner 无需接受员工邀请");
        UmsMember member = lockActiveMember(principalMemberId);
        requireNoActiveClubIdentity(principalMemberId);
        if (mmsMerchantMapper.countMmsMerchantRowsByMemberIdForUpdate(principalMemberId) > 0) {
            throw conflict("当前账号已拥有或申请过商家，不能加入其他商家团队");
        }
        if (merchantOperatorMapper.selectActiveByMemberId(principalMemberId) != null) {
            throw conflict("当前账号已加入其他商家团队");
        }

        MerchantOperator relation = new MerchantOperator();
        relation.setMerchantId(merchant.getId());
        relation.setOperatorMemberId(principalMemberId);
        relation.setRoleCode(invite.getRoleCode());
        relation.setStatus(MerchantOperator.STATUS_ACTIVE);
        relation.setAcceptedAt(now);
        relation.setVersion(0);
        relation.setCreateBy(String.valueOf(principalMemberId));
        relation.setCreateTime(now);
        relation.setUpdateBy(String.valueOf(principalMemberId));
        relation.setUpdateTime(now);
        relation.setOperatorNickname(member.getNickname());
        relation.setOperatorAvatar(member.getAvatar());
        if (merchantOperatorMapper.insertMerchantOperator(relation) != 1
                || merchantOperatorInviteMapper.acceptCas(invite.getId(), invite.getVersion(), principalMemberId) != 1) {
            throw conflict("邀请状态已变化，请刷新");
        }
        MerchantAccessContext auditAccess = new MerchantAccessContext(principalMemberId, merchant.getId(),
                merchant.getMemberId(), invite.getRoleCode());
        audit(auditAccess, "MERCHANT_OPERATOR_JOINED", "MERCHANT_OPERATOR", relation.getId(),
                relation.getId(), null, roleJson(invite.getRoleCode()), null, normalizedRequestId);
        return MerchantOperatorVO.from(relation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantOperatorVO updateRole(Long principalMemberId, Long operatorId, String roleCode,
                                         Integer version, String requestId) {
        MerchantAccessContext access = lockCurrentOwner(principalMemberId);
        requireOperatorRole(roleCode);
        requireMutationTarget(operatorId, version, "成员参数不完整");
        String normalizedRequestId = normalizeRequestId(requestId);
        MerchantOperator relation = lockOwnedOperator(access, operatorId);
        BusinessAuditEvent receipt = selectMutationReceipt(
                access, "MERCHANT_ROLE_CHANGED", normalizedRequestId);
        if (receipt != null) {
            return exactRoleUpdateReplay(receipt, access, relation, roleCode, version, normalizedRequestId);
        }
        requireActiveVersion(relation, version, "成员角色已变化，请刷新");
        if (roleCode.equals(relation.getRoleCode())) {
            return MerchantOperatorVO.fromMutation(relation, MerchantOperatorVO.MUTATION_EXACT_RESULT);
        }
        String before = operatorStateJson(relation.getRoleCode(), MerchantOperator.STATUS_ACTIVE, version);
        String after = operatorStateJson(roleCode, MerchantOperator.STATUS_ACTIVE, version + 1);
        if (merchantOperatorMapper.updateRoleCas(operatorId, access.getMerchantId(), version, roleCode,
                String.valueOf(access.getPrincipalMemberId())) != 1) throw conflict("成员角色已变化，请刷新");
        relation.setRoleCode(roleCode);
        relation.setVersion(version + 1);
        mutationAudit(access, "MERCHANT_ROLE_CHANGED", "MERCHANT_OPERATOR", operatorId,
                before, after, null, normalizedRequestId);
        return MerchantOperatorVO.fromMutation(relation, MerchantOperatorVO.MUTATION_EXACT_RESULT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantOperatorVO remove(Long principalMemberId, Long operatorId, Integer version,
                                     String reason, String requestId) {
        MerchantAccessContext access = lockCurrentOwner(principalMemberId);
        requireMutationTarget(operatorId, version, "成员参数不完整");
        String normalizedReason = requireReason(reason);
        String normalizedRequestId = normalizeRequestId(requestId);
        MerchantOperator relation = lockOwnedOperator(access, operatorId);
        BusinessAuditEvent receipt = selectMutationReceipt(
                access, "MERCHANT_OPERATOR_REMOVED", normalizedRequestId);
        if (receipt != null) {
            return exactRemoveReplay(receipt, access, relation, version,
                    normalizedReason, normalizedRequestId);
        }
        requireActiveVersion(relation, version, "成员状态已变化，请刷新");
        String before = operatorStateJson(relation.getRoleCode(), MerchantOperator.STATUS_ACTIVE, version);
        String after = operatorStateJson(relation.getRoleCode(), MerchantOperator.STATUS_REVOKED, version + 1);
        if (merchantOperatorMapper.revokeCas(operatorId, access.getMerchantId(), version,
                access.getPrincipalMemberId(), normalizedReason,
                String.valueOf(access.getPrincipalMemberId())) != 1) throw conflict("成员状态已变化，请刷新");
        relation.setStatus(MerchantOperator.STATUS_REVOKED);
        relation.setVersion(version + 1);
        relation.setRevokedByMemberId(access.getPrincipalMemberId());
        relation.setRevokeReason(normalizedReason);
        relation.setRevokedAt(new Date());
        mutationAudit(access, "MERCHANT_OPERATOR_REMOVED", "MERCHANT_OPERATOR", operatorId,
                before, after, normalizedReason, normalizedRequestId);
        return MerchantOperatorVO.fromMutation(relation, MerchantOperatorVO.MUTATION_EXACT_RESULT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantOperatorInviteVO revokeInvite(Long principalMemberId, Long inviteId, Integer version,
                                                 String reason, String requestId) {
        MerchantAccessContext access = lockCurrentOwner(principalMemberId);
        requireMutationTarget(inviteId, version, "邀请参数不完整");
        String normalizedReason = requireReason(reason);
        String normalizedRequestId = normalizeRequestId(requestId);
        MerchantOperatorInvite invite = merchantOperatorInviteMapper.selectByIdForUpdate(inviteId);
        requireOwnedInvite(access, invite, inviteId);
        BusinessAuditEvent receipt = selectMutationReceipt(
                access, "MERCHANT_OPERATOR_INVITE_REVOKED", normalizedRequestId);
        if (receipt != null) {
            return exactInviteRevokeReplay(receipt, access, invite, version,
                    normalizedReason, normalizedRequestId);
        }
        if (!MerchantOperatorInvite.STATUS_PENDING.equals(invite.getStatus())
                || !version.equals(invite.getVersion())) throw conflict("邀请状态已变化，请刷新");
        String before = inviteStateJson(invite.getRoleCode(), MerchantOperatorInvite.STATUS_PENDING, version);
        String after = inviteStateJson(invite.getRoleCode(), MerchantOperatorInvite.STATUS_REVOKED, version + 1);
        if (merchantOperatorInviteMapper.revokeCas(inviteId, access.getMerchantId(), version,
                access.getPrincipalMemberId(), normalizedReason) != 1) throw conflict("邀请状态已变化，请刷新");
        invite.setStatus(MerchantOperatorInvite.STATUS_REVOKED);
        invite.setVersion(version + 1);
        invite.setRevokedByMemberId(access.getPrincipalMemberId());
        invite.setRevokeReason(normalizedReason);
        invite.setRevokedAt(new Date());
        mutationAudit(access, "MERCHANT_OPERATOR_INVITE_REVOKED", "MERCHANT_OPERATOR_INVITE", inviteId,
                before, after, normalizedReason, normalizedRequestId);
        return MerchantOperatorInviteVO.fromMutation(
                invite, MerchantOperatorInviteVO.MUTATION_EXACT_RESULT);
    }

    private MerchantAccessContext ownerAccess(Long principalMemberId) {
        return merchantAccessService.require(principalMemberId, MerchantPermission.OPERATOR_MANAGE);
    }

    /** 权限发现只负责定位商家；写事务必须锁商家行并以当前 owner 事实重新授权。 */
    private MerchantAccessContext lockCurrentOwner(Long principalMemberId) {
        MerchantAccessContext observed = ownerAccess(principalMemberId);
        if (observed == null || principalMemberId == null
                || !principalMemberId.equals(observed.getPrincipalMemberId())
                || !principalMemberId.equals(observed.getOwnerMemberId())
                || observed.getMerchantId() == null
                || !MerchantRoleCode.MERCHANT_OWNER.equals(observed.getRoleCode())) {
            throw forbidden("商家身份无效或权限不足");
        }
        MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(observed.getMerchantId());
        if (!isActiveMerchant(merchant) || !observed.getMerchantId().equals(merchant.getId())
                || !principalMemberId.equals(merchant.getMemberId())) {
            throw forbidden("商家 Owner 已变化或商家不可用");
        }
        lockActiveMember(principalMemberId);
        return new MerchantAccessContext(principalMemberId, merchant.getId(), merchant.getMemberId(),
                MerchantRoleCode.MERCHANT_OWNER);
    }

    private Long previewMerchantId(MerchantOperatorInvite preview) {
        if (preview == null || preview.getId() == null || preview.getMerchantId() == null
                || preview.getMerchantId() <= 0) {
            throw conflict("邀请无效或已失效");
        }
        return preview.getMerchantId();
    }

    private MmsMerchant lockActiveMerchant(Long merchantId) {
        MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(merchantId);
        if (!isActiveMerchant(merchant) || !merchantId.equals(merchant.getId())) {
            throw forbidden("商家已停用，不能加入团队");
        }
        return merchant;
    }

    private void requireSameInvite(MerchantOperatorInvite preview, MerchantOperatorInvite locked, Long merchantId) {
        if (locked == null || preview == null || preview.getId() == null
                || !preview.getId().equals(locked.getId())
                || !merchantId.equals(locked.getMerchantId())) {
            throw conflict("邀请状态已变化，请刷新");
        }
    }

    private void requireCurrentInviter(MerchantOperatorInvite invite, MmsMerchant merchant) {
        if (invite.getInvitedByMemberId() == null || merchant.getMemberId() == null
                || !merchant.getMemberId().equals(invite.getInvitedByMemberId())) {
            throw forbidden("邀请已因商家 Owner 变更失效");
        }
    }

    private void requireMutationTarget(Long targetId, Integer version, String message) {
        if (targetId == null || targetId <= 0 || version == null || version < 0) throw badRequest(message);
    }

    private MerchantOperator lockOwnedOperator(MerchantAccessContext access, Long operatorId) {
        MerchantOperator relation = merchantOperatorMapper.selectByIdForUpdate(operatorId);
        if (!validOwnedOperator(access, relation, operatorId)) {
            throw conflict("成员状态已变化，请刷新");
        }
        return relation;
    }

    private boolean validOwnedOperator(MerchantAccessContext access, MerchantOperator relation, Long operatorId) {
        return relation != null && operatorId.equals(relation.getId())
                && access.getMerchantId().equals(relation.getMerchantId())
                && relation.getOperatorMemberId() != null && relation.getOperatorMemberId() > 0
                && MerchantRoleCode.isOperatorRole(relation.getRoleCode())
                && (MerchantOperator.STATUS_ACTIVE.equals(relation.getStatus())
                    || MerchantOperator.STATUS_REVOKED.equals(relation.getStatus()))
                && relation.getVersion() != null && relation.getVersion() >= 0;
    }

    private void requireActiveVersion(MerchantOperator relation, Integer version, String message) {
        if (!MerchantOperator.STATUS_ACTIVE.equals(relation.getStatus())
                || !version.equals(relation.getVersion())) throw conflict(message);
    }

    private void requireOwnedInvite(MerchantAccessContext access, MerchantOperatorInvite invite, Long inviteId) {
        boolean knownStatus = invite != null
                && (MerchantOperatorInvite.STATUS_PENDING.equals(invite.getStatus())
                    || MerchantOperatorInvite.STATUS_ACCEPTED.equals(invite.getStatus())
                    || MerchantOperatorInvite.STATUS_REVOKED.equals(invite.getStatus())
                    || MerchantOperatorInvite.STATUS_EXPIRED.equals(invite.getStatus()));
        if (invite == null || !inviteId.equals(invite.getId())
                || !access.getMerchantId().equals(invite.getMerchantId())
                || !MerchantRoleCode.isOperatorRole(invite.getRoleCode())
                || !knownStatus || invite.getVersion() == null || invite.getVersion() < 0) {
            throw conflict("邀请状态已变化，请刷新");
        }
    }

    private void requireOperatorRole(String roleCode) {
        if (!MerchantRoleCode.isOperatorRole(roleCode)) throw badRequest("员工角色不合法");
    }

    private String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) throw badRequest("原因需为1至500字");
        return normalized;
    }

    private String normalizeRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.isEmpty()) normalized = UUID.randomUUID().toString();
        if (normalized.length() > 64) throw badRequest("requestId 不能超过64字");
        return normalized;
    }

    private boolean isActiveMerchant(MmsMerchant merchant) {
        return merchant != null && merchant.getId() != null && merchant.getMemberId() != null
                && Long.valueOf(1L).equals(merchant.getStatus())
                && Long.valueOf(1L).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
    }

    private boolean isActiveMember(UmsMember member, Long memberId) {
        return member != null && memberId.equals(member.getId())
                && Long.valueOf(1L).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    private UmsMember lockActiveMember(Long memberId) {
        if (!memberId.equals(umsMemberMapper.lockActiveMemberById(memberId))) {
            throw forbidden("当前会员不可用");
        }
        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        if (!isActiveMember(member, memberId)) throw forbidden("当前会员不可用");
        return member;
    }

    private BusinessAuditEvent selectMutationReceipt(MerchantAccessContext access,
                                                      String action, String requestId) {
        return businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "MERCHANT", access.getMerchantId(), action, requestId);
    }

    private MerchantOperatorVO exactRoleUpdateReplay(
            BusinessAuditEvent receipt, MerchantAccessContext access, MerchantOperator current,
            String requestedRole, Integer expectedVersion, String requestId) {
        String expectedAfter = operatorStateJson(
                requestedRole, MerchantOperator.STATUS_ACTIVE, expectedVersion + 1);
        boolean exactBefore = false;
        for (String role : MerchantRoleCode.operatorRoles()) {
            if (!requestedRole.equals(role) && Objects.equals(
                    operatorStateJson(role, MerchantOperator.STATUS_ACTIVE, expectedVersion),
                    receipt.getBeforeJson())) {
                exactBefore = true;
                break;
            }
        }
        boolean currentIsOriginalResult = current.getVersion().equals(expectedVersion + 1)
                && MerchantOperator.STATUS_ACTIVE.equals(current.getStatus())
                && requestedRole.equals(current.getRoleCode());
        boolean currentIsLaterAuthoritativeState = current.getVersion() > expectedVersion + 1;
        if (!exactMutationReceipt(receipt, access, "MERCHANT_ROLE_CHANGED",
                "MERCHANT_OPERATOR", current.getId(), requestId)
                || !exactBefore || !Objects.equals(expectedAfter, receipt.getAfterJson())
                || receipt.getReason() != null
                || (!currentIsOriginalResult && !currentIsLaterAuthoritativeState)) {
            throw conflict("改岗 requestId 与原请求不一致");
        }
        return MerchantOperatorVO.fromMutation(current,
                currentIsOriginalResult ? MerchantOperatorVO.MUTATION_EXACT_RESULT
                        : MerchantOperatorVO.MUTATION_LATER_AUTHORITATIVE);
    }

    private MerchantOperatorVO exactRemoveReplay(
            BusinessAuditEvent receipt, MerchantAccessContext access, MerchantOperator current,
            Integer expectedVersion, String reason, String requestId) {
        String receiptRole = exactStateTransitionRole(receipt,
                MerchantOperator.STATUS_ACTIVE, MerchantOperator.STATUS_REVOKED, expectedVersion);
        if (!exactMutationReceipt(receipt, access, "MERCHANT_OPERATOR_REMOVED",
                "MERCHANT_OPERATOR", current.getId(), requestId)
                || receiptRole == null || !Objects.equals(reason, receipt.getReason())
                || !MerchantOperator.STATUS_REVOKED.equals(current.getStatus())
                || !receiptRole.equals(current.getRoleCode())
                || current.getVersion() < expectedVersion + 1
                || !access.getPrincipalMemberId().equals(current.getRevokedByMemberId())
                || !Objects.equals(reason, current.getRevokeReason())) {
            throw conflict("移除成员 requestId 与原请求不一致");
        }
        return MerchantOperatorVO.fromMutation(current,
                current.getVersion().equals(expectedVersion + 1)
                        ? MerchantOperatorVO.MUTATION_EXACT_RESULT
                        : MerchantOperatorVO.MUTATION_LATER_AUTHORITATIVE);
    }

    private MerchantOperatorInviteVO exactInviteRevokeReplay(
            BusinessAuditEvent receipt, MerchantAccessContext access, MerchantOperatorInvite current,
            Integer expectedVersion, String reason, String requestId) {
        String receiptRole = exactInviteStateTransitionRole(receipt,
                MerchantOperatorInvite.STATUS_PENDING, MerchantOperatorInvite.STATUS_REVOKED, expectedVersion);
        if (!exactMutationReceipt(receipt, access, "MERCHANT_OPERATOR_INVITE_REVOKED",
                "MERCHANT_OPERATOR_INVITE", current.getId(), requestId)
                || receiptRole == null || !Objects.equals(reason, receipt.getReason())
                || !MerchantOperatorInvite.STATUS_REVOKED.equals(current.getStatus())
                || !receiptRole.equals(current.getRoleCode())
                || current.getVersion() < expectedVersion + 1
                || !access.getPrincipalMemberId().equals(current.getRevokedByMemberId())
                || !Objects.equals(reason, current.getRevokeReason())) {
            throw conflict("撤销邀请 requestId 与原请求不一致");
        }
        return MerchantOperatorInviteVO.fromMutation(current,
                current.getVersion().equals(expectedVersion + 1)
                        ? MerchantOperatorInviteVO.MUTATION_EXACT_RESULT
                        : MerchantOperatorInviteVO.MUTATION_LATER_AUTHORITATIVE);
    }

    private boolean exactMutationReceipt(
            BusinessAuditEvent receipt, MerchantAccessContext access, String action,
            String targetType, Long targetId, String requestId) {
        return receipt != null
                && "MERCHANT".equals(receipt.getDomainType())
                && Objects.equals(access.getMerchantId(), receipt.getDomainId())
                && Objects.equals(access.getOwnerMemberId(), receipt.getOwnerMemberId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && Objects.equals(access.getRoleCode(), receipt.getActorRoleCode())
                && action.equals(receipt.getAction())
                && targetType.equals(receipt.getTargetType())
                && Objects.equals(targetId, receipt.getTargetId())
                && Objects.equals(targetId, receipt.getRelationId())
                && requestId.equals(receipt.getRequestId());
    }

    private String exactStateTransitionRole(BusinessAuditEvent receipt,
                                            String beforeStatus, String afterStatus,
                                            Integer expectedVersion) {
        for (String role : MerchantRoleCode.operatorRoles()) {
            if (Objects.equals(operatorStateJson(role, beforeStatus, expectedVersion), receipt.getBeforeJson())
                    && Objects.equals(operatorStateJson(role, afterStatus, expectedVersion + 1),
                    receipt.getAfterJson())) return role;
        }
        return null;
    }

    private String exactInviteStateTransitionRole(BusinessAuditEvent receipt,
                                                  String beforeStatus, String afterStatus,
                                                  Integer expectedVersion) {
        for (String role : MerchantRoleCode.operatorRoles()) {
            if (Objects.equals(inviteStateJson(role, beforeStatus, expectedVersion), receipt.getBeforeJson())
                    && Objects.equals(inviteStateJson(role, afterStatus, expectedVersion + 1),
                    receipt.getAfterJson())) return role;
        }
        return null;
    }

    private String operatorStateJson(String roleCode, String status, Integer version) {
        return "{\"roleCode\":\"" + roleCode + "\",\"status\":\"" + status
                + "\",\"version\":" + version + "}";
    }

    private String inviteStateJson(String roleCode, String status, Integer version) {
        return operatorStateJson(roleCode, status, version);
    }

    private void mutationAudit(MerchantAccessContext access, String action, String targetType, Long targetId,
                               String beforeJson, String afterJson, String reason, String requestId) {
        try {
            audit(access, action, targetType, targetId, targetId,
                    beforeJson, afterJson, reason, requestId);
        } catch (DuplicateKeyException duplicate) {
            throw conflict("requestId 已用于其他团队操作");
        }
    }

    /** 接受商家身份前反查主理人档案与当前 owner 事实；两边与俱乐部入驻共用 ums_member 锁。 */
    private void requireNoActiveClubIdentity(Long memberId) {
        ClubLeader leader = clubLeaderMapper.selectClubLeaderByMemberId(memberId);
        if (leader != null && memberId.equals(leader.getMemberId())
                && Integer.valueOf(1).equals(leader.getStatus())
                && Integer.valueOf(0).equals(leader.getDelFlag())) {
            throw conflict("俱乐部主理人不能加入商家团队");
        }
        List<Long> activeOwnedClubIds = clubMapper.selectActiveOwnedClubIdsForUpdate(memberId);
        if (activeOwnedClubIds == null
                || activeOwnedClubIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw conflict("俱乐部主理人归属状态暂不可确认");
        }
        if (!activeOwnedClubIds.isEmpty()) {
            throw conflict("俱乐部主理人不能加入商家团队");
        }
    }

    private void audit(MerchantAccessContext access, String action, String targetType, Long targetId,
                       Long relationId, String beforeJson, String afterJson, String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("MERCHANT");
        event.setDomainId(access.getMerchantId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        event.setActorRoleCode(access.getRoleCode());
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setRelationId(relationId);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) throw conflict("业务审计写入失败");
    }

    private String roleJson(String roleCode) { return "{\"roleCode\":\"" + roleCode + "\"}"; }

    private MerchantInviteCreationVO exactReplay(MerchantOperatorInvite row, MerchantAccessContext access,
                                                  String roleCode, String requestId) {
        if (row == null || !access.getMerchantId().equals(row.getMerchantId())
                || !access.getPrincipalMemberId().equals(row.getInvitedByMemberId())
                || !roleCode.equals(row.getRoleCode()) || !requestId.equals(row.getRequestId())
                || !MerchantOperatorInvite.STATUS_PENDING.equals(row.getStatus())
                || row.getExpiresAt() == null || !row.getExpiresAt().after(new Date())) {
            throw conflict("该邀请请求已用于其他操作或已失效");
        }
        String token = inviteToken(row);
        String expectedHash = hashToken(token);
        if (row.getTokenHash() == null || !MessageDigest.isEqual(
                row.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("历史邀请凭证不可恢复，请使用新请求标识重建");
        }
        return new MerchantInviteCreationVO(MerchantOperatorInviteVO.from(row), token);
    }

    private MerchantOperatorVO acceptedReplay(MerchantOperatorInvite invite, Long principalMemberId) {
        if (invite.getId() == null || invite.getMerchantId() == null
                || !principalMemberId.equals(invite.getAcceptedByMemberId())
                || !MerchantOperatorInvite.STATUS_ACCEPTED.equals(invite.getStatus())
                || !MerchantRoleCode.isOperatorRole(invite.getRoleCode())) {
            throw conflict("邀请无效或已失效");
        }
        MerchantOperator relation = merchantOperatorMapper.selectActiveByMemberId(principalMemberId);
        if (relation == null || relation.getId() == null
                || !principalMemberId.equals(relation.getOperatorMemberId())
                || !invite.getMerchantId().equals(relation.getMerchantId())
                || !invite.getRoleCode().equals(relation.getRoleCode())
                || !MerchantOperator.STATUS_ACTIVE.equals(relation.getStatus())) {
            throw conflict("邀请已处理，当前经营身份已变化");
        }
        return MerchantOperatorVO.from(relation);
    }

    private String inviteToken(MerchantOperatorInvite row) {
        if (row == null || row.getMerchantId() == null || row.getInvitedByMemberId() == null
                || row.getRoleCode() == null || row.getRequestId() == null || row.getExpiresAt() == null
                || tokenSecret == null || tokenSecret.trim().isEmpty()) {
            throw new ServiceException("邀请凭证事实不完整", HttpStatus.ERROR);
        }
        String canonical = "merchant-operator-invite-v1\n" + row.getMerchantId() + "\n"
                + row.getInvitedByMemberId() + "\n" + row.getRoleCode() + "\n"
                + row.getRequestId() + "\n" + row.getExpiresAt().getTime() / 1000L;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", impossible);
        }
    }

    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
}
