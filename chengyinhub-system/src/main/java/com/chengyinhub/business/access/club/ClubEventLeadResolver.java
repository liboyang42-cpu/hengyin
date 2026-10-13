package com.chengyinhub.business.access.club;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.service.IClubService;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 本场领队是谁 —— 全仓唯一一份口径。
 *
 * <p>优先当前场次未过期的 ACTIVE {@code EVENT_LEAD} 委派;没有委派才回落俱乐部 owner,
 * owner 也缺失时回落活动创建者。此前这段逻辑只活在 ApiClubLeadController 的私有方法里,
 * 别处要判领队就得抄一份 —— 抄出来的那份不会跟着改,两边迟早分叉。
 */
@Component
public class ClubEventLeadResolver {

    private final ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    private final IClubService clubService;

    public ClubEventLeadResolver(ClubRoleAssignmentMapper clubRoleAssignmentMapper,
                                 IClubService clubService) {
        this.clubRoleAssignmentMapper = clubRoleAssignmentMapper;
        this.clubService = clubService;
    }

    /** @return 本场领队 memberId;活动不属于俱乐部局或无从判定时返回 null。 */
    public Long resolveLeaderMemberId(CmsActivity activity) {
        if (activity == null || activity.getClubId() == null || activity.getId() == null) return null;
        List<ClubRoleAssignment> assignments = clubRoleAssignmentMapper.selectActiveByClubAndScope(
                activity.getClubId(), activity.getId(), new Date());
        if (assignments != null) {
            for (ClubRoleAssignment assignment : assignments) {
                if (assignment != null && ClubRoleCode.EVENT_LEAD.equals(assignment.getRoleCode())
                        && assignment.getTargetMemberId() != null) {
                    return assignment.getTargetMemberId();
                }
            }
        }
        Club club = clubService.selectClubById(activity.getClubId());
        return club != null && club.getMemberId() != null ? club.getMemberId() : activity.getMemberId();
    }
}
