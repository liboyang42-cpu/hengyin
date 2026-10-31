package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.PlayTeamNearbyVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlayTeamServiceImpl implements IPlayTeamService {

    private static final SecureRandom INVITE_RANDOM = new SecureRandom();
    static final long APPLY_TTL_MILLIS = 24L * 60 * 60 * 1000;
    static final int NEARBY_DEFAULT_RADIUS_M = 3000;
    /** 走查 P-38(业主):附近地图「全部」= 所有距离;结果仍按距离由近到远截断条数,半径上限只防坐标溢出(NEARBY_LIMIT)。 */
    static final int NEARBY_MAX_RADIUS_M = 1_000_000;
    static final int NEARBY_LIMIT = 50;

    @Autowired
    private PlayTeamMapper teamMapper;
    @Autowired
    private ImBlockMapper blockMapper;
    @Autowired
    private CmsActivityMapper cmsActivityMapper;
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private IImService imService;
    @Autowired
    private INotifyService notifyService;
    @Autowired
    private IWxContentSecurityService wxContentSecurityService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private IAuditTaskService auditTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlayTeam create(Long memberId, Integer ownerType, Long ownerId, String title, Integer maxMembers,
                           Integer joinMode) {
        requireMember(memberId);
        // P 方案:地图组队以公开队伍为主,不传 joinMode 默认公开;队长可随时切回仅邀请。
        int mode = joinMode == null ? 2 : joinMode;
        if (mode != 1 && mode != 2) throw new IllegalArgumentException("组队方式有误");
        title = trimToNull(title);
        if (title != null && title.length() > 60) {
            throw new IllegalArgumentException("队伍名称最多 60 个字");
        }
        if (title != null) {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            String openid = member == null ? null : member.getWxOpenId();
            String securityError = wxContentSecurityService.checkText(title, openid);
            if (securityError != null) throw new IllegalArgumentException(securityError);
        }
        if (ownerType == null || ownerType != 2) {
            throw new IllegalArgumentException("组队仅支持活动场次");
        }
        if (ownerId == null || ownerId <= 0) throw new IllegalArgumentException("场次参数有误");
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
        if (activity == null) throw new IllegalArgumentException("场次不存在");
        CmsTopic topic = activity.getTopicId() == null ? null
                : cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
        if (topic == null || topic.getTeamMode() == null || topic.getTeamMode() == 0) {
            throw new IllegalStateException("该主题未开放组队");
        }
        Date expireTime = activity.getStartDate();
        if (expireTime == null) {
            throw new IllegalStateException("该活动未设置开始时间，暂不能建队");
        }
        if (teamMapper.hasPaidRegistration(memberId, ownerType, ownerId) <= 0) {
            throw new IllegalStateException("报名成功后才能建队");
        }
        int configuredMax = topic.getTeamMaxMembers() == null ? 4 : topic.getTeamMaxMembers();
        configuredMax = Math.max(2, Math.min(4, configuredMax));
        int cap = maxMembers == null ? configuredMax : maxMembers;
        if (cap < 2 || cap > configuredMax) {
            throw new IllegalArgumentException("队伍人数须为 2–" + configuredMax + " 人");
        }

        PlayTeam team = new PlayTeam();
        team.setOwnerType(ownerType);
        team.setOwnerId(ownerId);
        team.setLeaderMemberId(memberId);
        team.setInviteCode(newInviteCode());
        team.setTitle(title);
        team.setJoinMode(mode);
        team.setMaxMembers(cap);
        team.setJoinedCount(1);
        team.setStatus(PlayTeam.STATUS_RECRUITING);
        team.setExpireTime(expireTime);
        teamMapper.insertPlayTeam(team);

        PlayTeamMember leader = new PlayTeamMember();
        leader.setTeamId(team.getId());
        leader.setMemberId(memberId);
        leader.setRole(1);
        leader.setStatus(PlayTeamMember.STATUS_JOINED);
        teamMapper.insertPlayTeamMember(leader);
        imService.getOrCreateTeamGroup(team.getId(), memberId);
        return team;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateJoinMode(Long teamId, Long leaderMemberId, Integer joinMode) {
        if (joinMode == null || (joinMode != 1 && joinMode != 2)) throw new IllegalArgumentException("组队方式有误");
        PlayTeam team = requireLeader(teamId, leaderMemberId);
        if (team.getJoinMode() != null && team.getJoinMode().intValue() == joinMode.intValue()) return joinMode;
        if (teamMapper.updateJoinMode(team.getId(), leaderMemberId, joinMode) == 0) {
            throw new IllegalStateException("组队方式更新失败，请刷新后重试");
        }
        return joinMode;
    }

    @Override
    public Map<String, Object> info(Long teamId, String inviteCode, Long viewerMemberId) {
        String normalizedCode = trimToNull(inviteCode);
        PlayTeam team = normalizedCode == null ? requireTeam(teamId, false)
                : teamMapper.selectPlayTeamByInviteCode(normalizedCode);
        if (team == null) throw new IllegalArgumentException("邀请已失效");
        if (normalizedCode != null && !inviteAvailable(team)) throw new IllegalArgumentException("邀请已失效");
        teamId = team.getId();
        boolean joined = viewerMemberId != null && viewerMemberId > 0
                && teamMapper.selectActiveMember(teamId, viewerMemberId) != null;
        if (normalizedCode == null && !joined) {
            throw new IllegalArgumentException("无权查看该队伍");
        }
        List<PlayTeamMember> members = teamMapper.selectActiveMembers(teamId);
        Map<String, Object> result = new HashMap<>();
        result.put("team", team);
        List<Map<String, Object>> visibleMembers = new ArrayList<>();
        if (members != null) {
            for (PlayTeamMember member : members) {
                if (member == null) continue;
                Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("memberId", member.getMemberId());
                visible.put("role", member.getRole());
                visible.put("memberName", member.getMemberName());
                visible.put("memberAvatar", member.getMemberAvatar());
                visibleMembers.add(visible);
            }
        }
        result.put("members", visibleMembers);
        result.put("joined", joined);
        result.put("leader", joined && viewerMemberId.equals(team.getLeaderMemberId()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlayTeam join(String inviteCode, Long memberId) {
        requireMember(memberId);
        String normalizedCode = trimToNull(inviteCode);
        if (normalizedCode == null) throw new IllegalArgumentException("邀请链接已升级，请让队长重新分享");
        PlayTeam observed = teamMapper.selectPlayTeamByInviteCode(normalizedCode);
        if (observed == null || !inviteAvailable(observed)) throw new IllegalArgumentException("邀请已失效");
        Long teamId = observed.getId();
        if (memberId.equals(observed.getLeaderMemberId())) {
            throw new IllegalStateException("你已在队伍中");
        }
        if (blockMapper.existsBetween(observed.getLeaderMemberId(), memberId) > 0) {
            throw new IllegalStateException("暂时无法加入该队伍");
        }
        // 与公开申请同口径:邀请码也换不来入场资格,只认本场次(owner_type=2)已支付报名;
        // 自玩通行证 owner_type=1 不经过场次,不能入队。
        if (teamMapper.hasPaidRegistration(memberId, 2, observed.getOwnerId()) <= 0) {
            throw new TeamRejection("TICKET_REQUIRED", "持有这一场的票才能加入");
        }
        // 名额真源只有这条条件 UPDATE；不以任何先查结果决定是否还有空位。
        if (teamMapper.reserveSlot(teamId) == 0) {
            throw new IllegalStateException("队伍已满或已停止招募");
        }
        PlayTeamMember member = new PlayTeamMember();
        member.setTeamId(teamId);
        member.setMemberId(memberId);
        member.setRole(0);
        member.setStatus(PlayTeamMember.STATUS_JOINED);
        try {
            teamMapper.insertPlayTeamMember(member);
        } catch (DuplicateKeyException duplicate) {
            if (teamMapper.reactivateMember(teamId, memberId) == 0) {
                throw new IllegalStateException("你已在队伍中或不能重复加入");
            }
        }
        expirePendingIfFull(teamId);
        imService.getOrCreateTeamGroup(teamId, memberId);
        return teamMapper.selectPlayTeamById(teamId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long teamId, Long leaderMemberId, Long targetMemberId, boolean approved) {
        PlayTeam team = requireLeader(teamId, leaderMemberId);
        if (!approved) {
            if (teamMapper.rejectPending(teamId, targetMemberId, new Date()) == 0) {
                throw new TeamRejection("APPLY_NOT_PENDING", "申请已处理或已失效");
            }
            notifyService.notifyTeamStatus(targetMemberId, displayTitle(team), "入队申请未通过");
            return;
        }
        // 申请后 24h 内可能已退票或被队长拉黑:同意时重验,与 apply 同口径。
        if (targetMemberId == null || blockMapper.existsBetween(leaderMemberId, targetMemberId) > 0) {
            throw new TeamRejection("APPLY_BLOCKED", "暂时无法加入该队伍");
        }
        if (teamMapper.hasPaidRegistration(targetMemberId, 2, team.getOwnerId()) <= 0) {
            throw new TeamRejection("TICKET_REQUIRED", "对方已不再持有这一场的票");
        }
        // requireLeader 已对队伍行加 FOR UPDATE:并发同意在此串行,名额仍以 reserveSlot 条件更新为准。
        if (teamMapper.reserveSlot(teamId) == 0) {
            throw new TeamRejection("TEAM_FULL", "队伍已满或已停止招募");
        }
        if (teamMapper.approvePending(teamId, targetMemberId, new Date()) == 0) {
            // 抛出即回滚上面的扣位。
            throw new TeamRejection("APPLY_NOT_PENDING", "申请已处理或已失效");
        }
        expirePendingIfFull(teamId);
        imService.getOrCreateTeamGroup(teamId, targetMemberId);
        notifyService.notifyTeamStatus(targetMemberId, displayTitle(team), "入队申请已通过");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void quit(Long teamId, Long memberId) {
        requireMember(memberId);
        PlayTeam team = requireTeam(teamId, true);
        PlayTeamMember membership = teamMapper.selectActiveMember(teamId, memberId);
        if (membership == null) throw new IllegalStateException("你不在该队伍中");
        int activeCount = teamMapper.countActiveMembers(teamId);
        if (activeCount <= 1) {
            if (teamMapper.markMemberStatus(teamId, memberId,
                    PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_QUIT) == 0
                    || teamMapper.disbandTeam(teamId, team.getLeaderMemberId()) == 0) {
                throw new IllegalStateException("队伍状态已变化，请刷新后重试");
            }
            imService.closeTeamGroup(teamId);
            return;
        }
        if (memberId.equals(team.getLeaderMemberId())) {
            PlayTeamMember next = teamMapper.selectEarliestOtherActiveMember(teamId, memberId);
            if (next == null || teamMapper.transferLeadership(teamId, memberId, next.getMemberId()) == 0) {
                throw new IllegalStateException("队长移交失败，请重试");
            }
            if (teamMapper.updateMemberRole(teamId, memberId, 1, 0) == 0
                    || teamMapper.updateMemberRole(teamId, next.getMemberId(), 0, 1) == 0) {
                throw new IllegalStateException("队长移交失败，请重试");
            }
            notifyService.notifyTeamStatus(next.getMemberId(), displayTitle(team), "你已成为队长");
        }
        if (teamMapper.markMemberStatus(teamId, memberId,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_QUIT) == 0
                || teamMapper.releaseSlot(teamId) == 0) {
            throw new IllegalStateException("退队失败，请刷新后重试");
        }
        imService.leaveTeamGroup(teamId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void kick(Long teamId, Long leaderMemberId, Long targetMemberId) {
        PlayTeam team = requireLeader(teamId, leaderMemberId);
        if (leaderMemberId.equals(targetMemberId)) throw new IllegalArgumentException("队长不能移出自己");
        if (teamMapper.markMemberStatus(teamId, targetMemberId,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_KICKED) == 0) {
            throw new IllegalStateException("成员不在队伍中");
        }
        if (teamMapper.releaseSlot(teamId) == 0) {
            throw new IllegalStateException("队伍状态已变化，请刷新后重试");
        }
        imService.leaveTeamGroup(teamId, targetMemberId);
        notifyService.notifyTeamStatus(targetMemberId, displayTitle(team), "你已被移出队伍");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disband(Long teamId, Long leaderMemberId) {
        PlayTeam team = requireLeader(teamId, leaderMemberId);
        List<PlayTeamMember> members = teamMapper.selectActiveMembers(teamId);
        if (teamMapper.disbandTeam(teamId, leaderMemberId) == 0) {
            throw new IllegalStateException("队伍已结束或已解散");
        }
        teamMapper.markActiveMembersExited(teamId);
        teamMapper.expirePendingApplications(teamId);
        imService.closeTeamGroup(teamId);
        if (members != null) {
            for (PlayTeamMember member : members) {
                if (member != null && member.getMemberId() != null
                        && !leaderMemberId.equals(member.getMemberId())) {
                    notifyService.notifyTeamStatus(member.getMemberId(), displayTitle(team), "队伍已解散");
                }
            }
        }
    }

    @Override
    public List<PlayTeam> my(Long memberId) {
        requireMember(memberId);
        return teamMapper.selectMyTeams(memberId);
    }

    @Override
    public List<PlayTeamNearbyVO> nearby(Long viewerMemberId, double lat, double lng, int radius) {
        requireMember(viewerMemberId);
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) throw new IllegalArgumentException("经纬度格式不正确");
        int r = radius <= 0 ? NEARBY_DEFAULT_RADIUS_M : Math.min(radius, NEARBY_MAX_RADIUS_M);
        double[] box = RoamHangoutServiceImpl.bbox(lat, lng, r);
        return cmsTopicMapper.selectNearbyPublicTeams(viewerMemberId, lat, lng, box[0], box[1], box[2], box[3], r,
                new Date(), NEARBY_LIMIT);
    }

    @Override
    public List<Long> selectExpiredTeamIds(Date now, int limit) {
        return teamMapper.selectExpiredTeamIds(now == null ? new Date() : now, Math.max(1, Math.min(limit, 500)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int endExpiredTeam(Long teamId, Date now) {
        int changed = teamMapper.endExpiredTeam(teamId, now == null ? new Date() : now);
        if (changed > 0) {
            teamMapper.expirePendingApplications(teamId);
            imService.closeTeamGroup(teamId);
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Date apply(Long teamId, Long memberId, String message) {
        requireMember(memberId);
        message = trimToNull(message);
        if (message != null && message.length() > 200) throw new IllegalArgumentException("申请留言最多 200 个字");
        // 锁队伍行:与队长同意/满员判定串行,避免「刚满员又插进一条待审」。
        PlayTeam team = requireTeam(teamId, true);
        teamId = team.getId();
        // 无权限可见的队伍(非公开且非队内成员)与「不存在」同口径,错误码不得用来探测私密队伍。
        boolean visible = (team.getJoinMode() != null && team.getJoinMode() == 2)
                || teamMapper.selectActiveMember(team.getId(), memberId) != null;
        if (!visible) throw new IllegalArgumentException("队伍不存在");
        if (team.getJoinMode() == null || team.getJoinMode() != 2) {
            throw new TeamRejection("TEAM_NOT_PUBLIC", "该队伍仅限邀请加入");
        }
        if (memberId.equals(team.getLeaderMemberId())) throw new TeamRejection("ALREADY_JOINED", "你已在队伍中");
        if (team.getStatus() == null || team.getStatus() != PlayTeam.STATUS_RECRUITING
                || team.getJoinedCount() == null || team.getMaxMembers() == null
                || team.getJoinedCount() >= team.getMaxMembers()) {
            throw new TeamRejection("TEAM_FULL", "队伍已满或已停止招募");
        }
        Date now = new Date();
        CmsActivity activity = team.getOwnerType() != null && team.getOwnerType() == 2
                ? cmsActivityMapper.selectCmsActivityById(team.getOwnerId()) : null;
        Date deadline = activity == null ? null : activity.getStartDate();
        if (deadline != null && !deadline.after(now)) deadline = activity.getEndDate();
        if (deadline == null || !deadline.after(now)) deadline = new Date(now.getTime() + APPLY_TTL_MILLIS);
        if (teamMapper.countOpenReports(teamId) > 0) {
            throw new TeamRejection("TEAM_UNDER_REVIEW", "队伍审核中，暂不能申请");
        }
        if (blockMapper.existsBetween(team.getLeaderMemberId(), memberId) > 0) {
            throw new TeamRejection("APPLY_BLOCKED", "暂时无法加入该队伍");
        }
        // 只认本场次(owner_type=2)已支付报名;自玩通行证 owner_type=1 不经过场次,不能入队。
        if (teamMapper.hasPaidRegistration(memberId, 2, team.getOwnerId()) <= 0) {
            throw new TeamRejection("TICKET_REQUIRED", "持有这一场的票才能申请加入");
        }
        if (message != null) {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            String securityError = wxContentSecurityService.checkText(message, member == null ? null : member.getWxOpenId());
            if (securityError != null) throw new IllegalArgumentException(securityError);
        }
        Date expireAt = new Date(Math.min(now.getTime() + APPLY_TTL_MILLIS, deadline.getTime()));
        PlayTeamMember existing = teamMapper.selectMemberRow(teamId, memberId);
        if (existing == null) {
            PlayTeamMember application = new PlayTeamMember();
            application.setTeamId(teamId);
            application.setMemberId(memberId);
            application.setRole(0);
            application.setStatus(PlayTeamMember.STATUS_PENDING);
            application.setApplyMessage(message);
            application.setApplyExpireTime(expireAt);
            teamMapper.insertPlayTeamMember(application);
        } else {
            int status = existing.getStatus() == null ? -1 : existing.getStatus();
            if (status == PlayTeamMember.STATUS_JOINED) throw new TeamRejection("ALREADY_JOINED", "你已在队伍中");
            // 规则 3:被队长拒绝 / 移出后不能再申请同一支队伍(服务端挡,uk_team_member 保证只有这一行)。
            if (status == PlayTeamMember.STATUS_REJECTED || status == PlayTeamMember.STATUS_KICKED) {
                throw new TeamRejection("APPLY_REJECTED", "队长未同意，不能再次申请这支队伍");
            }
            if (status == PlayTeamMember.STATUS_PENDING && existing.getApplyExpireTime() != null
                    && existing.getApplyExpireTime().after(now)) {
                throw new TeamRejection("APPLY_PENDING", "已申请，等待队长同意");
            }
            if (teamMapper.reapplyMember(teamId, memberId, status, message, expireAt) == 0) {
                throw new IllegalStateException("申请状态已变化，请刷新后重试");
            }
        }
        notifyService.notifyTeamStatus(team.getLeaderMemberId(), displayTitle(team), "收到新的入队申请");
        return expireAt;
    }

    @Override
    public Long report(Long teamId, Long memberId, String reason) {
        requireMember(memberId);
        PlayTeam team = requireTeam(teamId, false);
        // 只有公开队伍或本队成员看得到队伍内容;其余一律按不存在处理,不借举报接口探测私密队伍。
        boolean visible = (team.getJoinMode() != null && team.getJoinMode() == 2)
                || teamMapper.selectActiveMember(team.getId(), memberId) != null;
        if (!visible) throw new IllegalArgumentException("队伍不存在");
        reason = trimToNull(reason);
        if (reason != null && reason.length() > 200) throw new IllegalArgumentException("举报理由最多 200 个字");
        return auditTaskService.reportContent(AuditTask.BIZ_TEAM, team.getId(), memberId,
                reason == null ? "用户举报" : reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long teamId, Long memberId) {
        requireMember(memberId);
        PlayTeam team = requireTeam(teamId, true);
        if (teamMapper.markMemberStatus(team.getId(), memberId,
                PlayTeamMember.STATUS_PENDING, PlayTeamMember.STATUS_WITHDRAWN) == 0) {
            throw new TeamRejection("APPLY_NOT_PENDING", "申请已处理或已失效");
        }
    }

    @Override
    public List<Map<String, Object>> applications(Long teamId, Long leaderMemberId) {
        requireMember(leaderMemberId);
        PlayTeam team = requireTeam(teamId, false);
        if (!leaderMemberId.equals(team.getLeaderMemberId())) throw new IllegalStateException("只有队长可以操作");
        List<Map<String, Object>> result = new ArrayList<>();
        List<PlayTeamMember> rows = teamMapper.selectPendingApplications(team.getId(), new Date());
        if (rows == null) return result;
        for (PlayTeamMember row : rows) {
            if (row == null) continue;
            Map<String, Object> visible = new LinkedHashMap<>();
            visible.put("memberId", row.getMemberId());
            visible.put("memberName", row.getMemberName());
            visible.put("memberAvatar", row.getMemberAvatar());
            visible.put("applyMessage", row.getApplyMessage());
            visible.put("appliedAt", row.getUpdateTime());
            visible.put("applyExpireTime", row.getApplyExpireTime());
            result.add(visible);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> myApplications(Long memberId) {
        requireMember(memberId);
        return teamMapper.selectMyApplications(memberId, new Date());
    }

    @Override
    public int expireStaleApplications(Date now) {
        return teamMapper.expireStaleApplications(now == null ? new Date() : now);
    }

    /** 规则 1:满员(status=1)时其余待审申请在同一事务内自动失效。 */
    private void expirePendingIfFull(Long teamId) {
        PlayTeam current = teamMapper.selectPlayTeamById(teamId);
        if (current != null && current.getStatus() != null && current.getStatus() == PlayTeam.STATUS_LOCKED) {
            teamMapper.expirePendingApplications(teamId);
        }
    }

    private PlayTeam requireLeader(Long teamId, Long memberId) {
        requireMember(memberId);
        PlayTeam team = requireTeam(teamId, true);
        if (!memberId.equals(team.getLeaderMemberId())) throw new IllegalStateException("只有队长可以操作");
        return team;
    }

    private PlayTeam requireTeam(Long teamId, boolean forUpdate) {
        if (teamId == null || teamId <= 0) throw new IllegalArgumentException("队伍参数有误");
        PlayTeam team = forUpdate ? teamMapper.selectPlayTeamByIdForUpdate(teamId)
                : teamMapper.selectPlayTeamById(teamId);
        if (team == null) throw new IllegalArgumentException("队伍不存在");
        return team;
    }

    private void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new IllegalStateException("请先登录");
    }

    private String displayTitle(PlayTeam team) {
        return team.getTitle() == null || team.getTitle().trim().isEmpty() ? "玩家队伍" : team.getTitle();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean inviteAvailable(PlayTeam team) {
        return team != null && team.getStatus() != null && team.getStatus() <= PlayTeam.STATUS_LOCKED;
    }

    private String newInviteCode() {
        byte[] bytes = new byte[16];
        INVITE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
