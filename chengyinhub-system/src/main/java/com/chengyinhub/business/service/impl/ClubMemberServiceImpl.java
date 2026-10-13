package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IImService;

/**
 * 俱乐部成员 Service 业务层处理
 */
@Service
public class ClubMemberServiceImpl implements IClubMemberService
{
    private static final Logger log = LoggerFactory.getLogger(ClubMemberServiceImpl.class);

    @Autowired
    private ClubMemberMapper clubMemberMapper;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private IClubAccessService clubAccessService;

    @Autowired
    private IClubParticipationGuard clubParticipationGuard;

    @Autowired
    private IClubRoleAssignmentService clubRoleAssignmentService;

    /**
     * 每个俱乐部的管理员上限。用户口径:「主理人可以添加 2 个管理员」= 主理人之外**额外** 2 个。
     * 主理人本就不在 club_member 里(set-member-role 拒绝给创建者设角色),故只数 role=1 天然不含他。
     */
    private static final int MAX_ADMINS_PER_CLUB = 2;

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private IImService imService;

    @Override
    public boolean topicHasOrganizer(Long topicId) {
        if (topicId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("club".equals(o.getToType())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTopicOrganizer(Long topicId, Long memberId) {
        if (topicId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if (!"club".equals(o.getToType()) || o.getToId() == null) {
                continue;
            }
            if (isClubOrganizer(o.getToId(), memberId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTopicOrganizer(Long topicId, Long clubId, Long memberId) {
        if (topicId == null || clubId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("club".equals(o.getToType()) && clubId.equals(o.getToId())) {
                return isClubOrganizer(clubId, memberId);
            }
        }
        return false;
    }

    @Override
    public boolean isTopicOwner(Long topicId, Long memberId) {
        if (topicId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("club".equals(o.getToType()) && o.getToId() != null
                    && isClubOwner(o.getToId(), memberId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTopicOwner(Long topicId, Long clubId, Long memberId) {
        if (topicId == null || clubId == null || memberId == null) {
            return false;
        }
        for (CoopOrder o : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("club".equals(o.getToType()) && clubId.equals(o.getToId())) {
                return isClubOwner(clubId, memberId);
            }
        }
        return false;
    }

    private boolean isClubOwner(Long clubId, Long memberId) {
        Club club = clubMapper.selectClubById(clubId);
        return club != null && memberId.equals(club.getMemberId());
    }

    private boolean isClubOrganizer(Long clubId, Long memberId) {
        try {
            ClubAccessContext access = clubAccessService.resolve(memberId, clubId, null);
            return access != null && access.hasPermission(ClubPermission.ACTIVITY_MANAGE);
        } catch (com.chengyinhub.common.exception.ServiceException ignored) {
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setMemberRoleWithAdminCap(Long clubId, Long memberId, int role) {
        // 与解散共用 club 行锁：无论升级还是降级，都不能在解散提交后继续写幽灵角色。
        Club club = clubMapper.selectClubByIdForUpdate(clubId);
        if (club == null) {
            throw new IllegalStateException("俱乐部不存在或已解散");
        }
        ClubMember cm = clubMemberMapper.selectByClubAndMember(clubId, memberId);
        if (cm == null) {
            return true;   // 成员不存在由上游先校验,这里不重复报错
        }
        if (role != 1) {   // 降级不受上限约束
            cm.setRole(0);
            clubMemberMapper.updateClubMember(cm);
            return true;
        }
        if (Integer.valueOf(1).equals(cm.getRole())) {
            return true;   // 已是管理员 ⇒ 幂等,不能因为「已达上限」把他自己顶掉
        }
        // 已锁【club 行】—— 计数的分组键。锁 club_member 行是错粒度:两个不同 memberId 并发提管理员时
        // 各锁各的行,谁也拦不住谁。范式取自 acceptInviteWithLock(锁内 count + 拒),
        // ⚠️ 不是 confirmWinnerIfPending 的 CAS —— 那是单行状态机,ApiCoopController:768 自己就写着
        // 「CAS 只保证本报名待定→中标原子,挡不住同节点已有别家中标」,跨行计数不能用它。
        // ⚠️ 也不用唯一索引:裸 UNIQUE 表达不了「至多 2」,要表达得引入槽位列(admin_slot),
        // 那是为一条上限新增一个要维护的状态维度,悲观锁够用且改动更小。
        if (clubMemberMapper.countAdminsByClub(clubId) >= MAX_ADMINS_PER_CLUB) {
            return false;
        }
        cm.setRole(1);
        clubMemberMapper.updateClubMember(cm);
        return true;
    }

    @Override
    public boolean canGovernClub(Long clubId, Long actorId)
    {
        if (clubId == null || actorId == null) {
            return false;
        }
        return canGovernClub(clubMapper.selectClubById(clubId), actorId);
    }

    @Override
    public boolean canGovernClub(Club club, Long actorId)
    {
        if (club == null || club.getId() == null || actorId == null) {
            return false;
        }
        if (actorId.equals(club.getMemberId())) {
            return true;   // 主理人:用调用方手上的 club,不再查一次库
        }
        ClubMember cm = clubMemberMapper.selectByClubAndMember(club.getId(), actorId);
        if (cm != null && Integer.valueOf(1).equals(cm.getRole())
                && Integer.valueOf(1).equals(cm.getStatus())) {
            return true;
        }
        if (clubAccessService == null) {
            return false;
        }
        try {
            ClubAccessContext access = clubAccessService.resolve(actorId, club.getId(), null);
            return access != null
                    && actorId.equals(access.getPrincipalMemberId())
                    && club.getId().equals(access.getClubId())
                    && club.getMemberId() != null
                    && club.getMemberId().equals(access.getOwnerMemberId())
                    && access.hasPermission(ClubPermission.MEMBER_MANAGE);
        } catch (com.chengyinhub.common.exception.ServiceException ignored) {
            return false;
        }
    }

    @Override
    public ClubMember selectByClubAndMember(Long clubId, Long memberId)
    {
        return clubMemberMapper.selectByClubAndMember(clubId, memberId);
    }

    @Override
    public ClubMember selectMembershipRecord(Long clubId, Long memberId)
    {
        return clubMemberMapper.selectAnyByClubAndMember(clubId, memberId);
    }

    @Override
    public List<ClubMember> selectClubMemberList(ClubMember clubMember)
    {
        return clubMemberMapper.selectClubMemberList(clubMember);
    }

    @Override
    public List<Long> selectClubIdsByMember(Long memberId)
    {
        return clubMemberMapper.selectClubIdsByMember(memberId);
    }

    @Override
    public int countByClub(Long clubId)
    {
        return clubMemberMapper.countByClub(clubId);
    }

    @Override
    public int countEffectiveMembers(Club club)
    {
        if (club == null || club.getId() == null) {
            return 0;
        }
        int count = clubMemberMapper.countByClub(club.getId());
        if (club.getMemberId() != null
                && clubMemberMapper.selectByClubAndMember(club.getId(), club.getMemberId()) == null) {
            count += 1;
        }
        return count;
    }

    @Override
    public int insertClubMember(ClubMember clubMember)
    {
        return clubMemberMapper.insertClubMember(clubMember);
    }

    @Override
    public int updateClubMember(ClubMember clubMember)
    {
        return clubMemberMapper.updateClubMember(clubMember);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteByClubAndMember(Long clubId, Long memberId)
    {
        if (clubMapper.selectClubByIdForUpdate(clubId) == null)
        {
            return 0;
        }
        return clubMemberMapper.deleteByClubAndMember(clubId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean joinMember(ClubMember clubMember)
    {
        Club club = clubMapper.selectClubByIdForUpdate(clubMember.getClubId());
        if (club == null) {
            throw new IllegalArgumentException("俱乐部不存在或未开放");
        }
        clubParticipationGuard.requireAllowed(clubMember.getClubId(), clubMember.getMemberId());
        ClubMember existing = clubMemberMapper.selectAnyByClubAndMember(
                clubMember.getClubId(), clubMember.getMemberId());
        if (existing != null) {
            if (Integer.valueOf(1).equals(existing.getStatus())) {
                return false;
            }
            clubMemberMapper.updateMembershipState(existing.getId(), 1, 0, new java.util.Date());
        } else {
            clubMember.setStatus(1);
            clubMember.setRole(0);
            if (clubMember.getJoinTime() == null) clubMember.setJoinTime(new java.util.Date());
            clubMemberMapper.insertClubMember(clubMember);
        }
        clubMapper.updateMemberCount(clubMember.getClubId(), 1);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String requestJoin(Long clubId, Long memberId)
    {
        return requestJoin(clubId, memberId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String requestJoin(Long clubId, Long memberId, String joinMessage)
    {
        Club club = clubMapper.selectClubByIdForUpdate(clubId);
        if (club == null || !Long.valueOf(1L).equals(club.getStatus())) {
            throw new IllegalArgumentException("俱乐部不存在或未开放");
        }
        if (memberId != null && memberId.equals(club.getMemberId())) {
            return JOIN_STATE_JOINED;
        }
        clubParticipationGuard.requireAllowed(clubId, memberId);

        ClubMember existing = clubMemberMapper.selectAnyByClubAndMember(clubId, memberId);
        if (existing != null && Integer.valueOf(1).equals(existing.getStatus())) {
            return JOIN_STATE_JOINED;
        }

        // 策略必须在锁住 club 行后读取：否则 owner 并发从公开切到审批时，旧读值会把申请人直接放成成员。
        // 只有探针明确返回 0（迁移未落库）才降级到既有“公开直进”。探针/读取异常无法证明
        // 俱乐部是公开团，必须 fail-closed；否则一次瞬时故障就会把私密团申请人直接升级成成员。
        boolean approvalRequired = false;
        int policyColumnCount;
        try {
            policyColumnCount = clubMapper.countJoinPolicyColumn();
        } catch (RuntimeException e) {
            log.error("探测 join_policy 失败，拒绝自动放行入会，clubId={}", clubId, e);
            throw new IllegalStateException("入会服务暂不可用，请稍后重试", e);
        }
        if (policyColumnCount > 0) {
            try {
                Integer policy = clubMapper.selectJoinPolicy(clubId);
                if (policy == null || (policy != 0 && policy != 1)) {
                    throw new IllegalStateException("join_policy 值不合法");
                }
                approvalRequired = policy == 1;
            } catch (RuntimeException e) {
                log.error("读取 join_policy 失败，拒绝自动放行入会，clubId={}", clubId, e);
                throw new IllegalStateException("入会服务暂不可用，请稍后重试", e);
            }
        }

        int targetStatus = approvalRequired ? 0 : 1;
        java.util.Date now = new java.util.Date();
        if (existing == null) {
            ClubMember membership = new ClubMember();
            membership.setClubId(clubId);
            membership.setMemberId(memberId);
            membership.setRole(0);
            membership.setStatus(targetStatus);
            // 留言只在待审的申请上留痕:公开直进的团这一行立刻就是正式成员,留言没有读者。
            membership.setJoinMessage(approvalRequired ? trimJoinMessage(joinMessage) : null);
            membership.setJoinTime(now);
            clubMemberMapper.insertClubMember(membership);
        } else {
            if (!Integer.valueOf(targetStatus).equals(existing.getStatus())) {
                clubMemberMapper.updateMembershipState(existing.getId(), targetStatus, 0, now);
            }
            // 被拒之后重新申请:这次写的话要盖掉上次那句,别让主理人看着旧留言做新判断。
            if (approvalRequired) {
                clubMemberMapper.updateJoinMessage(existing.getId(), trimJoinMessage(joinMessage));
            }
        }

        if (!approvalRequired) {
            clubMapper.updateMemberCount(clubId, 1);
            return JOIN_STATE_JOINED;
        }
        return JOIN_STATE_PENDING;
    }

    /** 列宽 200,截在 200 字符;空白只有空格的当没写。 */
    private static String trimJoinMessage(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewJoinRequest(Long clubId, Long memberId, Long ownerId, boolean approve)
    {
        Club club = clubMapper.selectClubByIdForUpdate(clubId);
        if (club == null) {
            throw new IllegalArgumentException("俱乐部不存在");
        }
        // 2026-08-26:管理员也能审批(管人类)。用锁内取到的 club,别再查一次库。
        if (!canApproveMembers(club, ownerId)) {
            throw new IllegalArgumentException("只有主理人或管理员可审批入会申请");
        }
        ClubMember pending = clubMemberMapper.selectAnyByClubAndMember(clubId, memberId);
        if (pending == null || !Integer.valueOf(0).equals(pending.getStatus())) {
            throw new IllegalArgumentException("入会申请不存在或已处理");
        }
        if (approve) {
            clubParticipationGuard.requireAllowed(clubId, memberId);
            clubMemberMapper.updateMembershipState(pending.getId(), 1, 0, new java.util.Date());
            clubMapper.updateMemberCount(clubId, 1);
        } else {
            clubMemberMapper.updateMembershipState(pending.getId(), 2, 0, null);
        }
    }

    @Override
    public List<ClubMember> selectPendingJoinRequests(Long clubId, Long ownerId)
    {
        Club club = clubMapper.selectClubById(clubId);
        if (club == null) {
            throw new IllegalArgumentException("俱乐部不存在");
        }
        if (!canApproveMembers(club, ownerId)) {
            throw new IllegalArgumentException("只有主理人或管理员可查看入会申请");
        }
        return clubMemberMapper.selectPendingByClub(clubId);
    }

    private boolean canApproveMembers(Club club, Long principalMemberId)
    {
        if (club == null || club.getId() == null || principalMemberId == null
                || club.getMemberId() == null) {
            return false;
        }
        if (canGovernClub(club, principalMemberId)) {
            return true;
        }
        try {
            ClubAccessContext access = clubAccessService.resolve(principalMemberId, club.getId(), null);
            return access != null
                    && principalMemberId.equals(access.getPrincipalMemberId())
                    && club.getId().equals(access.getClubId())
                    && club.getMemberId().equals(access.getOwnerMemberId())
                    && access.hasPermission(ClubPermission.MEMBER_APPROVE);
        } catch (com.chengyinhub.common.exception.ServiceException ignored) {
            return false;
        }
    }

    @Override
    public int countPendingJoinRequests(Long clubId)
    {
        return clubMemberMapper.countPendingByClub(clubId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int quitMember(Long clubId, Long memberId)
    {
        return quitMemberAndLeaveGroup(clubId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int quitMemberAndLeaveGroup(Long clubId, Long memberId)
    {
        return quitMemberAndLeaveGroup(
                clubId, memberId, memberId, "SYSTEM_ACCESS_END", "MEMBER_QUIT_OR_REMOVED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int quitMemberAndLeaveGroup(Long clubId, Long memberId, Long actorMemberId,
                                       String actorRoleCode, String reason)
    {
        if (clubMapper.selectClubByIdForUpdate(clubId) == null)
        {
            // 即便俱乐部业务行已不存在，也清理一次历史 IM 分叉行。
            imService.leaveClubGroup(clubId, memberId);
            return 0;
        }
        clubRoleAssignmentService.revokeAllForAccessEnd(
                clubId, memberId, actorMemberId, actorRoleCode, reason);
        int rows = clubMemberMapper.deleteByClubAndMember(clubId, memberId);
        if (rows > 0) {
            clubMapper.updateMemberCount(clubId, -1);
        }
        // REQUIRED 事务内执行：异常向外抛出，业务成员删除与计数更新一并回滚。
        // rows==0 仍清理一次，给历史“业务已退、IM 仍在”的分叉行提供幂等修复路径。
        imService.leaveClubGroup(clubId, memberId);
        return rows;
    }

    // ===== 本团贡献榜 =====
    //
    // 综合分 = W_COUNT·通关场次 + W_MILEAGE·本团里程(km) + W_HOST·发起团数。
    // 权重 2026-07-16 拍板,刻意写成 Java 常量而非 sys_config:改权重会让整个榜单跳名次,
    // 本就不该是能在后台随手改的东西;真要收进配置,得连带一份 migration_* 注册 SQL(③,合前必须落库)。
    private static final BigDecimal W_COUNT   = new BigDecimal("10");
    private static final BigDecimal W_MILEAGE = new BigDecimal("1");
    private static final BigDecimal W_HOST    = new BigDecimal("5");

    private static final int LEADERBOARD_LIMIT = 50;

    /** 本周一 00:00(Asia/Shanghai)。周一当天 = 本周,不是上周。 */
    static Date currentWeekStart() {
        java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate monday = java.time.LocalDate.now(shanghai)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return Date.from(monday.atStartOfDay(shanghai).toInstant());
    }

    @Override
    public List<Map<String, Object>> selectClubLeaderboard(Long clubId, String sortBy)
    {
        // ★2026-09-11:榜单是**周榜**。UI 上写死了「本团贡献榜 · 本周」与「榜单每周一 0 点重置」,
        //   而在此之前 SQL 里没有任何时间条件 —— 用户看到的是终身累计榜,那两句文案是假的。
        //   窗口在这里算、不写进 SQL:一处真源,IT 要跨月造数据时传 null 绕开。
        List<Map<String, Object>> rows = clubMemberMapper.selectClubContribution(clubId, currentWeekStart());
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        for (Map<String, Object> r : rows) {
            BigDecimal count   = toDecimal(r.get("clearCount"));
            BigDecimal mileage = toDecimal(r.get("mileage"));
            BigDecimal minutes = toDecimal(r.get("durationMin"));
            BigDecimal hosted  = toDecimal(r.get("hostedCount"));

            r.put("score", W_COUNT.multiply(count)
                    .add(W_MILEAGE.multiply(mileage))
                    .add(W_HOST.multiply(hosted))
                    .setScale(1, RoundingMode.HALF_UP));
            r.put("mileage", mileage.setScale(1, RoundingMode.HALF_UP));
            // 配速 min/km。里程或用时任一为 0 都判「无配速」(null),不是 0:
            //  · 零里程照除会崩(除零);
            //  · 零用时照除得 0.0 min/km = 无限快,会稳居榜首 —— 而零用时恰恰是
            //    「单节点团 / 只打卡一次」的常态(MAX-MIN=0),等于把没花时间的人捧成第一。
            // null 在配速榜按 nullsLast 沉底。
            r.put("pace", mileage.compareTo(BigDecimal.ZERO) > 0 && minutes.compareTo(BigDecimal.ZERO) > 0
                    ? minutes.divide(mileage, 1, RoundingMode.HALF_UP)
                    : null);
            // 完成用时榜与配速榜不同:按实际累计打卡用时比快慢。
            // 0 代表单节点/缺少有效计时,不是 0 分钟完成,必须沉底而不能占榜首。
            r.put("completionDuration", minutes.compareTo(BigDecimal.ZERO) > 0 ? minutes : null);
        }
        // CU-C-72(9-25 裁决:按单个榜剔除):每个榜只列「该榜成绩 > 0」的成员,各榜各自判。
        // 综合分>0、里程>0、完成用时>0、配速存在 —— 里程榜里 0.0 km 的人不再占名次,
        // 完成用时榜里没有效计时的人不再以「—」垫底。空态归前端:该榜为空 = 真的该榜没人有成绩。
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (qualifiesForBoard(r, sortBy)) {
                ranked.add(r);
            }
        }
        ranked.sort(comparatorFor(sortBy));
        return ranked.size() > LEADERBOARD_LIMIT
                ? new ArrayList<>(ranked.subList(0, LEADERBOARD_LIMIT))
                : ranked;
    }

    /**
     * CU-C-72(9-25 裁决):按单个榜剔除零成绩 —— 每个榜只列「该榜成绩 > 0」的成员。
     *
     * <p>榜单是「在册成员 LEFT JOIN 三张成绩表」,没有成绩的成员被 ifnull 补成 0。
     * 若只像 #1169 那样剔除「三项全 0」,那么一个只发起过团(mileage=0)的人仍会挂在
     * 里程榜上显示「0.0 km」占一个名次、一个零用时的人挂在完成用时榜显示「—」——
     * 界面没成绩却产生名次,和空态自相矛盾。裁决改成各榜各自判。
     *
     * <ul>
     *   <li>综合(composite):加权分 &gt; 0,等价于三个原始度量任一 &gt; 0(权重皆为正)。</li>
     *   <li>里程(mileage):mileage &gt; 0。</li>
     *   <li>完成用时(duration):completionDuration 非 null,即累计打卡用时 &gt; 0。</li>
     *   <li>配速(pace):pace 非 null,即 mileage&gt;0 且 minutes&gt;0(见上方 pace 计算)。</li>
     * </ul>
     *
     * <p>判据用原始度量/派生布尔而不是加权 score:score 是展示值,以后调权重不该改变
     * 「谁算这个榜的贡献者」这个事实。
     */
    private static boolean qualifiesForBoard(Map<String, Object> row, String sortBy)
    {
        if ("mileage".equals(sortBy)) {
            return toDecimal(row.get("mileage")).compareTo(BigDecimal.ZERO) > 0;
        }
        if ("duration".equals(sortBy)) {
            return row.get("completionDuration") != null;
        }
        if ("pace".equals(sortBy)) {
            return row.get("pace") != null;
        }
        // composite(含非法/缺省 sortBy,与 comparatorFor 的回落口径一致):加权分 > 0
        return toDecimal(row.get("score")).compareTo(BigDecimal.ZERO) > 0;
    }

    /** sortBy 白名单;非法/缺省一律回落 composite。memberId 兜底保证同分时顺序稳定(否则分页/测试会飘)。 */
    private static Comparator<Map<String, Object>> comparatorFor(String sortBy)
    {
        if ("pace".equals(sortBy)) {
            // 配速升序 = 快的靠前。无配速(零里程)沉底。
            return Comparator.<Map<String, Object>, BigDecimal>comparing(
                        r -> (BigDecimal) r.get("pace"),
                        Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                    .thenComparing(ClubMemberServiceImpl::memberIdOf);
        }
        if ("mileage".equals(sortBy)) {
            return Comparator.<Map<String, Object>, BigDecimal>comparing(r -> toDecimal(r.get("mileage")))
                    .reversed()
                    .thenComparing(ClubMemberServiceImpl::memberIdOf);
        }
        if ("duration".equals(sortBy)) {
            return Comparator.<Map<String, Object>, BigDecimal>comparing(
                        r -> (BigDecimal) r.get("completionDuration"),
                        Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                    .thenComparing(ClubMemberServiceImpl::memberIdOf);
        }
        return Comparator.<Map<String, Object>, BigDecimal>comparing(r -> toDecimal(r.get("score")))
                .reversed()
                .thenComparing(ClubMemberServiceImpl::memberIdOf);
    }

    private static Long memberIdOf(Map<String, Object> r)
    {
        Object v = r.get("memberId");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** MyBatis 的 Map 里 count(*) 是 Long、sum(decimal) 是 BigDecimal、timestampdiff 是 Long —— 统一收口。 */
    private static BigDecimal toDecimal(Object v)
    {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        return BigDecimal.ZERO;
    }
}
