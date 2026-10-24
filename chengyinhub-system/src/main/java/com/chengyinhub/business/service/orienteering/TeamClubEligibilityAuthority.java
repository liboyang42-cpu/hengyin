package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * TEAM / 多俱乐部赛事资格的独占权威。资格只读现有俱乐部成员行、主理人归属和治理禁赛闸，
 * 不另造成员真源，不恢复已取消的随机匹配。
 */
public final class TeamClubEligibilityAuthority {

    public static final int TEAM_MIN = 2;
    public static final int TEAM_MAX = 4;
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_REGISTERED = "REGISTERED";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    public enum EntryMode { SOLO, TEAM }

    private final IClubService clubService;
    private final IClubMemberService clubMemberService;
    private final IClubParticipationGuard participationGuard;
    private final Map<String, Registration> byRequestId = new ConcurrentHashMap<String, Registration>();

    public TeamClubEligibilityAuthority(IClubService clubService, IClubMemberService clubMemberService,
                                        IClubParticipationGuard participationGuard) {
        if (clubService == null || clubMemberService == null || participationGuard == null) {
            throw new IllegalArgumentException("俱乐部权威依赖不能为空");
        }
        this.clubService = clubService;
        this.clubMemberService = clubMemberService;
        this.participationGuard = participationGuard;
    }

    public Registration register(CmsTopic course, EntryMode mode, Collection<Long> memberIds,
                                 Collection<Long> authorizedClubIds, String requestId) {
        String key = requireKey(requestId);
        if (course == null || course.getId() == null || course.getId() <= 0) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "主题参数有误");
        }
        if (mode == null) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "报名方式有误");
        }
        List<Long> members = distinctPositive(memberIds);
        if (members.isEmpty()) {
            throw new OrienteeringEventException(OrienteeringEventException.MATCHMAKING_CANCELLED,
                    "须显式报名队伍或个人，随机匹配已取消");
        }
        if (mode == EntryMode.SOLO && members.size() != 1) {
            throw new OrienteeringEventException(OrienteeringEventException.TEAM_INELIGIBLE, "个人报名只能有一名成员");
        }
        if (mode == EntryMode.TEAM && (members.size() < TEAM_MIN || members.size() > TEAM_MAX)) {
            throw new OrienteeringEventException(OrienteeringEventException.TEAM_INELIGIBLE,
                    "组队须 2–4 人且显式报名");
        }
        List<Long> clubs = distinctPositive(authorizedClubIds);
        if (mode == EntryMode.TEAM && clubs.isEmpty()) {
            throw new OrienteeringEventException(OrienteeringEventException.CLUB_AUTHORITY_MISSING,
                    "组队赛事必须绑定真实俱乐部权威");
        }
        if (mode == EntryMode.SOLO && clubs.isEmpty() && course.getClubId() != null) {
            clubs = List.of(course.getClubId());
        }
        Map<Long, Long> memberClub = new LinkedHashMap<Long, Long>();
        Set<Long> usedClubs = new LinkedHashSet<Long>();
        for (Long memberId : members) {
            Long clubId = qualifyMember(memberId, clubs, mode);
            memberClub.put(memberId, clubId);
            if (clubId != null) usedClubs.add(clubId);
        }
        Registration registration = new Registration(key, course.getId(), mode, members, new ArrayList<Long>(usedClubs),
                memberClub);
        Registration previous = byRequestId.putIfAbsent(key, registration);
        if (previous != null) {
            if (!previous.sameAs(registration)) {
                throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                        "同一报名键不能改写成员或俱乐部");
            }
            return previous;
        }
        return registration;
    }

    public Registration readback(String requestId) {
        Registration found = requestId == null ? null : byRequestId.get(requestId);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条报名回执");
        }
        return found;
    }

    public OriginalReceipt recoverOriginal(String requestId) {
        return recoverUnknown(requestId);
    }

    /**
     * Missing / blank requestId stays Java {@code null} with typed {@code NOT_FOUND}.
     * Never-seen ids return {@code NOT_FOUND} without occupying the ledger.
     */
    public OriginalReceipt recoverUnknown(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            return OriginalReceipt.notFound(null);
        }
        Registration found = byRequestId.get(id);
        if (found == null) {
            return OriginalReceipt.notFound(id);
        }
        return new OriginalReceipt(found.requestId, OUTCOME_REGISTERED, false, found);
    }

    /** 已取消能力的显式拒绝，避免调用方把空名单交给随机匹配。 */
    public Registration refuseRandomMatchmaking(CmsTopic course, Collection<Long> authorizedClubIds, String requestId) {
        throw new OrienteeringEventException(OrienteeringEventException.MATCHMAKING_CANCELLED,
                "随机匹配已取消，不能代为组队");
    }

    private Long qualifyMember(Long memberId, List<Long> authorizedClubIds, EntryMode mode) {
        if (authorizedClubIds.isEmpty()) {
            if (mode == EntryMode.SOLO) return null;
            throw new OrienteeringEventException(OrienteeringEventException.CLUB_AUTHORITY_MISSING,
                    "组队赛事必须绑定真实俱乐部权威");
        }
        for (Long clubId : authorizedClubIds) {
            if (isEligibleForClub(clubId, memberId)) return clubId;
        }
        throw new OrienteeringEventException(OrienteeringEventException.TEAM_INELIGIBLE,
                "成员 " + memberId + " 不在授权俱乐部的有效成员或主理人范围内");
    }

    boolean isEligibleForClub(Long clubId, Long memberId) {
        Club club = clubService.selectClubById(clubId);
        if (club == null || !clubId.equals(club.getId()) || !Integer.valueOf(0).equals(club.getDelFlag())
                || !Long.valueOf(1L).equals(club.getStatus())) {
            return false;
        }
        boolean eligible = memberId.equals(club.getMemberId());
        ClubMember row = clubMemberService.selectByClubAndMember(clubId, memberId);
        if (row != null) {
            if (!clubId.equals(row.getClubId()) || !memberId.equals(row.getMemberId())
                    || !Integer.valueOf(1).equals(row.getStatus())
                    || (row.getDelFlag() != null && !Integer.valueOf(0).equals(row.getDelFlag()))) {
                return false;
            }
            eligible = true;
        }
        if (!eligible) return false;
        try {
            participationGuard.requireAllowed(clubId, memberId);
        } catch (ServiceException blocked) {
            throw new OrienteeringEventException(OrienteeringEventException.TEAM_INELIGIBLE,
                    blocked.getMessage() == null ? "该成员当前无法参与该俱乐部" : blocked.getMessage());
        }
        return true;
    }

    private static List<Long> distinctPositive(Collection<Long> ids) {
        LinkedHashSet<Long> unique = new LinkedHashSet<Long>();
        if (ids == null) return List.of();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "成员或俱乐部参数有误");
            }
            unique.add(id);
        }
        return new ArrayList<Long>(unique);
    }

    static String parseKey(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }

    private static String requireKey(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "报名幂等键无效");
        }
        return id;
    }

    public static final class OriginalReceipt {
        public final String originalRequestId;
        public final String outcome;
        public final boolean mintedNewRequest;
        public final Registration registration;

        OriginalReceipt(String originalRequestId, String outcome, boolean mintedNewRequest, Registration registration) {
            this.originalRequestId = originalRequestId;
            this.outcome = outcome;
            this.mintedNewRequest = mintedNewRequest;
            this.registration = registration;
        }

        static OriginalReceipt notFound(String requestId) {
            return new OriginalReceipt(requestId, OUTCOME_NOT_FOUND, false, null);
        }
    }

    public static final class Registration {
        public final String requestId;
        public final long topicId;
        public final EntryMode mode;
        public final List<Long> memberIds;
        public final List<Long> authorizedClubIdsUsed;
        public final Map<Long, Long> memberClubId;

        Registration(String requestId, long topicId, EntryMode mode, List<Long> memberIds,
                     List<Long> authorizedClubIdsUsed, Map<Long, Long> memberClubId) {
            this.requestId = requestId;
            this.topicId = topicId;
            this.mode = mode;
            this.memberIds = Collections.unmodifiableList(new ArrayList<Long>(memberIds));
            this.authorizedClubIdsUsed = Collections.unmodifiableList(new ArrayList<Long>(authorizedClubIdsUsed));
            this.memberClubId = Collections.unmodifiableMap(new LinkedHashMap<Long, Long>(memberClubId));
        }

        boolean sameAs(Registration other) {
            return topicId == other.topicId && mode == other.mode
                    && memberIds.equals(other.memberIds)
                    && authorizedClubIdsUsed.equals(other.authorizedClubIdsUsed)
                    && memberClubId.equals(other.memberClubId);
        }
    }
}
