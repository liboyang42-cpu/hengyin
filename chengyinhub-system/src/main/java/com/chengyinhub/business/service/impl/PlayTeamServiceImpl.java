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
import com.chengyinhub.business.service.IPlayerWxacodeService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PlayTeamServiceImpl.class);
    private static final SecureRandom INVITE_RANDOM = new SecureRandom();
    static final String QR_READY = "READY";
    static final String QR_UNAVAILABLE = "UNAVAILABLE";
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
    @Autowired
    private IPlayerWxacodeService playerWxacodeService;

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
        team.setTitle(title);
        team.setJoinMode(mode);
        team.setMaxMembers(cap);
        team.setJoinedCount(1);
        team.setStatus(PlayTeam.STATUS_RECRUITING);
        team.setExpireTime(expireTime);
        insertWithUniqueInvite(team);

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
        String normalizedCode = normalizeInviteCode(inviteCode);
        PlayTeam team = normalizedCode == null ? requireTeam(teamId, false)
                : teamMapper.selectPlayTeamByInviteCode(normalizedCode);
        if (team == null) throw new IllegalArgumentException("邀请已失效");
        if (normalizedCode != null && !inviteAvailable(team)) throw new IllegalArgumentException("邀请已失效");
        teamId = team.getId();
        PlayTeamMember activeMembership = viewerMemberId != null && viewerMemberId > 0
                ? teamMapper.selectActiveMember(teamId, viewerMemberId) : null;
        boolean joined = activeMembership != null;
        if (normalizedCode == null && !joined) {
            throw new IllegalArgumentException("无权查看该队伍");
        }
        List<PlayTeamMember> members = teamMapper.selectActiveMembers(teamId);
        Map<String, Object> result = new HashMap<>();
        // j4 F10:非成员凭邀请码只看邀请页要用的:标题、人数、状态、加入方式与成员昵称头像。
        // 不回邀请码、队长 / 成员 id、场次归属与创建时间。队伍 id 留着:入队命令按它冻结目标(邀请换队即拒)。
        result.put("team", joined ? team : invitePreview(team));
        List<Map<String, Object>> visibleMembers = new ArrayList<>();
        if (members != null) {
            for (PlayTeamMember member : members) {
                if (member == null) continue;
                Map<String, Object> visible = new LinkedHashMap<>();
                if (joined) visible.put("memberId", member.getMemberId());
                visible.put("role", member.getRole());
                visible.put("memberName", member.getMemberName());
                visible.put("memberAvatar", member.getMemberAvatar());
                visibleMembers.add(visible);
            }
        }
        result.put("members", visibleMembers);
        result.put("joined", joined);
        result.put("leader", joined && viewerMemberId.equals(team.getLeaderMemberId()));
        // U06-01:已购席位、待审申请、已领取与仍可领取的名额,加邀请期限和本人关系,全部从权威对象现读。
        result.put("seats", seatProjection(team));
        result.put("invite", inviteProjection(team));
        result.put("viewer", viewerProjection(team, viewerMemberId, activeMembership));
        return result;
    }

    private static Map<String, Object> invitePreview(PlayTeam team) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("id", team.getId());
        preview.put("title", team.getTitle());
        preview.put("status", team.getStatus());
        preview.put("joinMode", team.getJoinMode());
        preview.put("joinedCount", team.getJoinedCount());
        preview.put("maxMembers", team.getMaxMembers());
        return preview;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlayTeam join(String inviteCode, Long memberId) {
        requireMember(memberId);
        String normalizedCode = normalizeInviteCode(inviteCode);
        if (normalizedCode == null) throw new IllegalArgumentException("邀请链接已升级，请让队长重新分享");
        PlayTeam observed = teamMapper.selectPlayTeamByInviteCode(normalizedCode);
        // 邀请码不可解析(撤销/换码)与队伍已过招募期必须能区分:前者要重新要链接,后者要换队。
        if (observed == null) throw new TeamRejection("INVITE_REVOKED", "邀请已失效");
        if (!inviteAvailable(observed)) throw new TeamRejection("INVITE_EXPIRED", "邀请已失效");
        Long teamId = observed.getId();
        if (memberId.equals(observed.getLeaderMemberId())) {
            throw new IllegalStateException("你已在队伍中");
        }
        if (blockMapper.existsBetween(observed.getLeaderMemberId(), memberId) > 0) {
            throw new IllegalStateException("暂时无法加入该队伍");
        }
        // 与公开申请同口径:审核中的队伍两条入口都不收人,不再让邀请码绕过举报冻结。
        if (teamMapper.countOpenReports(teamId) > 0) {
            throw new TeamRejection("TEAM_UNDER_REVIEW", "队伍审核中，暂不能加入");
        }
        // 与公开申请同口径:邀请码也换不来入场资格,只认本场次(owner_type=2)已支付报名;
        // 自玩通行证 owner_type=1 不经过场次,不能入队。
        if (teamMapper.hasPaidRegistration(memberId, 2, observed.getOwnerId()) <= 0) {
            throw new TeamRejection("TICKET_REQUIRED", "持有这一场的票才能加入");
        }
        // 名额真源只有这条条件 UPDATE；不以任何先查结果决定是否还有空位。
        if (teamMapper.reserveSlot(teamId) == 0) {
            throw new TeamRejection("TEAM_FULL", "队伍已满或已停止招募");
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
                // uk_team_member 只有一行:能复用(0/2/5/6)的已在上面复活;
                // 走到这里要么已经入队,要么是被拒绝/被移出的历史(3/4),两者不能并成一句话。
                // 第十一轮 g2(j4 F1):被移出 / 被拒的人凭旧链接再来,按钮旁要看到真话 —— 不说「你已在队伍中」。
                if (teamMapper.selectActiveMember(teamId, memberId) != null) {
                    throw new TeamRejection("ALREADY_JOINED", "你已在队伍中");
                }
                throw new TeamRejection("APPLY_REJECTED", "暂时无法加入该队伍");
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
                throw new TeamRejection("TEAM_STATE_CHANGED", "队伍状态已变化，请刷新后重试");
            }
            imService.closeTeamGroup(teamId);
            return;
        }
        if (memberId.equals(team.getLeaderMemberId())) {
            PlayTeamMember next = teamMapper.selectEarliestOtherActiveMember(teamId, memberId);
            if (next == null || teamMapper.transferLeadership(teamId, memberId, next.getMemberId()) == 0) {
                // 场上进行中(status=2)不在原 transferLeadership 窗口内,只能由现场工作人员处理整队。
                throw new TeamRejection("LEADER_HANDOVER_FAILED", "队长移交失败，请重试");
            }
            if (teamMapper.updateMemberRole(teamId, memberId, 1, 0) == 0
                    || teamMapper.updateMemberRole(teamId, next.getMemberId(), 0, 1) == 0) {
                throw new TeamRejection("LEADER_HANDOVER_FAILED", "队长移交失败，请重试");
            }
            notifyService.notifyTeamStatus(next.getMemberId(), displayTitle(team), "你已成为队长");
        }
        if (teamMapper.markMemberStatus(teamId, memberId,
                PlayTeamMember.STATUS_JOINED, PlayTeamMember.STATUS_QUIT) == 0
                || teamMapper.releaseSlot(teamId) == 0) {
            throw new TeamRejection("TEAM_STATE_CHANGED", "退队失败，请刷新后重试");
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
            throw new TeamRejection("TEAM_STATE_CHANGED", "队伍状态已变化，请刷新后重试");
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
        boolean guest = viewerMemberId == null || viewerMemberId <= 0;
        viewerMemberId = guest ? 0L : viewerMemberId;
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) throw new IllegalArgumentException("经纬度格式不正确");
        int r = radius <= 0 ? NEARBY_DEFAULT_RADIUS_M : Math.min(radius, NEARBY_MAX_RADIUS_M);
        double[] box = RoamHangoutServiceImpl.bbox(lat, lng, r);
        List<PlayTeamNearbyVO> rows = cmsTopicMapper.selectNearbyPublicTeams(viewerMemberId, lat, lng, box[0], box[1], box[2], box[3], r,
                new Date(), NEARBY_LIMIT);
        if (guest) for (PlayTeamNearbyVO row : rows) {
            row.setViewerStatus(null);
            row.setViewerHasTicket(null);
            row.setApplyExpireTime(null);
            row.setPendingCount(null);
        }
        return rows;
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
                throw new TeamRejection("APPLY_STATE_CHANGED", "申请状态已变化，请刷新后重试");
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

    /**
     * 席位投影。名额唯一真源仍是 play_team.joined_count(reserveSlot/releaseSlot 的条件 UPDATE);
     * 这里只读换算,不建第二套席位表,也不写订单/权益/退款:
     * capacity=上限、occupied=已领取席位、available=仍可领取的名额、
     * paidOccupied=已入队且仍持本场次有效票、paidTicketMissing=已入队但票已失效、
     * pendingApplications=未过期待审申请(申请不占席,与名额无关)。
     */
    private Map<String, Object> seatProjection(PlayTeam team) {
        int capacity = team.getMaxMembers() == null ? 0 : team.getMaxMembers();
        int occupied = team.getJoinedCount() == null ? 0 : Math.max(0, team.getJoinedCount());
        int paidOccupied = Math.max(0, teamMapper.countPaidActiveMembers(team.getId()));
        Map<String, Object> seats = new LinkedHashMap<>();
        seats.put("capacity", capacity);
        seats.put("occupied", occupied);
        // 与 reserveSlot 的容量条件同口径:名额用完即无可领取席位。
        seats.put("available", Math.max(0, capacity - Math.max(occupied, paidOccupied)));
        seats.put("paidOccupied", paidOccupied);
        seats.put("paidTicketMissing", Math.max(0, occupied - paidOccupied));
        seats.put("pendingApplications",
                Math.max(0, teamMapper.countOpenApplications(team.getId(), new Date())));
        return seats;
    }

    /**
     * 邀请投影。available 直接复用 join 现用的 inviteAvailable,expiresAt 只回放原 expire_time,
     * 不另设到期语义,也不按枚举大小猜队伍状态。
     */
    private Map<String, Object> inviteProjection(PlayTeam team) {
        boolean available = inviteAvailable(team);
        Map<String, Object> invite = new LinkedHashMap<>();
        invite.put("mode", team.getJoinMode() != null && team.getJoinMode() == 2 ? "PUBLIC" : "INVITE_ONLY");
        invite.put("available", available);
        invite.put("expiresAt", team.getExpireTime());
        invite.put("expired", !available);
        return invite;
    }

    /**
     * 本人关系投影:成员关系、队长职务与剧情角色分开表达。
     * canApply / canJoinWithCode 复用 apply/join 的只读前置判断(票、审核、拉黑、容量、重复申请),
     * 只用于提示下一步;写路径条件仍是唯一权威,末席并发由 reserveSlot 决定。
     */
    private Map<String, Object> viewerProjection(PlayTeam team, Long viewerMemberId, PlayTeamMember activeMembership) {
        boolean signedIn = viewerMemberId != null && viewerMemberId > 0;
        PlayTeamMember row = activeMembership;
        if (signedIn && row == null) row = teamMapper.selectMemberRow(team.getId(), viewerMemberId);
        boolean joined = activeMembership != null;
        Date now = new Date();
        Map<String, Object> viewer = new LinkedHashMap<>();
        viewer.put("joined", joined);
        viewer.put("leader", joined && viewerMemberId.equals(team.getLeaderMemberId()));
        viewer.put("role", joined ? activeMembership.getRole() : null);
        viewer.put("applicationStatus", applicationStatus(row, now));
        viewer.put("applyExpireTime", row == null ? null : row.getApplyExpireTime());
        Map<String, List<String>> blockers = admissionBlockers(team, viewerMemberId, row, now);
        viewer.put("canApply", blockers.get("apply").isEmpty());
        viewer.put("canJoinWithCode", blockers.get("join").isEmpty());
        viewer.put("applyBlockers", blockers.get("apply"));
        viewer.put("joinBlockers", blockers.get("join"));
        return viewer;
    }

    /**
     * apply/join 前置条件的只读镜像,一次算完两路:
     * 共享 = 容量 + 审核 + 拉黑 + 本场票 + 被拒绝/被移出的历史;
     * apply = 共享 + 公开申请制 + 未处于待审;
     * join  = 共享 + 邀请可用。
     * 阻塞码与写路径的 TeamRejection.errorCode 同名;空列表只表示「当前看起来能过」。
     * 「被拒绝/被移出不能再回来」是 join 与 apply 的共同事实:两条入口都靠
     * play_team_member 唯一行 + reactivateMember(status in 0,2,5,6) 挡住 3/4。
     */
    private Map<String, List<String>> admissionBlockers(PlayTeam team, Long viewerMemberId,
                                                        PlayTeamMember row, Date now) {
        List<String> shared = new ArrayList<>();
        int status = row == null || row.getStatus() == null ? -1 : row.getStatus();
        if (viewerMemberId == null || viewerMemberId <= 0) {
            shared.add("NOT_SIGNED_IN");
        } else if (status == PlayTeamMember.STATUS_JOINED
                || viewerMemberId.equals(team.getLeaderMemberId())) {
            shared.add("ALREADY_JOINED");
        } else if (status == PlayTeamMember.STATUS_REJECTED || status == PlayTeamMember.STATUS_KICKED) {
            shared.add("APPLY_REJECTED");
        } else {
            int capacity = team.getMaxMembers() == null ? 0 : team.getMaxMembers();
            int occupied = team.getJoinedCount() == null ? 0 : team.getJoinedCount();
            // reserveSlot 要求 status=0 且 joined_count<max_members,两者不满足都收不进人。
            if (team.getStatus() == null || team.getStatus() != PlayTeam.STATUS_RECRUITING
                    || occupied >= capacity) {
                shared.add("TEAM_FULL");
            }
            if (teamMapper.countOpenReports(team.getId()) > 0) shared.add("TEAM_UNDER_REVIEW");
            if (blockMapper.existsBetween(team.getLeaderMemberId(), viewerMemberId) > 0) {
                shared.add("APPLY_BLOCKED");
            }
            if (teamMapper.hasPaidRegistration(viewerMemberId, 2, team.getOwnerId()) <= 0) {
                shared.add("TICKET_REQUIRED");
            }
        }
        List<String> apply = new ArrayList<>(shared);
        List<String> join = new ArrayList<>(shared);
        if (shared.isEmpty()) {
            if (team.getJoinMode() == null || team.getJoinMode() != 2) apply.add("TEAM_NOT_PUBLIC");
            if (status == PlayTeamMember.STATUS_PENDING
                    && row.getApplyExpireTime() != null && row.getApplyExpireTime().after(now)) {
                apply.add("APPLY_PENDING");
            }
            if (!inviteAvailable(team)) join.add("INVITE_UNAVAILABLE");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("apply", apply);
        result.put("join", join);
        return result;
    }

    private String applicationStatus(PlayTeamMember row, Date now) {
        if (row == null || row.getStatus() == null) return null;
        switch (row.getStatus()) {
            case PlayTeamMember.STATUS_JOINED: return "JOINED";
            case PlayTeamMember.STATUS_PENDING:
                return row.getApplyExpireTime() != null && !row.getApplyExpireTime().after(now)
                        ? "EXPIRED" : "PENDING";
            case PlayTeamMember.STATUS_QUIT: return "QUIT";
            case PlayTeamMember.STATUS_KICKED: return "KICKED";
            case PlayTeamMember.STATUS_REJECTED: return "REJECTED";
            case PlayTeamMember.STATUS_WITHDRAWN: return "WITHDRAWN";
            case PlayTeamMember.STATUS_EXPIRED: return "EXPIRED";
            default: return null;
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

    @Override
    public Map<String, Object> inviteQrcode(Long teamId, Long memberId) {
        requireMember(memberId);
        PlayTeam team = requireTeam(teamId, false);
        // 与 info 同一口径:只有本队在队成员看得到邀请码;邀请已过招募窗口不再出码。
        if (teamMapper.selectActiveMember(team.getId(), memberId) == null) {
            throw new IllegalArgumentException("无权查看该队伍");
        }
        String inviteCode = trimToNull(team.getInviteCode());
        if (inviteCode == null || !inviteAvailable(team)) throw new IllegalArgumentException("邀请已失效");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teamId", team.getId());
        result.put("inviteCode", inviteCode);
        String scene = teamScene(inviteCode);
        String url = null;
        if (scene != null) {
            try {
                url = playerWxacodeService.getOrCreateScene(scene);
            } catch (RuntimeException e) {
                // 微信接口 / 频控 / 存储不可用:降级为只给加入码。不回传内部原因。
                log.warn("队伍小程序码不可用,降级为加入码,teamId={},exceptionType={}",
                        team.getId(), e.getClass().getSimpleName());
                url = null;
            }
        }
        boolean ready = url != null && !url.trim().isEmpty();
        result.put("qrState", ready ? QR_READY : QR_UNAVAILABLE);
        if (ready) result.put("qrcodeUrl", url);
        return result;
    }

    /**
     * 新的六位加入码使用 tm 前缀。继续识别旧的 22 位和压缩十六进制 scene，直到原邀请到期。
     */
    static String teamScene(String inviteCode) {
        if (inviteCode == null) return null;
        if (inviteCode.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}")) return "tm." + inviteCode;
        if (inviteCode.matches("[A-Za-z0-9_-]{22}")) return "tm." + inviteCode;
        if (inviteCode.matches("[0-9a-f]{32}")) {
            byte[] bytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                bytes[i] = (byte) Integer.parseInt(inviteCode.substring(i * 2, i * 2 + 2), 16);
            }
            return "th." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return null;
    }

    private boolean inviteAvailable(PlayTeam team) {
        return team != null && team.getStatus() != null && team.getStatus() >= PlayTeam.STATUS_RECRUITING
                && team.getStatus() <= PlayTeam.STATUS_LOCKED;
    }

    private String normalizeInviteCode(String value) {
        String code = trimToNull(value);
        return code != null && code.length() == 6 ? code.toUpperCase(java.util.Locale.ROOT) : code;
    }

    private void insertWithUniqueInvite(PlayTeam team) {
        for (int attempt = 0; attempt < 16; attempt++) {
            team.setInviteCode(newInviteCode());
            try {
                teamMapper.insertPlayTeam(team);
                return;
            } catch (DuplicateKeyException collision) {
                if (attempt == 15) throw collision;
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String rotateInviteCode(Long teamId, Long memberId) {
        PlayTeam team = requireLeader(teamId, memberId);
        if (!inviteAvailable(team)) throw new TeamRejection("INVITE_EXPIRED", "邀请已失效");
        for (int attempt = 0; attempt < 16; attempt++) {
            String code = newInviteCode();
            if (code.equals(team.getInviteCode())) continue;
            try {
                if (teamMapper.rotateInviteCode(teamId, memberId, code) != 1) {
                    throw new IllegalStateException("邀请码更新失败，请刷新后重试");
                }
                return code;
            } catch (DuplicateKeyException collision) {
                if (attempt == 15) throw collision;
            }
        }
        throw new IllegalStateException("邀请码更新失败，请重试");
    }

    String newInviteCode() {
        String alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
        char[] code = new char[6];
        for (int i = 0; i < code.length; i++) code[i] = alphabet.charAt(INVITE_RANDOM.nextInt(alphabet.length()));
        return new String(code);
    }
}
