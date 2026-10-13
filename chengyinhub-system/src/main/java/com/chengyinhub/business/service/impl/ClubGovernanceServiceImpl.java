package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.ClubGovernanceService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClubGovernanceServiceImpl implements ClubGovernanceService {
    private static final long MAX_CLUB_BAN_MILLIS = 90L * 24L * 60L * 60L * 1000L;
    @Autowired private IClubAccessService clubAccessService;
    @Autowired private ClubMapper clubMapper;
    @Autowired private ClubMemberMapper clubMemberMapper;
    @Autowired private CmsActivityMapper cmsActivityMapper;
    @Autowired private IClubParticipationGuard clubParticipationGuard;
    @Autowired private IImService imService;
    @Autowired private IClubMembershipService clubMembershipService;
    @Autowired private IClubRoleAssignmentService clubRoleAssignmentService;
    @Autowired private IClubLeaderService clubLeaderService;
    @Autowired private ICapabilityService capabilityService;
    @Autowired private IUmsMemberService umsMemberService;
    @Autowired private IRoleService roleService;
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private ClubGovernanceMapper clubGovernanceMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ClubGovernanceBan> list(Long principalMemberId, Long clubId) {
        governanceAccess(principalMemberId, clubId);
        clubGovernanceMapper.expireStaleByClub(clubId, new Date());
        List<ClubGovernanceBan> rows = clubGovernanceMapper.selectByClub(clubId);
        if (rows == null) return Collections.emptyList();
        for (ClubGovernanceBan row : rows) {
            if (row == null || !clubId.equals(row.getClubId())) throw forbidden();
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceBan ban(Long principalMemberId, Long clubId, Long targetMemberId,
                                 String reason, Date expiresAt, String requestId) {
        governanceAccess(principalMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        ClubAccessContext access = governanceAccess(principalMemberId, clubId);
        if (!isSameActiveClub(locked, access)) throw forbidden();
        String normalizedRequestId = requireRequestId(requestId);
        if (clubGovernanceMapper.selectByRequestIdForUpdate(clubId, normalizedRequestId) != null) {
            throw conflict("治理请求已处理，请勿重复提交");
        }
        if (!positive(targetMemberId)) throw badRequest("目标成员不合法");
        if (targetMemberId.equals(access.getOwnerMemberId())) throw badRequest("主理人不可被封禁");

        String normalizedReason = requireReason(reason);
        Date now = new Date();
        requireClubBanExpiry(expiresAt, now);
        clubGovernanceMapper.expireStaleByClubAndMember(clubId, targetMemberId, now);
        ClubGovernanceBan active = clubGovernanceMapper.selectEffectiveByClubAndMember(
                clubId, targetMemberId, now);
        if (isEffective(active, clubId, targetMemberId, now)) throw conflict("该成员已在有效封禁期内");
        if (active != null) throw forbidden();

        ClubMember member = clubMemberMapper.selectByClubAndMember(clubId, targetMemberId);
        if (!isActiveMember(member, clubId, targetMemberId)) {
            throw badRequest("目标账号不是本俱乐部正常成员");
        }

        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setClubId(clubId);
        row.setTargetMemberId(targetMemberId);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(ClubGovernanceBan.SOURCE_CLUB);
        row.setBanReason(normalizedReason);
        row.setExpiresAt(expiresAt);
        row.setBannedByMemberId(principalMemberId);
        row.setBannedAt(now);
        row.setBanRequestId(normalizedRequestId);
        row.setVersion(0);
        row.setCreateBy(String.valueOf(principalMemberId));
        row.setCreateTime(now);
        row.setUpdateBy(String.valueOf(principalMemberId));
        row.setUpdateTime(now);
        if (clubGovernanceMapper.insertClubGovernanceBan(row) != 1) {
            throw conflict("封禁失败，请刷新后重试");
        }
        clubRoleAssignmentService.revokeAllForAccessEnd(
                clubId, targetMemberId, principalMemberId, access.getPrimaryRoleCode(), "MEMBER_BANNED");
        removeMemberForBan(clubId, targetMemberId);
        row.setMembershipAccessEnd(clubMembershipService.handleMemberAccessEnded(
                clubId, targetMemberId, "MEMBER_BANNED", normalizedRequestId + "-membership"));
        audit(access, "CLUB_MEMBER_BANNED", row, null,
                stateJson(ClubGovernanceBan.STATUS_ACTIVE, expiresAt),
                normalizedReason, normalizedRequestId);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unban(Long principalMemberId, Long clubId, Long banId, Integer version,
                      String reason, String requestId) {
        governanceAccess(principalMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        ClubAccessContext access = governanceAccess(principalMemberId, clubId);
        if (!isSameActiveClub(locked, access)) throw forbidden();
        if (!positive(banId) || version == null || version < 0) throw badRequest("解封参数不完整");
        String normalizedRequestId = requireRequestId(requestId);
        if (clubGovernanceMapper.selectByRequestIdForUpdate(clubId, normalizedRequestId) != null) {
            throw conflict("治理请求已处理，请勿重复提交");
        }
        String normalizedReason = optionalReason(reason);
        ClubGovernanceBan row = clubGovernanceMapper.selectByIdForUpdate(banId);
        Date now = new Date();
        if (row == null || !clubId.equals(row.getClubId())) throw forbidden();
        if (ClubGovernanceBan.SOURCE_PLATFORM.equals(row.getSourceType())) {
            throw forbidden("平台封禁只能由平台审核解除");
        }
        if (!ClubGovernanceBan.STATUS_ACTIVE.equals(row.getStatus())
                || !version.equals(row.getVersion())
                || row.getExpiresAt() != null && !row.getExpiresAt().after(now)) {
            throw conflict("封禁状态已变化，请刷新");
        }
        if (clubGovernanceMapper.unbanCas(banId, clubId, version, principalMemberId,
                normalizedReason, normalizedRequestId, String.valueOf(principalMemberId)) != 1) {
            throw conflict("封禁状态已变化，请刷新");
        }
        audit(access, "CLUB_MEMBER_UNBANNED", row,
                stateJson(ClubGovernanceBan.STATUS_ACTIVE, row.getExpiresAt()),
                stateJson(ClubGovernanceBan.STATUS_UNBANNED, row.getExpiresAt()),
                normalizedReason, normalizedRequestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requireJoinAllowed(Long clubId, Long memberId) {
        requireParticipationAllowed(clubId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requireParticipationAllowed(Long clubId, Long memberId) {
        clubParticipationGuard.requireAllowed(clubId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceOwnerTransfer transferOwner(Long principalMemberId, Long clubId,
                                                      Long targetMemberId, String reason,
                                                      String requestId) {
        if (!positive(targetMemberId) || targetMemberId.equals(principalMemberId)) {
            throw badRequest("新主理人不合法");
        }
        // 俱乐部建会、配额与治理统一先拿账号身份互斥，再拿 club；涉及两个账号时
        // 必须先按 memberId 固定顺序全部锁定，避免 A→B 与 B→A 互等。
        lockTransferAccounts(principalMemberId, targetMemberId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        ClubAccessContext access = governanceAccess(principalMemberId, clubId);
        if (!access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)
                || !principalMemberId.equals(access.getOwnerMemberId())) throw forbidden();
        if (!isSameActiveClub(locked, access)) throw forbidden();
        String normalizedReason = requireGovernanceReason(reason, "转让原因");
        String normalizedRequestId = requireRequestId(requestId);
        if (clubGovernanceMapper.selectOwnerTransferByRequestForUpdate(clubId, normalizedRequestId) != null) {
            throw conflict("转让请求已处理，请勿重复提交");
        }
        ClubMember target = clubMemberMapper.selectByClubAndMemberForUpdate(clubId, targetMemberId);
        if (!isActiveMember(target, clubId, targetMemberId)) {
            throw badRequest("新主理人必须是本俱乐部正常成员");
        }
        if (clubGovernanceMapper.selectPendingSubjectCaseForUpdate(clubId, targetMemberId) != null) {
            throw conflict("新主理人存在待平台复核的治理风险，处理完成后方可转让");
        }
        UmsMember targetAccount = requireTransferTargetIdentity(targetMemberId);
        requireTransferTargetQuota(targetMemberId);
        promoteTransferTarget(targetAccount, principalMemberId);
        Date now = new Date();
        ClubGovernanceOwnerTransfer row = new ClubGovernanceOwnerTransfer();
        row.setClubId(clubId);
        row.setFromOwnerMemberId(principalMemberId);
        row.setToOwnerMemberId(targetMemberId);
        row.setReason(normalizedReason);
        row.setRequestId(normalizedRequestId);
        row.setTransferredAt(now);
        row.setCreateTime(now);
        clubRoleAssignmentService.revokeAllForAccessEnd(
                clubId, targetMemberId, principalMemberId, ClubRoleCode.CLUB_OWNER,
                "OWNER_TRANSFER_TARGET_PROMOTED");
        clubRoleAssignmentService.revokeAllForAccessEnd(
                clubId, principalMemberId, principalMemberId, ClubRoleCode.CLUB_OWNER,
                "OWNER_TRANSFER_FORMER_OWNER_DEMOTED");
        if (clubGovernanceMapper.insertOwnerTransfer(row) != 1
                || clubGovernanceMapper.deleteActiveMemberForOwnerTransfer(clubId, targetMemberId) != 1
                || clubGovernanceMapper.upsertFormerOwnerAsMember(clubId, principalMemberId, now) <= 0
                || clubGovernanceMapper.transferClubOwnerCas(clubId, principalMemberId, targetMemberId) != 1) {
            throw conflict("主理人状态已变化，请刷新后重试");
        }
        reconcileFormerOwnerIdentity(principalMemberId, clubId);
        auditEvent(clubId, principalMemberId, principalMemberId, ClubRoleCode.CLUB_OWNER,
                "CLUB_OWNER_TRANSFERRED", "CLUB_OWNER", targetMemberId, row.getId(),
                ownerStateJson(principalMemberId), ownerStateJson(targetMemberId),
                normalizedReason, normalizedRequestId);
        return row;
    }

    /** 受让人身份互斥是转让前置闸；商家关系脏数据/解析异常由 access 服务直接 fail closed。 */
    private UmsMember requireTransferTargetIdentity(Long targetMemberId) {
        UmsMember account = umsMemberService.selectUmsMemberById(targetMemberId);
        if (!isActiveAccount(account, targetMemberId)) {
            throw forbidden("新主理人账号不可用");
        }
        if (capabilityService.hasCapability(targetMemberId, MemberCapability.CAP_MERCHANT)
                || merchantAccessService.resolve(targetMemberId) != null
                || IRoleService.ROLE_MERCHANT.equals(roleService.resolveRole(account))) {
            throw conflict("商家 owner/operator 不能兼任俱乐部主理人");
        }
        return account;
    }

    /** 多俱乐部并发转让按 memberId 固定顺序加锁，串行化配额与全局能力收口。 */
    private void lockTransferAccounts(Long ownerMemberId, Long targetMemberId) {
        Long first = ownerMemberId < targetMemberId ? ownerMemberId : targetMemberId;
        Long second = ownerMemberId < targetMemberId ? targetMemberId : ownerMemberId;
        if (!first.equals(umsMemberService.lockMemberById(first))
                || !second.equals(umsMemberService.lockMemberById(second))) {
            throw forbidden("主理人账号不可用");
        }
    }

    private void requireTransferTargetQuota(Long targetMemberId) {
        List<Long> ownedClubIds = clubMapper.selectOwnedClubIdsForUpdate(targetMemberId);
        if (ownedClubIds == null || ownedClubIds.stream().anyMatch(id -> !positive(id))) {
            throw forbidden("新主理人俱乐部归属数据异常");
        }
        if (ownedClubIds.size() >= 2) throw conflict("新主理人已达到俱乐部数量上限");
    }

    private void promoteTransferTarget(UmsMember account, Long grantedBy) {
        Long targetMemberId = account.getId();
        ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(targetMemberId);
        if (leader == null) {
            leader = new ClubLeader();
            leader.setMemberId(targetMemberId);
            leader.setLeaderName(account.getNickname());
            leader.setLevel(0);
            leader.setTags(null);
            leader.setStatus(1);
            leader.setDelFlag(0);
            leader.setCreateBy(String.valueOf(grantedBy));
            if (clubLeaderService.insertClubLeader(leader) != 1) {
                throw conflict("新主理人身份建立失败，请刷新后重试");
            }
        } else if (!targetMemberId.equals(leader.getMemberId())
                || !Integer.valueOf(0).equals(leader.getDelFlag())
                || !Integer.valueOf(1).equals(leader.getStatus())) {
            throw conflict("新主理人身份状态不可用");
        }
        capabilityService.grant(targetMemberId, MemberCapability.CAP_CLUB_LEADER, grantedBy);
        if (!IRoleService.ROLE_CLUB.equals(roleService.resolveRole(account))) {
            UmsMember rolePatch = new UmsMember();
            rolePatch.setId(targetMemberId);
            rolePatch.setRole(IRoleService.ROLE_CLUB);
            if (umsMemberService.updateUmsMember(rolePatch) != 1) {
                throw conflict("新主理人默认视角更新失败，请刷新后重试");
            }
        }
    }

    private void reconcileFormerOwnerIdentity(Long formerOwnerMemberId, Long transferredClubId) {
        List<Long> activeClubIds = clubMapper.selectOwnedClubIdsForUpdate(formerOwnerMemberId);
        if (activeClubIds == null || activeClubIds.stream().anyMatch(id -> !positive(id))) {
            throw forbidden("原主理人俱乐部归属数据异常");
        }
        if (activeClubIds.contains(transferredClubId)) {
            throw conflict("主理人归属状态尚未收敛，请刷新后重试");
        }
        if (!activeClubIds.isEmpty()) {
            return;
        }
        capabilityService.revoke(formerOwnerMemberId, MemberCapability.CAP_CLUB_LEADER);
        UmsMember account = umsMemberService.selectUmsMemberById(formerOwnerMemberId);
        if (!isActiveAccount(account, formerOwnerMemberId)) {
            throw forbidden("原主理人账号不可用");
        }
        if (IRoleService.ROLE_CLUB.equals(roleService.resolveRole(account))) {
            UmsMember rolePatch = new UmsMember();
            rolePatch.setId(formerOwnerMemberId);
            rolePatch.setRole(IRoleService.ROLE_PLAYER);
            if (umsMemberService.updateUmsMember(rolePatch) != 1) {
                throw conflict("原主理人默认视角更新失败，请刷新后重试");
            }
        }
    }

    private boolean isActiveAccount(UmsMember account, Long memberId) {
        return account != null && memberId.equals(account.getId())
                && Long.valueOf(1L).equals(account.getStatus())
                && Integer.valueOf(0).equals(account.getDelFlag());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceCase submitReport(Long principalMemberId, Long clubId, String targetType,
                                           Long targetId, Map<String, String> evidence,
                                           String reason, String requestId) {
        if (!positive(principalMemberId) || !positive(clubId) || !positive(targetId)) {
            throw badRequest("举报参数不完整");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        if (!isActiveClub(locked, clubId)) throw forbidden();
        String actorRole = requireClubParticipant(locked, principalMemberId);
        String normalizedTargetType = requireReportTargetType(targetType);
        Long subjectMemberId = validateReportTarget(
                locked, principalMemberId, normalizedTargetType, targetId);
        String evidenceJson = normalizeEvidence(evidence);
        String normalizedReason = requireGovernanceReason(reason, "举报原因");
        String normalizedRequestId = requireRequestId(requestId);
        requireNewCaseRequest(clubId, principalMemberId, normalizedRequestId);
        ClubGovernanceCase row = newCase(clubId, ClubGovernanceCase.TYPE_REPORT,
                principalMemberId, subjectMemberId, normalizedTargetType, targetId,
                evidenceJson, null, normalizedReason, normalizedRequestId);
        insertCase(row);
        auditEvent(clubId, locked.getMemberId(), principalMemberId, actorRole,
                "CLUB_GOVERNANCE_REPORT_SUBMITTED", "CLUB_GOVERNANCE_CASE",
                targetId, row.getId(), null, caseStateJson(row),
                normalizedReason, "case:" + row.getId());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceCase submitAppeal(Long principalMemberId, Long clubId,
                                           String reason, String requestId) {
        if (!positive(principalMemberId) || !positive(clubId)) throw badRequest("申诉参数不完整");
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        if (!isActiveClub(locked, clubId)) throw forbidden();
        String normalizedReason = requireGovernanceReason(reason, "申诉原因");
        String normalizedRequestId = requireRequestId(requestId);
        requireNewCaseRequest(clubId, principalMemberId, normalizedRequestId);
        Date now = new Date();
        clubGovernanceMapper.expireStaleByClubAndMember(clubId, principalMemberId, now);
        ClubGovernanceBan ban = clubGovernanceMapper.selectEffectiveByClubAndMemberForUpdate(
                clubId, principalMemberId, now);
        if (!isEffective(ban, clubId, principalMemberId, now)) {
            throw conflict("当前没有可申诉的有效封禁");
        }
        if (clubGovernanceMapper.selectPendingAppealByBanForUpdate(ban.getId()) != null) {
            throw conflict("该封禁已有待处理申诉");
        }
        ClubGovernanceCase row = newCase(clubId, ClubGovernanceCase.TYPE_APPEAL,
                principalMemberId, principalMemberId, ClubGovernanceCase.TARGET_BAN, ban.getId(),
                "{}", ban.getId(), normalizedReason, normalizedRequestId);
        insertCase(row);
        auditEvent(clubId, locked.getMemberId(), principalMemberId, "BANNED_MEMBER",
                "CLUB_GOVERNANCE_APPEAL_SUBMITTED", "CLUB_GOVERNANCE_CASE",
                principalMemberId, row.getId(), null, caseStateJson(row),
                normalizedReason, "case:" + row.getId());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceBan activeBan(Long principalMemberId, Long clubId) {
        if (!positive(principalMemberId) || !positive(clubId)) throw badRequest("查询参数不完整");
        Date now = new Date();
        // 与 submitAppeal 同一套口径:先过期掉该成员身上的存量封禁,再取有效那条。
        // 这里不要求 MEMBER_MANAGE —— 申诉人是被封禁的普通成员,
        // 且查询只限定在 (clubId, principalMemberId) 本人身上,不构成越权。
        clubGovernanceMapper.expireStaleByClubAndMember(clubId, principalMemberId, now);
        ClubGovernanceBan row = clubGovernanceMapper.selectEffectiveByClubAndMember(
                clubId, principalMemberId, now);
        return isEffective(row, clubId, principalMemberId, now) ? row : null;
    }

    @Override
    public List<ClubGovernanceCase> listMyCases(Long principalMemberId, Long clubId) {
        if (!positive(principalMemberId) || !positive(clubId)) throw badRequest("查询参数不完整");
        List<ClubGovernanceCase> rows = clubGovernanceMapper.selectMyCases(clubId, principalMemberId);
        if (rows == null) return Collections.emptyList();
        for (ClubGovernanceCase row : rows) {
            if (row == null || !clubId.equals(row.getClubId())
                    || !principalMemberId.equals(row.getFilerMemberId())) throw forbidden();
        }
        return rows;
    }

    @Override
    public List<ClubGovernanceCase> listPlatformCases(String caseType, String status, Long clubId) {
        String type = optionalEnum(caseType, ClubGovernanceCase.TYPE_REPORT, ClubGovernanceCase.TYPE_APPEAL,
                "治理类型不合法");
        String caseStatus = optionalEnum(status, ClubGovernanceCase.STATUS_PENDING,
                ClubGovernanceCase.STATUS_APPROVED, ClubGovernanceCase.STATUS_REJECTED, "治理状态不合法");
        if (clubId != null && !positive(clubId)) throw badRequest("俱乐部参数不合法");
        List<ClubGovernanceCase> rows = clubGovernanceMapper.selectPlatformCases(type, caseStatus, clubId);
        return rows == null ? Collections.emptyList() : rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewPlatformCase(Long operatorUserId, Long caseId, Integer version,
                                   boolean approved, String reason, String requestId) {
        if (!positive(operatorUserId) || !positive(caseId) || version == null || version < 0) {
            throw badRequest("平台裁决参数不完整");
        }
        String normalizedReason = requireGovernanceReason(reason, "裁决说明");
        String normalizedRequestId = requireRequestId(requestId);
        ClubGovernanceCase row = clubGovernanceMapper.selectCaseByIdForUpdate(caseId);
        if (row == null || !ClubGovernanceCase.STATUS_PENDING.equals(row.getStatus())
                || !version.equals(row.getVersion()) || !positive(row.getClubId())
                || !positive(row.getFilerMemberId()) || !validCaseTarget(row)) {
            throw conflict("治理工单状态已变化，请刷新");
        }
        if (clubGovernanceMapper.selectCaseByDecisionRequestForUpdate(
                row.getClubId(), normalizedRequestId) != null) {
            throw conflict("平台裁决请求已处理，请勿重复提交");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(row.getClubId());
        if (!isClubRecord(locked, row.getClubId())) throw forbidden();
        String next = approved ? ClubGovernanceCase.STATUS_APPROVED : ClubGovernanceCase.STATUS_REJECTED;
        ClubGovernanceBan releasedBan = null;
        if (approved && ClubGovernanceCase.TYPE_APPEAL.equals(row.getCaseType())) {
            releasedBan = resolveApprovedAppeal(row, normalizedReason, normalizedRequestId);
        }
        if (clubGovernanceMapper.reviewCaseCas(row.getId(), version, next, operatorUserId,
                normalizedReason, normalizedRequestId) != 1) {
            throw conflict("治理工单状态已变化，请刷新");
        }
        if (releasedBan != null) {
            auditEvent(row.getClubId(), locked.getMemberId(), operatorUserId, "PLATFORM_AUDITOR",
                    "PLATFORM_CLUB_MEMBER_UNBANNED_ON_APPEAL", "CLUB_MEMBER_BAN",
                    releasedBan.getTargetMemberId(), releasedBan.getId(),
                    stateJson(ClubGovernanceBan.STATUS_ACTIVE, releasedBan.getSourceType(),
                            releasedBan.getExpiresAt()),
                    stateJson(ClubGovernanceBan.STATUS_UNBANNED, releasedBan.getSourceType(),
                            releasedBan.getExpiresAt()),
                    normalizedReason, normalizedRequestId);
        }
        auditEvent(row.getClubId(), locked.getMemberId(), operatorUserId, "PLATFORM_AUDITOR",
                approved ? "CLUB_GOVERNANCE_CASE_APPROVED" : "CLUB_GOVERNANCE_CASE_REJECTED",
                "CLUB_GOVERNANCE_CASE", row.getTargetId(), row.getId(),
                caseStateJson(row), "{\"status\":\"" + next + "\"}",
                normalizedReason, normalizedRequestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubGovernanceBan platformPermanentBan(Long operatorUserId, Long clubId, Long targetMemberId,
                                                  String reason, String requestId) {
        if (!positive(operatorUserId) || !positive(clubId) || !positive(targetMemberId)) {
            throw badRequest("平台封禁参数不完整");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        if (!isSameActiveClub(locked, clubId)) throw forbidden();
        if (targetMemberId.equals(locked.getMemberId())) {
            throw badRequest("主理人账号须走平台账号级处置，不能从成员封禁入口移除");
        }
        String normalizedReason = requireGovernanceReason(reason, "平台封禁原因");
        String normalizedRequestId = requireRequestId(requestId);
        if (clubGovernanceMapper.selectByRequestIdForUpdate(clubId, normalizedRequestId) != null) {
            throw conflict("平台封禁请求已处理，请勿重复提交");
        }
        Date now = new Date();
        clubGovernanceMapper.expireStaleByClubAndMember(clubId, targetMemberId, now);
        ClubGovernanceBan active = clubGovernanceMapper.selectEffectiveByClubAndMember(
                clubId, targetMemberId, now);
        if (isEffective(active, clubId, targetMemberId, now)) throw conflict("该成员已在有效封禁期内");
        if (active != null) throw forbidden();
        ClubMember target = clubMemberMapper.selectByClubAndMember(clubId, targetMemberId);
        if (!isActiveMember(target, clubId, targetMemberId)) {
            throw badRequest("目标账号不是本俱乐部正常成员");
        }
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setClubId(clubId);
        row.setTargetMemberId(targetMemberId);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(ClubGovernanceBan.SOURCE_PLATFORM);
        row.setBanReason(normalizedReason);
        row.setExpiresAt(null);
        row.setBannedByMemberId(operatorUserId);
        row.setBannedAt(now);
        row.setBanRequestId(normalizedRequestId);
        row.setVersion(0);
        row.setCreateBy("PLATFORM:" + operatorUserId);
        row.setCreateTime(now);
        row.setUpdateBy("PLATFORM:" + operatorUserId);
        row.setUpdateTime(now);
        if (clubGovernanceMapper.insertClubGovernanceBan(row) != 1) {
            throw conflict("平台封禁失败，请刷新后重试");
        }
        clubRoleAssignmentService.revokeAllForAccessEnd(
                clubId, targetMemberId, operatorUserId, "PLATFORM_AUDITOR", "MEMBER_BANNED");
        removeMemberForBan(clubId, targetMemberId);
        row.setMembershipAccessEnd(clubMembershipService.handleMemberAccessEnded(
                clubId, targetMemberId, "MEMBER_BANNED", normalizedRequestId + "-membership"));
        auditEvent(clubId, locked.getMemberId(), operatorUserId, "PLATFORM_AUDITOR",
                "PLATFORM_CLUB_MEMBER_PERMANENTLY_BANNED", "CLUB_MEMBER_BAN",
                targetMemberId, row.getId(), null,
                stateJson(ClubGovernanceBan.STATUS_ACTIVE, ClubGovernanceBan.SOURCE_PLATFORM, null),
                normalizedReason, normalizedRequestId);
        return row;
    }

    private void removeMemberForBan(Long clubId, Long memberId) {
        if (clubMemberMapper.deleteByClubAndMember(clubId, memberId) != 1
                || clubMapper.updateMemberCount(clubId, -1) != 1) {
            throw conflict("成员状态已变化，请刷新后重试");
        }
        // 与封禁记录、成员删除同一 REQUIRED 事务；失败即整体回滚，不能留下仍可发言的 IM 分叉。
        imService.leaveClubGroup(clubId, memberId);
    }

    private ClubAccessContext governanceAccess(Long principalMemberId, Long clubId) {
        ClubAccessContext access = clubAccessService.require(
                principalMemberId, clubId, null, ClubPermission.MEMBER_MANAGE);
        boolean governanceRole = access != null && (access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)
                || access.getRoleCodes().contains(ClubRoleCode.CLUB_CO_OWNER));
        if (!governanceRole || !clubId.equals(access.getClubId())
                || !principalMemberId.equals(access.getPrincipalMemberId())
                || !positive(access.getOwnerMemberId())) throw forbidden();
        return access;
    }

    private boolean isSameActiveClub(Club club, ClubAccessContext access) {
        return club != null && access.getClubId().equals(club.getId())
                && access.getOwnerMemberId().equals(club.getMemberId())
                && Long.valueOf(1L).equals(club.getStatus())
                && Integer.valueOf(0).equals(club.getDelFlag());
    }

    private boolean isSameActiveClub(Club club, Long clubId) {
        return isActiveClub(club, clubId) && positive(club.getMemberId());
    }

    private boolean isActiveClub(Club club, Long clubId) {
        return isClubRecord(club, clubId) && Long.valueOf(1L).equals(club.getStatus())
                && Integer.valueOf(0).equals(club.getDelFlag());
    }

    private boolean isClubRecord(Club club, Long clubId) {
        return club != null && clubId.equals(club.getId()) && positive(club.getMemberId());
    }

    private boolean isActiveMember(ClubMember member, Long clubId, Long memberId) {
        return member != null && member.getId() != null && clubId.equals(member.getClubId())
                && memberId.equals(member.getMemberId()) && Integer.valueOf(1).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    private boolean isEffective(ClubGovernanceBan row, Long clubId, Long memberId, Date now) {
        return row != null && clubId.equals(row.getClubId()) && memberId.equals(row.getTargetMemberId())
                && ClubGovernanceBan.STATUS_ACTIVE.equals(row.getStatus())
                && (ClubGovernanceBan.SOURCE_CLUB.equals(row.getSourceType())
                        && row.getExpiresAt() != null && row.getExpiresAt().after(now)
                    || ClubGovernanceBan.SOURCE_PLATFORM.equals(row.getSourceType())
                        && row.getExpiresAt() == null);
    }

    private void audit(ClubAccessContext access, String action, ClubGovernanceBan row,
                       String beforeJson, String afterJson, String reason, String requestId) {
        auditEvent(access.getClubId(), access.getOwnerMemberId(), access.getPrincipalMemberId(),
                access.getPrimaryRoleCode(), action, "CLUB_MEMBER_BAN", row.getTargetMemberId(),
                row.getId(), beforeJson, afterJson, reason, requestId);
    }

    private void auditEvent(Long clubId, Long ownerMemberId, Long actorId, String actorRole,
                            String action, String targetType, Long targetId, Long relationId,
                            String beforeJson, String afterJson, String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(clubId);
        event.setOwnerMemberId(ownerMemberId);
        event.setActorMemberId(actorId);
        event.setActorRoleCode(actorRole);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setRelationId(relationId);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private String stateJson(String status, Date expiresAt) {
        return stateJson(status, ClubGovernanceBan.SOURCE_CLUB, expiresAt);
    }

    private String stateJson(String status, String sourceType, Date expiresAt) {
        return "{\"status\":\"" + status + "\",\"sourceType\":\"" + sourceType
                + "\",\"expiresAt\":"
                + (expiresAt == null ? "null" : expiresAt.getTime()) + "}";
    }

    private String ownerStateJson(Long ownerMemberId) {
        return "{\"ownerMemberId\":" + ownerMemberId + "}";
    }

    private String caseStateJson(ClubGovernanceCase row) {
        return "{\"caseType\":\"" + row.getCaseType() + "\",\"status\":\""
                + row.getStatus() + "\",\"targetType\":\"" + row.getTargetType()
                + "\",\"targetId\":" + row.getTargetId() + "}";
    }

    private ClubGovernanceCase newCase(Long clubId, String type, Long filerMemberId,
                                       Long subjectMemberId, String targetType, Long targetId,
                                       String evidenceJson, Long relatedBanId,
                                       String reason, String requestId) {
        Date now = new Date();
        ClubGovernanceCase row = new ClubGovernanceCase();
        row.setClubId(clubId);
        row.setCaseType(type);
        row.setFilerMemberId(filerMemberId);
        row.setSubjectMemberId(subjectMemberId);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setEvidenceJson(evidenceJson);
        row.setRelatedBanId(relatedBanId);
        row.setReason(reason);
        row.setStatus(ClubGovernanceCase.STATUS_PENDING);
        row.setSubmitRequestId(requestId);
        row.setVersion(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private void insertCase(ClubGovernanceCase row) {
        if (clubGovernanceMapper.insertCase(row) != 1 || !positive(row.getId())) {
            throw conflict("治理工单提交失败，请刷新后重试");
        }
    }

    private void requireNewCaseRequest(Long clubId, Long filerMemberId, String requestId) {
        if (clubGovernanceMapper.selectCaseBySubmitRequestForUpdate(
                clubId, filerMemberId, requestId) != null) {
            throw conflict("治理工单请求已处理，请勿重复提交");
        }
    }

    private String requireClubParticipant(Club club, Long memberId) {
        if (memberId.equals(club.getMemberId())) return ClubRoleCode.CLUB_OWNER;
        ClubMember membership = clubMemberMapper.selectByClubAndMember(club.getId(), memberId);
        if (!isActiveMember(membership, club.getId(), memberId)) throw forbidden();
        return ClubRoleCode.CLUB_MEMBER;
    }

    private void requireClubSubject(Club club, Long memberId) {
        if (memberId.equals(club.getMemberId())) return;
        ClubMember membership = clubMemberMapper.selectByClubAndMember(club.getId(), memberId);
        if (!isActiveMember(membership, club.getId(), memberId)) {
            throw badRequest("被举报账号不是本俱乐部成员");
        }
    }

    private String requireReportTargetType(String targetType) {
        String normalized = targetType == null ? "" : targetType.trim().toUpperCase();
        if (!ClubGovernanceCase.TARGET_CLUB.equals(normalized)
                && !ClubGovernanceCase.TARGET_ACTIVITY.equals(normalized)
                && !ClubGovernanceCase.TARGET_MEMBER.equals(normalized)) {
            throw badRequest("举报目标类型只能是 CLUB、ACTIVITY 或 MEMBER");
        }
        return normalized;
    }

    private Long validateReportTarget(Club club, Long principalMemberId,
                                      String targetType, Long targetId) {
        if (ClubGovernanceCase.TARGET_CLUB.equals(targetType)) {
            if (!club.getId().equals(targetId)) throw forbidden();
            return null;
        }
        if (ClubGovernanceCase.TARGET_ACTIVITY.equals(targetType)) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityByIdForUpdate(targetId);
            if (activity == null || !targetId.equals(activity.getId())
                    || !club.getId().equals(activity.getClubId())
                    || !Integer.valueOf(0).equals(activity.getDelFlag())) throw forbidden();
            // CU-C-149:MEMBER 那一档早有「不能举报自己」,ACTIVITY 这一档只验了归属,没验是谁开的场。
            // 主理人举报自己项目的场次能一路成单(PENDING),平台收到一张没有对手的工单。
            if (principalMemberId.equals(activity.getMemberId())) throw badRequest("不能举报自己创建的活动");
            return null;
        }
        if (principalMemberId.equals(targetId)) throw badRequest("不能举报自己");
        requireClubSubject(club, targetId);
        return targetId;
    }

    private String normalizeEvidence(Map<String, String> evidence) {
        if (evidence == null || evidence.isEmpty()) return "{}";
        if (evidence.size() > 6) throw badRequest("证据索引最多6项");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : evidence.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!evidenceKey(key) || value.isEmpty() || value.contains("://")
                    || value.toLowerCase().startsWith("www.")) {
                throw badRequest("证据索引不合法");
            }
            validateEvidenceValue(key, value);
            normalized.put(key, value);
        }
        return JSON.toJSONString(normalized);
    }

    private boolean evidenceKey(String key) {
        return "occurredAt".equals(key) || "locationLabel".equals(key)
                || "orderNo".equals(key) || "messageId".equals(key)
                || "postId".equals(key) || "commentId".equals(key);
    }

    private void validateEvidenceValue(String key, String value) {
        if ("occurredAt".equals(key)) {
            if (value.length() > 32 || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}(:[0-9]{2})?")) {
                throw badRequest("证据时间格式不合法");
            }
            return;
        }
        if ("locationLabel".equals(key)) {
            if (value.length() > 100 || value.contains("\n") || value.contains("\r")) {
                throw badRequest("证据位置标签不合法");
            }
            return;
        }
        if ("orderNo".equals(key)) {
            if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw badRequest("订单索引不合法");
            return;
        }
        if (!value.matches("[1-9][0-9]{0,18}")) throw badRequest("证据ID不合法");
    }

    private boolean validCaseTarget(ClubGovernanceCase row) {
        if (!positive(row.getTargetId())) return false;
        if (ClubGovernanceCase.TYPE_REPORT.equals(row.getCaseType())) {
            if (row.getRelatedBanId() != null) return false;
            if (ClubGovernanceCase.TARGET_MEMBER.equals(row.getTargetType())) {
                return row.getTargetId().equals(row.getSubjectMemberId());
            }
            return (ClubGovernanceCase.TARGET_CLUB.equals(row.getTargetType())
                    || ClubGovernanceCase.TARGET_ACTIVITY.equals(row.getTargetType()))
                    && row.getSubjectMemberId() == null;
        }
        return ClubGovernanceCase.TYPE_APPEAL.equals(row.getCaseType())
                && ClubGovernanceCase.TARGET_BAN.equals(row.getTargetType())
                && positive(row.getSubjectMemberId())
                && row.getFilerMemberId().equals(row.getSubjectMemberId())
                && row.getTargetId().equals(row.getRelatedBanId());
    }

    private ClubGovernanceBan resolveApprovedAppeal(ClubGovernanceCase row, String reason,
                                                     String requestId) {
        if (!positive(row.getRelatedBanId()) || !positive(row.getSubjectMemberId())) throw forbidden();
        ClubGovernanceBan ban = clubGovernanceMapper.selectByIdForUpdate(row.getRelatedBanId());
        if (ban == null || !row.getClubId().equals(ban.getClubId())
                || !row.getSubjectMemberId().equals(ban.getTargetMemberId())) throw forbidden();
        Date now = new Date();
        if (!isEffective(ban, row.getClubId(), row.getSubjectMemberId(), now)) return null;
        if (clubGovernanceMapper.platformUnbanCas(ban.getId(), row.getClubId(), ban.getVersion(),
                "平台申诉通过：" + reason, requestId) != 1) {
            throw conflict("封禁状态已变化，请刷新后重试");
        }
        return ban;
    }

    private void requireClubBanExpiry(Date expiresAt, Date now) {
        if (expiresAt == null || !expiresAt.after(now)) {
            throw badRequest("俱乐部封禁期限必须晚于当前时间");
        }
        if (expiresAt.getTime() - now.getTime() > MAX_CLUB_BAN_MILLIS) {
            throw badRequest("俱乐部封禁期限最长90天");
        }
    }

    private String requireRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw badRequest("requestId 需为1至64字");
        }
        return normalized;
    }

    private String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw badRequest("封禁原因需为1至500字");
        }
        return normalized;
    }

    private String requireGovernanceReason(String reason, String label) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw badRequest(label + "需为1至500字");
        }
        return normalized;
    }

    private String optionalEnum(String value, String first, String second, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty()) return null;
        if (!first.equals(normalized) && !second.equals(normalized)) throw badRequest(message);
        return normalized;
    }

    private String optionalEnum(String value, String first, String second, String third, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty()) return null;
        if (!first.equals(normalized) && !second.equals(normalized) && !third.equals(normalized)) {
            throw badRequest(message);
        }
        return normalized;
    }

    private String optionalReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() > 500) throw badRequest("解封说明不能超过500字");
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
    private ServiceException forbidden() {
        return new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN);
    }
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
