package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务边界必须位于会抛错的领域调用之外，让 Controller 在事务已经回滚后再翻译错误回执。
 */
@Service
public class ClubLifecycleCommandServiceImpl implements IClubLifecycleCommandService {
    @Autowired private IClubService clubService;
    @Autowired private IClubMemberService clubMemberService;
    @Autowired private IClubMembershipService clubMembershipService;
    @Autowired private IClubAccessService clubAccessService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubLifecycleCommandResultVO removeMember(Long clubId, Long memberId, Long actorMemberId,
                                                      String requestId) {
        String actorRoleCode = requireCurrentMemberManager(clubId, memberId, actorMemberId);
        int rows = clubMemberService.quitMemberAndLeaveGroup(
                clubId, memberId, actorMemberId, actorRoleCode, "MEMBER_REMOVED");
        ClubLifecycleCommandResultVO result = result(rows);
        if (rows > 0) {
            result.setMembershipAccessEnd(clubMembershipService.handleMemberAccessEnded(
                    clubId, memberId, "MEMBER_REMOVED", requestId));
        }
        return result;
    }

    /**
     * 角色撤销与成员移除共用 club 行锁作为线性化点。Controller 的预检只负责尽早提示，
     * 真正授权必须在事务内、拿锁后按当前 assignment 重算，不能信任锁前观察到的角色。
     */
    private String requireCurrentMemberManager(Long clubId, Long memberId, Long actorMemberId) {
        if (!positive(clubId) || !positive(memberId) || !positive(actorMemberId)) throw forbidden();
        Club locked = clubService.selectClubByIdForUpdate(clubId);
        if (locked == null || !clubId.equals(locked.getId()) || !positive(locked.getMemberId())
                || !Long.valueOf(1L).equals(locked.getStatus())
                || !Integer.valueOf(0).equals(locked.getDelFlag())) {
            throw forbidden();
        }
        if (memberId.equals(locked.getMemberId())) {
            throw new ServiceException("不能移除俱乐部创建者", HttpStatus.BAD_REQUEST);
        }
        ClubAccessContext access = clubAccessService.resolve(actorMemberId, clubId, null);
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId())
                || !locked.getMemberId().equals(access.getOwnerMemberId())
                || !access.hasPermission(ClubPermission.MEMBER_MANAGE)) {
            throw forbidden();
        }
        String actorRoleCode = access.getPrimaryRoleCode();
        if (!ClubRoleCode.CLUB_OWNER.equals(actorRoleCode)
                && !ClubRoleCode.CLUB_CO_OWNER.equals(actorRoleCode)
                && !ClubRoleCode.CLUB_LEGACY_ADMIN.equals(actorRoleCode)) {
            throw forbidden();
        }
        if (!actorMemberId.equals(locked.getMemberId())) {
            ClubMember target = clubMemberService.selectByClubAndMember(clubId, memberId);
            if (target != null && Integer.valueOf(1).equals(target.getRole())
                    && Integer.valueOf(1).equals(target.getStatus())
                    && Integer.valueOf(0).equals(target.getDelFlag())) {
                throw new ServiceException("只有主理人可以移除管理员", HttpStatus.BAD_REQUEST);
            }
        }
        return actorRoleCode;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubLifecycleCommandResultVO quit(Long clubId, Long memberId, String requestId) {
        int rows = clubMemberService.quitMemberAndLeaveGroup(clubId, memberId);
        ClubLifecycleCommandResultVO result = result(rows);
        result.setMembershipAccessEnd(clubMembershipService.handleMemberAccessEnded(
                clubId, memberId, "MEMBER_QUIT", requestId));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubLifecycleCommandResultVO dissolve(Long clubId, Long ownerId, boolean confirmed,
                                                  boolean memberConsequencesConfirmed, String requestId) {
        int rows = clubService.dissolveClub(
                clubId, ownerId, confirmed, memberConsequencesConfirmed);
        ClubLifecycleCommandResultVO result = result(rows);
        result.setMembershipAccessEnd(clubMembershipService.handleClubAccessEnded(
                clubId, "CLUB_DISSOLVED", requestId));
        return result;
    }

    private ClubLifecycleCommandResultVO result(int affectedRows) {
        ClubLifecycleCommandResultVO result = new ClubLifecycleCommandResultVO();
        result.setAffectedRows(affectedRows);
        return result;
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    private ServiceException forbidden() {
        return new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN);
    }
}
