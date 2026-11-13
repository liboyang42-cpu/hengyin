package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.dto.ScopedLeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardCoverageVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardVO;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.OwnershipUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * BE-14 多作用域榜单只读投影（world / country / city / quest / club × week / all）。
 *
 * <p>设计边界：
 * <ul>
 *   <li>只读：分数来自现有完成事实与现有已支付报名/通行证；本类与 Mapper 没有任何写语句，
 *       不改现 global 榜（{@code MemberLeaderboardMapper}）、分数写入、积分钱包。</li>
 *   <li>不造分数：score 口径固定 {@code COMPLETED_NODES}（已完成节点数），与现
 *       {@code PlayerNodeProgressMapper.selectActivityLeaderboard/selectRouteLeaderboard} 的 score 同义；
 *       提示消费、解谜分、竞品分值都不参与。</li>
 *   <li>资格隔离（C05）：未支付、取消、过期、已退款的报名/通行证一律不进榜；
 *       无法归因到订单的旧局只计入 {@code coverage.unverifiedRuns}，不补 0、不猜名次。</li>
 *   <li>地理归因只看该局冻结快照：city/country 从
 *       {@code topic_route_session.config_snapshot_json.themeConfig.contentGeography}
 *       读该局冻结的 cityCode/countryCode（由正式发布编译器写入，见 BE-06 契约）。
 *       没有冻结值的旧局一律 UNKNOWN：不进 city/country 榜，只进 {@code coverage.unattributedRuns}；
 *       绝不读 {@code cms_topic.city_code} 这类当前可编辑值补历史，不读节点坐标/地址，
 *       也不从用户位置推断国家。{@code coverage.attributionBasis=FROZEN_RUN_SNAPSHOT} 明示这一点。</li>
 *   <li>俱乐部：先校验请求者权限（主理人 / 在册成员 / 公开可浏览且未被封禁），
 *       榜单行本身也只含该俱乐部在册成员/主理人在其归属主题上的对局。</li>
 *   <li>队伍（UI-P9 队内榜）：只有该队在岗成员能看；榜单行只含在岗成员在该队自身内容（主题/场次）上的对局，
 *       分数口径、有效报名与周窗与其它作用域相同；不做好友榜，不新增公会。</li>
 *   <li>响应只含 memberId/nickname/avatar/score/rank：不含手机号、邮箱、真实姓名、精确位置。</li>
 * </ul>
 */
@Service
public class ScopedLeaderboardApplication {

    static final String SCOPE_WORLD = "world";
    static final String SCOPE_COUNTRY = "country";
    static final String SCOPE_CITY = "city";
    static final String SCOPE_QUEST = "quest";
    static final String SCOPE_CLUB = "club";
    static final String SCOPE_TEAM = "team";

    /** 当前唯一分数口径；将来新增口径必须另有权威事实，不得改这一个的含义。 */
    static final String SCORE_BASIS_COMPLETED_NODES = "COMPLETED_NODES";

    /** 地理归因依据（与 coverage.attributionBasis 同值域）。 */
    static final String ATTRIBUTION_SCOPE_CONTENT = "SCOPE_CONTENT";
    static final String ATTRIBUTION_FROZEN_RUN_SNAPSHOT = "FROZEN_RUN_SNAPSHOT";

    /** 冻结国家码形状：ISO 3166-1 alpha-2（大写两位）。空值=UNKNOWN，不推断。 */
    private static final java.util.regex.Pattern ISO_COUNTRY = java.util.regex.Pattern.compile("[A-Z]{2}");

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    @Autowired
    private ScopedLeaderboardMapper scopedLeaderboardMapper;
    @Autowired
    private IClubService clubService;
    @Autowired
    private IClubMemberService clubMemberService;
    @Autowired(required = false)
    private IClubParticipationGuard clubParticipationGuard;
    @Autowired
    private PlayTeamMapper playTeamMapper;

    /** 入口：校验作用域与权限后返回只读投影。 */
    public AjaxResult list(Long actorMemberId, ScopedLeaderboardQueryDTO query) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0) return error("请先登录");
        String scope = normalizeScope(query == null ? null : query.getScope());
        if (scope == null) return error("scope 不合法");
        String period = normalizePeriod(query == null ? null : query.getPeriod());
        String scopeId = trimToNull(query == null ? null : query.getScopeId());
        int limit = clampLimit(query == null ? null : query.getLimit());

        Long scopeIdLong = null;
        if (SCOPE_QUEST.equals(scope) || SCOPE_CLUB.equals(scope) || SCOPE_TEAM.equals(scope)) {
            scopeIdLong = parseId(scopeId);
            if (scopeIdLong == null) return error("scopeId 不合法");
        } else if (SCOPE_CITY.equals(scope)) {
            if (scopeId == null) return error("城市榜需要冻结 cityCode");
        } else if (SCOPE_COUNTRY.equals(scope)) {
            // 只接受发布者填写、随发布冻结的 ISO 3166-1 alpha-2；不接受国家名/adcode/用户位置。
            String country = scopeId == null ? null : scopeId.toUpperCase(Locale.ROOT);
            if (country == null || !ISO_COUNTRY.matcher(country).matches()) {
                return error("国家榜需要 ISO 3166-1 alpha-2 两位国家码");
            }
            scopeId = country;
        }
        if (SCOPE_CLUB.equals(scope)) {
            AjaxResult denied = requireClubViewer(scopeIdLong, memberId);
            if (denied != null) return denied;
        }
        if (SCOPE_TEAM.equals(scope)) {
            AjaxResult denied = requireTeamMember(scopeIdLong, memberId);
            if (denied != null) return denied;
        }

        Instant nowInstant = Instant.now();
        Date weekStart = ScopedLeaderboardPeriods.PERIOD_WEEK.equals(period)
                ? ScopedLeaderboardPeriods.weekStart(nowInstant) : null;
        Date weekEnd = weekStart == null ? null : ScopedLeaderboardPeriods.weekEnd(weekStart);

        List<ScopedLeaderboardEntryVO> entries = scopedLeaderboardMapper.selectScopedTop(
                scope, scopeId, scopeIdLong, weekStart, weekEnd, limit);
        if (entries == null) entries = new ArrayList<>();
        assignCompetitionRanks(entries);

        ScopedLeaderboardEntryVO me = scopedLeaderboardMapper.selectScopedMemberRow(
                scope, scopeId, scopeIdLong, weekStart, weekEnd, memberId);
        if (me == null) {
            me = new ScopedLeaderboardEntryVO();
            me.setMemberId(memberId);
            me.setScore(0L); // 无有效对局：0 分且不猜名次（与现 /api/play/leaderboard 同口径）
            me.setRank(null);
        } else {
            me.setRank(scopedLeaderboardMapper.selectScopedMemberRank(
                    scope, scopeId, scopeIdLong, weekStart, weekEnd, memberId));
        }

        ScopedLeaderboardVO vo = new ScopedLeaderboardVO();
        vo.setScope(scope);
        vo.setScopeId(scopeId);
        vo.setPeriod(period);
        vo.setPeriodStart(weekStart);
        vo.setPeriodEnd(weekEnd);
        vo.setScoreBasis(SCORE_BASIS_COMPLETED_NODES);
        vo.setEntries(entries);
        vo.setMe(me);
        vo.setCoverage(buildCoverage(scope, scopeId, scopeIdLong, weekStart, weekEnd));
        return AjaxResult.success(vo);
    }

    private ScopedLeaderboardCoverageVO buildCoverage(String scope, String scopeId, Long scopeIdLong,
                                                      Date weekStart, Date weekEnd) {
        ScopedLeaderboardCoverageVO coverage = new ScopedLeaderboardCoverageVO();
        coverage.setRankedMembers(scopedLeaderboardMapper.countRankedMembers(
                scope, scopeId, scopeIdLong, weekStart, weekEnd));
        coverage.setUnverifiedRuns(scopedLeaderboardMapper.countUnverifiedRuns(
                scope, scopeId, scopeIdLong, weekStart, weekEnd));
        if (SCOPE_CITY.equals(scope) || SCOPE_COUNTRY.equals(scope)) {
            coverage.setAttributionBasis(ATTRIBUTION_FROZEN_RUN_SNAPSHOT);
            coverage.setUnattributedRuns(scopedLeaderboardMapper.countUnattributedRuns(
                    scope, scopeId, scopeIdLong, weekStart, weekEnd));
        } else {
            coverage.setAttributionBasis(ATTRIBUTION_SCOPE_CONTENT);
        }
        return coverage;
    }

    /**
     * 俱乐部作用域的前置权限闸（IDOR）。判据从严：
     * 主理人、{@code status=1} 的在册成员、或「公开加入 + 俱乐部对外可见 + 未被封禁」的登录用户。
     * 待审/已拒绝的申请人不在其中 —— 新读口默认取严，不沿用贡献榜那条更宽的旧判据。
     */
    private AjaxResult requireClubViewer(Long clubId, Long viewerId) {
        if (clubId == null || clubId <= 0) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        if (OwnershipUtils.isOwner(club.getMemberId(), viewerId)) return null;
        ClubMember membership = clubMemberService.selectMembershipRecord(clubId, viewerId);
        if (membership != null && Integer.valueOf(1).equals(membership.getStatus())) return null;
        if (canBrowsePublicRoster(club, viewerId)) return null;
        return error("加入俱乐部后才能看本团榜单");
    }

    /** 队内榜前置闸（IDOR）：只有该队在岗成员（play_team_member.status=1）；已退/被踢/申请中都不行。 */
    private AjaxResult requireTeamMember(Long teamId, Long viewerId) {
        if (teamId == null || teamId <= 0) return error("缺少队伍ID");
        if (playTeamMapper.selectPlayTeamById(teamId) == null) return error("队伍不存在");
        if (viewerId == null || viewerId <= 0 || playTeamMapper.selectActiveMember(teamId, viewerId) == null)
            return error("只有队伍成员可以查看队内榜");
        return null;
    }

    /** 与 ApiClubController#canBrowsePublicRoster 同口径；只在本类内使用，不改旧接口。 */
    private boolean canBrowsePublicRoster(Club club, Long viewerId) {
        if (club == null || viewerId == null || viewerId <= 0) return false;
        if (club.getStatus() == null || club.getStatus() != 1L) return false;
        if (!canViewClub(club, viewerId)) return false;
        if (!isPublicJoinClub(club)) return false;
        if (clubParticipationGuard == null) return false;
        try {
            clubParticipationGuard.requireAllowed(club.getId(), viewerId);
        } catch (RuntimeException blocked) {
            return false;
        }
        return true;
    }

    private boolean canViewClub(Club club, Long viewerId) {
        if (club.getPublicVisible() == null || !Integer.valueOf(0).equals(club.getPublicVisible())) return true;
        if (OwnershipUtils.isOwner(club.getMemberId(), viewerId)) return true;
        ClubMember membership = clubMemberService.selectMembershipRecord(club.getId(), viewerId);
        return membership != null && Integer.valueOf(1).equals(membership.getStatus());
    }

    private boolean isPublicJoinClub(Club club) {
        if (club == null || club.getId() == null) return false;
        try {
            if (!clubService.isJoinPolicySupported()) return true;
            return Integer.valueOf(0).equals(clubService.selectJoinPolicy(club.getId()));
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    /**
     * 名次：排序键完全相同的相邻行并列（1,1,3），与现
     * {@code LeaderboardRankPolicy#assignRanks} 的线性口径一致（score + finishedAt）。
     */
    static void assignCompetitionRanks(List<ScopedLeaderboardEntryVO> entries) {
        for (int i = 0; i < entries.size(); i++) {
            ScopedLeaderboardEntryVO row = entries.get(i);
            if (i > 0 && sameSortKey(entries.get(i - 1), row)) {
                row.setRank(entries.get(i - 1).getRank());
            } else {
                row.setRank(i + 1);
            }
        }
    }

    private static boolean sameSortKey(ScopedLeaderboardEntryVO a, ScopedLeaderboardEntryVO b) {
        return Objects.equals(a.getScore(), b.getScore()) && Objects.equals(a.getFinishedAt(), b.getFinishedAt());
    }

    static String normalizeScope(String scope) {
        if (scope == null) return null;
        String value = scope.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case SCOPE_WORLD:
            case SCOPE_COUNTRY:
            case SCOPE_CITY:
            case SCOPE_QUEST:
            case SCOPE_CLUB:
            case SCOPE_TEAM:
                return value;
            default:
                return null;
        }
    }

    static String normalizePeriod(String period) {
        if (period == null) return ScopedLeaderboardPeriods.PERIOD_ALL;
        String value = period.trim().toLowerCase(Locale.ROOT);
        return ScopedLeaderboardPeriods.PERIOD_WEEK.equals(value)
                ? ScopedLeaderboardPeriods.PERIOD_WEEK : ScopedLeaderboardPeriods.PERIOD_ALL;
    }

    static int clampLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static Long parseId(String raw) {
        if (raw == null) return null;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
