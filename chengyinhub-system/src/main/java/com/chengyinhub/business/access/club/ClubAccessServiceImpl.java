package com.chengyinhub.business.access.club;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClubAccessServiceImpl implements IClubAccessService {
    @Autowired private ClubMapper clubMapper;
    @Autowired private ClubMemberMapper clubMemberMapper;
    @Autowired private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    @Autowired private CmsActivityMapper cmsActivityMapper;
    @Autowired private UmsMemberMapper umsMemberMapper;

    @Override
    public ClubAccessContext require(Long principalMemberId, Long clubId, Long activityId, String permission) {
        if (permission == null || !ClubPermission.all().contains(permission)) throw forbidden();
        ClubAccessContext access = resolve(principalMemberId, clubId, activityId);
        if (access == null || !access.hasPermission(permission)) throw forbidden();
        return access;
    }

    @Override
    public ClubAccessContext resolve(Long principalMemberId, Long clubId, Long activityId) {
        return resolve(principalMemberId, clubId, activityId, false);
    }

    @Override
    public ClubAccessContext resolveWithEventAccesses(Long principalMemberId, Long clubId) {
        return resolve(principalMemberId, clubId, null, true);
    }

    private ClubAccessContext resolve(Long principalMemberId, Long clubId, Long activityId,
                                      boolean includeEventAccesses) {
        if (!positive(principalMemberId) || !positive(clubId) || (activityId != null && !positive(activityId))) {
            throw forbidden();
        }
        Club club = clubMapper.selectClubById(clubId);
        if (!isActiveClub(club, clubId)) throw forbidden();
        if (activityId != null) requireOwnedActivity(activityId, clubId);
        Long lockedPrincipal = umsMemberMapper.lockActiveMemberById(principalMemberId);
        if (!principalMemberId.equals(lockedPrincipal)) throw forbidden();
        if (principalMemberId.equals(club.getMemberId())) {
            com.chengyinhub.business.domain.UmsMember owner = umsMemberMapper.selectRolePermissionSubject(principalMemberId);
            if (owner == null || !com.chengyinhub.business.service.IRoleService.ROLE_CLUB.equals(owner.getRole())) throw forbidden();
            return new ClubAccessContext(principalMemberId, clubId, club.getMemberId(), activityId,
                    Collections.singleton(ClubRoleCode.CLUB_OWNER));
        }
        ClubMember membership = clubMemberMapper.selectByClubAndMember(clubId, principalMemberId);
        if (!isActiveMembership(membership, clubId, principalMemberId)) return null;

        Date now = new Date();
        Set<String> roles = new LinkedHashSet<>();
        roles.add(ClubRoleCode.CLUB_MEMBER);
        if (Integer.valueOf(1).equals(membership.getRole())) {
            // 兼容既有 club_member.role=1：只派生“管人和内容治理”能力，不进入可分配角色目录。
            roles.add(ClubRoleCode.CLUB_LEGACY_ADMIN);
        }
        List<ClubRoleAssignment> rows = clubRoleAssignmentMapper.selectActiveForAccess(
                clubId, principalMemberId, activityId, now);
        if (rows != null) for (ClubRoleAssignment row : rows) {
            String roleCode = validateAssignment(row, clubId, principalMemberId, activityId, now);
            if (roleCode != null) roles.add(roleCode);
        }
        List<ClubEventAccess> eventAccesses = includeEventAccesses
                ? resolveEventAccesses(clubId, principalMemberId, now)
                : Collections.<ClubEventAccess>emptyList();
        return new ClubAccessContext(principalMemberId, clubId, club.getMemberId(), activityId,
                roles, eventAccesses);
    }

    private List<ClubEventAccess> resolveEventAccesses(Long clubId, Long memberId, Date now) {
        List<ClubRoleAssignment> rows = clubRoleAssignmentMapper.selectActiveEventAccesses(
                clubId, memberId, now);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Map<Long, Set<String>> rolesByActivity = new LinkedHashMap<>();
        Map<Long, Long> topicByActivity = new LinkedHashMap<>();
        for (ClubRoleAssignment row : rows) {
            Long activityId = validateEventAccessAssignment(row, clubId, memberId, now);
            if (activityId == null) continue;
            Long topicId = row.getScopeTopicId();
            if (!positive(topicId)) throw forbidden();
            Long mappedTopicId = topicByActivity.get(activityId);
            if (mappedTopicId != null && !mappedTopicId.equals(topicId)) throw forbidden();
            topicByActivity.put(activityId, topicId);
            Set<String> roles = rolesByActivity.get(activityId);
            if (roles == null) {
                roles = new LinkedHashSet<>();
                rolesByActivity.put(activityId, roles);
            }
            roles.add(row.getRoleCode());
        }
        List<ClubEventAccess> accesses = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : rolesByActivity.entrySet()) {
            accesses.add(new ClubEventAccess(
                    entry.getKey(), topicByActivity.get(entry.getKey()), entry.getValue()));
        }
        return accesses;
    }

    private Long validateEventAccessAssignment(ClubRoleAssignment row, Long clubId,
                                               Long memberId, Date now) {
        if (row == null || !clubId.equals(row.getClubId()) || !memberId.equals(row.getTargetMemberId())
                || !ClubRoleAssignment.STATUS_ACTIVE.equals(row.getStatus())
                || !ClubRoleAssignment.SCOPE_EVENT.equals(row.getScopeType())
                || !positive(row.getScopeId()) || !ClubRoleCode.isEventRole(row.getRoleCode())) {
            throw forbidden();
        }
        if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) return null;
        return row.getScopeId();
    }

    private String validateAssignment(ClubRoleAssignment row, Long clubId, Long memberId,
                                      Long activityId, Date now) {
        if (row == null || !clubId.equals(row.getClubId()) || !memberId.equals(row.getTargetMemberId())
                || !ClubRoleAssignment.STATUS_ACTIVE.equals(row.getStatus())) throw forbidden();
        if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) return null;
        if (ClubRoleCode.isClubRole(row.getRoleCode())) {
            if (!ClubRoleAssignment.SCOPE_CLUB.equals(row.getScopeType())
                    || !clubId.equals(row.getScopeId())) throw forbidden();
            return row.getRoleCode();
        }
        if (ClubRoleCode.isEventRole(row.getRoleCode())) {
            if (activityId == null || !ClubRoleAssignment.SCOPE_EVENT.equals(row.getScopeType())
                    || !activityId.equals(row.getScopeId())) throw forbidden();
            return row.getRoleCode();
        }
        throw forbidden();
    }

    private void requireOwnedActivity(Long activityId, Long clubId) {
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(activityId);
        if (activity == null || !activityId.equals(activity.getId()) || !clubId.equals(activity.getClubId())
                || !Integer.valueOf(0).equals(activity.getDelFlag())) throw forbidden();
    }

    private boolean isActiveClub(Club club, Long clubId) {
        return club != null && clubId.equals(club.getId()) && club.getMemberId() != null
                && Long.valueOf(1L).equals(club.getStatus()) && Integer.valueOf(0).equals(club.getDelFlag());
    }

    private boolean isActiveMembership(ClubMember membership, Long clubId, Long memberId) {
        return membership != null && membership.getId() != null && clubId.equals(membership.getClubId())
                && memberId.equals(membership.getMemberId()) && Integer.valueOf(1).equals(membership.getStatus())
                && Integer.valueOf(0).equals(membership.getDelFlag());
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private ServiceException forbidden() {
        return new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN);
    }
}
