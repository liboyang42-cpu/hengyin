package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;

import java.util.LinkedHashMap;
import com.chengyinhub.business.domain.vo.ClubEventOccurrenceRowVO;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.domain.vo.ClubEventCancellationStateVO;
import com.chengyinhub.business.domain.vo.ClubEventSeriesView;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.ITeamGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashSet;

@Service
public class ClubEventOpsServiceImpl implements IClubEventOpsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClubEventOpsServiceImpl.class);
    private static final DateTimeFormatter ASSEMBLY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    @Autowired private com.chengyinhub.business.mapper.CmsActivityMapper cancellationActivityMapper;
    @Autowired private com.chengyinhub.business.mapper.CmsTopicMapper cancellationTopicMapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager cancellationTransactions;

    private final IClubAccessService clubAccessService;
    private final ClubEventScheduleMaterializer scheduleMaterializer;
    private final IClubSessionService clubSessionService;
    private final ClubEventOpsMapper clubEventOpsMapper;
    private final ClubMemberMapper clubMemberMapper;
    private final ClubLeadMapper clubLeadMapper;
    private final IClubRoleAssignmentService clubRoleAssignmentService;
    private final ITeamGroupService teamGroupService;

    /** O5b-7 开关(默认关):系列 CLOSED 终态的关闭入口。 */
    @org.springframework.beans.factory.annotation.Value("${chengyin.club.o5b.series-close.enabled:false}")
    private boolean seriesCloseEnabled = false;

    public ClubEventOpsServiceImpl(IClubAccessService clubAccessService,
                                   ClubEventScheduleMaterializer scheduleMaterializer,
                                   IClubSessionService clubSessionService,
                                   ClubEventOpsMapper clubEventOpsMapper,
                                   ClubMemberMapper clubMemberMapper,
                                   ClubLeadMapper clubLeadMapper,
                                   IClubRoleAssignmentService clubRoleAssignmentService) {
        this(clubAccessService, scheduleMaterializer, clubSessionService, clubEventOpsMapper,
                clubMemberMapper, clubLeadMapper, clubRoleAssignmentService, null);
    }

    @Autowired
    public ClubEventOpsServiceImpl(IClubAccessService clubAccessService,
                                   ClubEventScheduleMaterializer scheduleMaterializer,
                                   IClubSessionService clubSessionService,
                                   ClubEventOpsMapper clubEventOpsMapper,
                                   ClubMemberMapper clubMemberMapper,
                                   ClubLeadMapper clubLeadMapper,
                                   IClubRoleAssignmentService clubRoleAssignmentService,
                                   ITeamGroupService teamGroupService) {
        this.clubAccessService = clubAccessService;
        this.scheduleMaterializer = scheduleMaterializer;
        this.clubSessionService = clubSessionService;
        this.clubEventOpsMapper = clubEventOpsMapper;
        this.clubMemberMapper = clubMemberMapper;
        this.clubLeadMapper = clubLeadMapper;
        this.clubRoleAssignmentService = clubRoleAssignmentService;
        this.teamGroupService = teamGroupService;
    }

    @Override
    public List<CmsTopic> listOpenableTopics(Long actorMemberId, Long clubId) {
        if (!positive(actorMemberId) || !positive(clubId)) throw badRequest("俱乐部参数不完整");
        ClubAccessContext access = clubAccessService.require(actorMemberId, clubId, null,
                ClubPermission.ACTIVITY_MANAGE);
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId()) || !positive(access.getOwnerMemberId())) throw forbidden();
        List<CmsTopic> topics = clubSessionService.selectOpenableTopics(clubId, access.getOwnerMemberId());
        return topics == null ? java.util.Collections.<CmsTopic>emptyList() : topics;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ClubEventSeriesView> listSeries(Long actorMemberId, Long clubId) {
        requireActivityManage(actorMemberId, clubId);
        List<ClubEventSeries> rows = clubEventOpsMapper.selectActiveSeriesByClub(clubId);
        if (rows == null) return java.util.Collections.emptyList();
        List<ClubEventSeriesView> result = new ArrayList<>();
        for (ClubEventSeries row : rows) {
            validateReadableSeries(row, clubId);
            List<ClubEventOccurrence> future = clubEventOpsMapper.selectFutureOccurrences(
                    row.getId(), clubId, now());
            result.add(toSeriesView(row, java.util.Collections.<String>emptyList(), future));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClubEventSeriesView getSeries(Long actorMemberId, Long clubId, Long seriesId) {
        if (!positive(seriesId)) throw badRequest("系列参数不完整");
        requireActivityManage(actorMemberId, clubId);
        ClubEventSeries series = clubEventOpsMapper.selectActiveSeriesByIdAndClub(seriesId, clubId);
        validateReadableSeries(series, clubId);
        List<ClubEventOccurrence> rows = clubEventOpsMapper.selectFutureOccurrences(
                seriesId, clubId, futureDateBoundary());
        List<String> dates = new ArrayList<>();
        if (rows != null) for (ClubEventOccurrence row : rows) {
            if (row == null || !seriesId.equals(row.getSeriesId()) || !positive(row.getActivityId())
                    || row.getOccurrenceAt() == null
                    || !ClubEventOccurrence.STATUS_ACTIVE.equals(row.getStatus())) {
                throw conflict("未来场次状态不完整");
            }
            dates.add(row.getOccurrenceAt().toInstant()
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
        }
        return toSeriesView(series, dates, rows);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> listSeriesOccurrences(Long actorMemberId, Long clubId, Long seriesId) {
        if (!positive(seriesId)) throw badRequest("系列参数不完整");
        requireActivityManage(actorMemberId, clubId);
        ClubEventSeries series = clubEventOpsMapper.selectActiveSeriesByIdAndClub(seriesId, clubId);
        validateReadableSeries(series, clubId);

        List<ClubEventOccurrenceRowVO> rows = clubEventOpsMapper.selectSeriesOccurrenceRows(seriesId, clubId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        long now = now().getTime();
        for (ClubEventOccurrenceRowVO row : rows) {
            if (row == null || row.getOccurrenceAt() == null) continue;
            boolean cancelled = ClubEventOccurrence.STATUS_CANCELLED.equals(row.getStatus());
            boolean past = row.getOccurrenceAt().getTime() <= now;
            Integer signup = row.getSignupCount();
            // ⚠️ 报名数算不出来时 editable 必须是 false:不知道有没有人报名就不能放开编辑。
            //    这条比「显示成 0」更要紧 —— 前者只是显示错,后者会让人真的改掉一场有人的活动。
            // 与 FUTURE_ONLY 写口共用同一事实判据：成功报名为 0 不代表没有候补、退款历史或结算。
            boolean protectedHistory = !positive(row.getActivityId())
                    || clubEventOpsMapper.countProtectedActivityHistory(row.getActivityId()) != 0;
            boolean editable = !cancelled && !past && signup != null && signup == 0 && !protectedHistory;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("occurrenceId", row.getOccurrenceId());
            item.put("activityId", row.getActivityId());
            item.put("occurrenceAt", row.getOccurrenceAt());
            item.put("status", row.getStatus());
            item.put("version", row.getVersion());
            item.put("signupCount", signup);
            item.put("refundedCount", row.getRefundedCount());
            item.put("editable", editable);
            item.put("lockReason", cancelled ? "本场已取消"
                    : (past ? "已结束"
                    : (signup == null ? "报名数待确认"
                    : (signup > 0 ? "不可编辑"
                    : (protectedHistory ? "已有报名、候补或结算事实" : null)))));
            out.add(item);
        }
        return out;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ClubEventSeries createSeries(Long actorMemberId, String username, ClubEventSeriesRequest request) {
        if (!positive(actorMemberId) || request == null || !positive(request.getClubId())
                || !positive(request.getTopicId())) throw badRequest("俱乐部与主题参数不完整");
        ClubAccessContext access = requireActivityManage(actorMemberId, request.getClubId());
        Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(request.getClubId());
        access = requireActivityManage(actorMemberId, request.getClubId());
        if (!positive(lockedOwner) || !lockedOwner.equals(access.getOwnerMemberId())) throw forbidden();
        String requestId = requiredRequestId(request.getRequestId());
        Long leadMemberId = request.getDefaultLeadMemberId() == null
                ? access.getOwnerMemberId() : request.getDefaultLeadMemberId();
        requireActiveLead(request.getClubId(), access.getOwnerMemberId(), leadMemberId);
        // 上限与默认值跟 club_event_series 的 CHECK 约束逐字对齐(见
        // migration_club_waitlist_offer_window_20260911.sql)—— 这边放行、库里拒绝,
        // 用户看到的是一句没人看得懂的 SQLIntegrityConstraintViolation。
        int offerMinutes = request.getOfferMinutes() == null ? 1440 : request.getOfferMinutes();
        if (offerMinutes < 5 || offerMinutes > 1440) throw badRequest("候补认领窗口必须为 5 至 1440 分钟");
        Integer capacity = request.getCapacity();
        if (capacity != null && (capacity < 1 || capacity > 10000)) {
            throw badRequest("容量必须为 1 至 10000");
        }
        String recurrenceType = request.getRecurrenceType() == null ? ""
                : request.getRecurrenceType().trim().toUpperCase(Locale.ROOT);
        ClubEventSeries replay = clubEventOpsMapper.selectSeriesByRequest(
                request.getClubId(), actorMemberId, requestId);
        // 已有固定日期回执先核原完整指纹；跨上海日重发不能被“今天”挡在回执之前。
        List<LocalDate> dates = replay != null && ("ONCE".equals(recurrenceType) || "CUSTOM_DATES".equals(recurrenceType))
                ? scheduleMaterializer.normalizeFixedDatesForReplay(request)
                : scheduleMaterializer.materialize(request, todayShanghai());
        // 5.9:集合时刻是场次退款钟的锚点,必须是系列入参的一部分;缺省拒在建系列之前。
        String startTime = requiredStartTime(request);
        String requestFingerprint = seriesFingerprint(actorMemberId, request.getClubId(), request.getTopicId(),
                recurrenceType, dates, startTime, leadMemberId, capacity,
                Boolean.TRUE.equals(request.getWaitlistEnabled()), offerMinutes);
        if (replay != null) {
            return exactSeriesReplay(replay, requestFingerprint);
        }
        // CU-C-35:幂等重放已在上一步返回 —— 提前拦会把「创建已成功、回执丢了再重试」
        // 误判成失败。这里才拦「日期=今天但集合时刻已过」。
        requireAssemblyNotPast(dates, startTime);
        Date now = now();
        ClubEventSeries series = new ClubEventSeries();
        series.setClubId(request.getClubId());
        series.setTopicId(request.getTopicId());
        series.setRecurrenceType(recurrenceType);
        series.setTimezone("Asia/Shanghai");
        series.setDefaultLeadMemberId(leadMemberId);
        series.setDefaultCapacity(capacity);
        series.setWaitlistEnabled(Boolean.TRUE.equals(request.getWaitlistEnabled()));
        series.setOfferMinutes(offerMinutes);
        series.setStatus(ClubEventSeries.STATUS_ACTIVE);
        series.setVersion(0);
        series.setCreatedByMemberId(actorMemberId);
        series.setRequestId(requestId);
        series.setRequestFingerprint(requestFingerprint);
        series.setCreateBy(String.valueOf(actorMemberId));
        series.setCreateTime(now);
        series.setUpdateBy(String.valueOf(actorMemberId));
        series.setUpdateTime(now);
        try {
            if (clubEventOpsMapper.insertSeries(series) != 1 || !positive(series.getId())) {
                throw conflict("系列创建失败，请刷新后重试");
            }
        } catch (DuplicateKeyException duplicate) {
            ClubEventSeries concurrent = clubEventOpsMapper.selectSeriesByRequestForUpdate(
                    request.getClubId(), actorMemberId, requestId);
            if (concurrent == null) throw conflict("系列创建冲突，请刷新后重试");
            return exactSeriesReplay(concurrent, requestFingerprint);
        }
        for (LocalDate date : dates) {
            ClubSessionOpenRequest open = new ClubSessionOpenRequest();
            open.setClubId(request.getClubId());
            open.setTopicId(request.getTopicId());
            open.setSessionDate(assemblyMoment(date, startTime));
            open.setSessionTime(startTime);
            Long activityId = clubSessionService.open(open, access.getOwnerMemberId(), username);
            if (!positive(activityId)) throw conflict("系列场次创建失败");
            applyCapacity(activityId, capacity);
            ClubEventOccurrence occurrence = new ClubEventOccurrence();
            occurrence.setSeriesId(series.getId());
            occurrence.setActivityId(activityId);
            occurrence.setOccurrenceAt(open.getSessionDate());
            occurrence.setDefaultLeadMemberId(leadMemberId);
            occurrence.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
            occurrence.setVersion(0);
            occurrence.setCreateTime(now);
            if (clubEventOpsMapper.insertOccurrence(occurrence) != 1) throw conflict("系列场次关联失败");
            ClubLeadState leadState = new ClubLeadState();
            leadState.setActivityId(activityId);
            leadState.setLeaderMemberId(leadMemberId);
            leadState.setStatus(1);
            leadState.setCreateTime(now);
            if (clubLeadMapper.insertClubLeadState(leadState) != 1) throw conflict("默认负责人绑定失败");
            bindCanonicalEventLead(actorMemberId, request.getClubId(), access.getOwnerMemberId(),
                    leadMemberId, activityId, series.getId());
        }
        return series;
    }

    private ClubEventSeries exactSeriesReplay(ClubEventSeries replay, String requestFingerprint) {
        if (replay == null || !positive(replay.getId())
                || !requestFingerprint.equals(replay.getRequestFingerprint())) {
            throw conflict("requestId 已用于不同系列配置");
        }
        return replay;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ClubEventSeries closeSeries(Long actorMemberId, String username, ClubEventSeriesRequest request) {
        if (!seriesCloseEnabled) {
            throw new com.chengyinhub.common.exception.ServiceException("系列关闭功能未开放",
                    com.chengyinhub.common.constant.HttpStatus.FORBIDDEN);
        }
        if (!positive(actorMemberId) || request == null || !positive(request.getSeriesId())
                || !positive(request.getClubId())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw badRequest("系列关闭参数不完整");
        }
        // 锁序与 updateFuture 一致:权限 → 系列行锁 → 俱乐部 owner 锁 → 锁后重验权限。
        ClubAccessContext access = requireActivityManage(actorMemberId, request.getClubId());
        ClubEventSeries series = clubEventOpsMapper.selectSeriesByIdForUpdate(request.getSeriesId());
        Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(request.getClubId());
        access = requireActivityManage(actorMemberId, request.getClubId());
        if (!positive(lockedOwner) || !lockedOwner.equals(access.getOwnerMemberId())) throw forbidden();
        if (series == null || !request.getClubId().equals(series.getClubId())) {
            throw conflict("系列不存在或范围不一致");
        }
        if (ClubEventSeries.STATUS_CLOSED.equals(series.getStatus())) {
            return series;
        }
        if (!ClubEventSeries.STATUS_ACTIVE.equals(series.getStatus())
                || !request.getExpectedVersion().equals(series.getVersion())) {
            throw conflict("系列状态或版本已变化，请刷新");
        }
        if (clubEventOpsMapper.closeSeriesCas(series.getId(), series.getClubId(), series.getVersion(),
                username, now()) != 1) {
            throw conflict("系列状态或版本已变化，请刷新");
        }
        series.setStatus(ClubEventSeries.STATUS_CLOSED);
        series.setVersion(series.getVersion() + 1);
        return series;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ClubEventSeries updateFuture(Long actorMemberId, String username, ClubEventSeriesRequest request) {
        if (!positive(actorMemberId) || request == null || !positive(request.getSeriesId())
                || !positive(request.getClubId()) || !positive(request.getTopicId())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            throw badRequest("系列未来编辑参数不完整");
        }
        ClubAccessContext access = requireActivityManage(actorMemberId, request.getClubId());
        ClubEventSeries series = clubEventOpsMapper.selectSeriesByIdForUpdate(request.getSeriesId());
        Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(request.getClubId());
        access = requireActivityManage(actorMemberId, request.getClubId());
        if (!positive(lockedOwner) || !lockedOwner.equals(access.getOwnerMemberId())) throw forbidden();
        if (series == null || !ClubEventSeries.STATUS_ACTIVE.equals(series.getStatus())
                || !request.getClubId().equals(series.getClubId())
                || !request.getTopicId().equals(series.getTopicId())
                || !positive(series.getDefaultLeadMemberId()) || series.getWaitlistEnabled() == null
                || series.getOfferMinutes() == null
                || !request.getExpectedVersion().equals(series.getVersion())) {
            throw conflict("系列不存在、范围不一致或已被更新");
        }
        // 系列级候补/负责人策略会被历史场次读取，FUTURE_ONLY 禁止借重排改写它们。
        Long requestedLead = request.getDefaultLeadMemberId() == null
                ? series.getDefaultLeadMemberId() : request.getDefaultLeadMemberId();
        Boolean requestedWaitlist = request.getWaitlistEnabled() == null
                ? series.getWaitlistEnabled() : request.getWaitlistEnabled();
        Integer requestedOfferMinutes = request.getOfferMinutes() == null
                ? series.getOfferMinutes() : request.getOfferMinutes();
        Integer requestedCapacity = request.getCapacity() == null
                ? series.getDefaultCapacity() : request.getCapacity();
        if (!series.getDefaultLeadMemberId().equals(requestedLead)
                || !series.getWaitlistEnabled().equals(requestedWaitlist)
                || !series.getOfferMinutes().equals(requestedOfferMinutes)
                || !java.util.Objects.equals(series.getDefaultCapacity(), requestedCapacity)) {
            throw badRequest("FUTURE_ONLY 仅支持重排日期；负责人和候补策略请新建系列");
        }
        LocalDate today = todayShanghai();
        // 同创建:未来重排也要带集合时刻 —— 缺省拒绝,别让新增场次又落回 00:00。
        String startTime = requiredStartTime(request);
        List<LocalDate> desiredDates = scheduleMaterializer.materialize(request, today);
        for (LocalDate desiredDate : desiredDates) {
            if (!desiredDate.isAfter(today)) throw badRequest("FUTURE_ONLY 只能编辑明天及之后的场次");
        }
        Map<LocalDate, ClubEventOccurrence> existingByDate = new HashMap<>();
        Date now = new Date();
        List<ClubEventOccurrence> existing = clubEventOpsMapper.selectFutureOccurrencesForUpdate(series.getId(), futureDateBoundary());
        if (existing != null) for (ClubEventOccurrence occurrence : existing) {
            if (occurrence == null || !series.getId().equals(occurrence.getSeriesId())
                    || occurrence.getId() == null || occurrence.getActivityId() == null
                    || occurrence.getOccurrenceAt() == null || occurrence.getVersion() == null
                    || !ClubEventOccurrence.STATUS_ACTIVE.equals(occurrence.getStatus())) {
                throw conflict("未来场次状态不完整");
            }
            LocalDate localDate = occurrence.getOccurrenceAt().toInstant()
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (existingByDate.put(localDate, occurrence) != null) throw conflict("未来场次日期重复");
        }
        Set<LocalDate> desired = new HashSet<>(desiredDates);
        for (Map.Entry<LocalDate, ClubEventOccurrence> entry : existingByDate.entrySet()) {
            if (desired.contains(entry.getKey())) continue;
            ClubEventOccurrence occurrence = entry.getValue();
            if (clubEventOpsMapper.countProtectedActivityHistory(occurrence.getActivityId()) != 0) {
                throw conflict("已有报名或结算事实的未来场次不能移除");
            }
            clubRoleAssignmentService.revokeEventLeadForCancelledOccurrence(
                    actorMemberId, request.getClubId(), occurrence.getActivityId(),
                    "series-cancel-" + series.getId() + "-" + occurrence.getActivityId());
            if (clubEventOpsMapper.cancelFutureActivityCas(occurrence.getActivityId(), now, safeUsername(username, actorMemberId)) != 1
                    || clubEventOpsMapper.cancelOccurrenceCas(occurrence.getId(), occurrence.getVersion()) != 1) {
                throw conflict("未来场次状态已变化，请刷新");
            }
        }
        for (LocalDate date : desiredDates) {
            if (existingByDate.containsKey(date)) continue;
            createOccurrence(series.getId(), request.getClubId(), request.getTopicId(), date, startTime,
                    series.getDefaultLeadMemberId(), series.getDefaultCapacity(), access.getOwnerMemberId(),
                    actorMemberId, safeUsername(username, actorMemberId), now);
        }
        String recurrenceType = request.getRecurrenceType() == null ? ""
                : request.getRecurrenceType().trim().toUpperCase(Locale.ROOT);
        if (clubEventOpsMapper.updateSeriesScheduleCas(series.getId(), series.getVersion(), recurrenceType,
                safeUsername(username, actorMemberId), now) != 1) throw conflict("系列状态已变化，请刷新");
        series.setRecurrenceType(recurrenceType);
        series.setVersion(series.getVersion() + 1);
        series.setUpdateTime(now);
        return series;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ClubEventCancellationResult cancelOccurrence(Long actorMemberId, String username,
                                                         ClubEventCancellationRequest request) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireCancellationOutsideTransaction();
        ClubEventCancellationRequest frozen = freezeCancellationRequest(request);
        PreparedOccurrence prepared = cancellationTransaction(Isolation.READ_COMMITTED, () -> {
            OccurrencePreparation original = prepareOccurrenceCancellation(actorMemberId, frozen);
            return new PreparedOccurrence(original, original.replay() != null ? null
                    : teamGroupService.captureCancellationPayments(2L, frozen.getActivityId()));
        });
        if (prepared.original().replay() != null) return prepared.original().replay();
        RegistrationCancellationPayments observed = teamGroupService.observeCancellationPayments(prepared.payments());
        return cancellationAfterObservation(Isolation.READ_COMMITTED, observed,
                () -> cancelOccurrenceWithObservedPayments(actorMemberId, username, frozen, observed));
    }

    private ClubEventCancellationResult cancelOccurrenceWithObservedPayments(Long actorMemberId, String username,
            ClubEventCancellationRequest request, RegistrationCancellationPayments observed) {
        OccurrencePreparation prepared = prepareOccurrenceCancellation(actorMemberId, request);
        if (prepared.replay() != null) return prepared.replay();
        String reason = prepared.reason();
        String requestId = prepared.requestId();
        Long lockedOwner = prepared.ownerMemberId();
        ClubEventOccurrence occurrence = prepared.occurrence();

        // 必须先快照；真实活动取消会改变报名，候补终止会改变 waitlist eligibility。
        List<Long> registeredRecipients = cancellationRecipients(
                clubEventOpsMapper.selectRegisteredRecipients(request.getClubId(), request.getActivityId()));
        List<Long> waitlistRecipients = cancellationRecipients(
                clubEventOpsMapper.selectWaitlistRecipients(request.getClubId(), request.getActivityId()));

        clubRoleAssignmentService.revokeEventLeadForCancelledOccurrence(
                actorMemberId, request.getClubId(), request.getActivityId(),
                "event-cancel-role-" + requestId);
        ActivityCancelResult refund = teamGroupService.cancelActivityWithObservedPayments(
                request.getActivityId(), lockedOwner, actorMemberId, reason, false, observed);
        if (refund == null || refund.getRefundedOrders() < 0 || refund.getManualOrders() < 0
                || refund.getClosedPendingOrders() < 0) {
            throw conflict("活动取消退款回执不完整");
        }

        ClubEventCancellationResult result = baseCancellationResult(request.getActivityId());
        result.setRefundStatus(refund.getManualOrders() > 0
                ? "ACCEPTED_WITH_MANUAL_REVIEW"
                : (refund.getRefundedOrders() > 0 ? "REFUND_REQUESTED" : "NOT_REQUIRED"));
        result.setRefundedOrders(refund.getRefundedOrders());
        result.setManualOrders(refund.getManualOrders());
        result.setClosedPendingOrders(refund.getClosedPendingOrders());
        finishOccurrenceCancellation(actorMemberId, request.getClubId(), request.getActivityId(), reason,
                occurrence, registeredRecipients, waitlistRecipients, requestId, result);
        return result;
    }

    private record OccurrencePreparation(String reason, String requestId, Long ownerMemberId,
                                          ClubEventOccurrence occurrence, ClubEventCancellationResult replay) { }
    private record PreparedOccurrence(OccurrencePreparation original, RegistrationCancellationPayments payments) { }

    private OccurrencePreparation prepareOccurrenceCancellation(Long actorMemberId, ClubEventCancellationRequest request) {
        if (!positive(actorMemberId) || request == null || !positive(request.getClubId())
                || !positive(request.getActivityId())) {
            throw badRequest("取消场次参数不完整");
        }
        String reason = cancellationReason(request.getReason());
        String requestId = cancellationRequestId(request.getRequestId());
        ClubAccessContext access = requireCancellationAccess(
                actorMemberId, request.getClubId(), request.getActivityId());
        com.chengyinhub.business.domain.CmsActivity observedActivity = lockCancellationTopic(
                request.getActivityId(), null, request.getClubId());

        // [F-36] open-session 开出来的独立场次没有 series/occurrence。此前这里一律 409,
        // 主理人永远取消不了。归属改按「有系列就用系列、没有就问 cms_activity」判 ——
        // 与 F-34 状态查询同一口径,都不把系列当作活动存在的前提。
        ClubEventSeries series = clubEventOpsMapper
                .selectSeriesByActivityAnyStatusForUpdate(request.getActivityId());
        com.chengyinhub.business.domain.CmsActivity currentActivity = RegistrationTopicFence.requireCurrentActivity(
                observedActivity, cancellationActivityMapper);
        requireCancellationActivityScope(currentActivity, request.getClubId(), series);
        boolean standalone = series == null;
        if (standalone) {
            // selectActivityTopicForUpdate 只在「活动属于本俱乐部且未删」时返回,顺带上行锁。
            if (clubEventOpsMapper.selectActivityTopicForUpdate(
                    request.getActivityId(), request.getClubId()) == null) {
                throw conflict("场次不存在或不属于当前俱乐部");
            }
        } else if (!positive(series.getId()) || !request.getClubId().equals(series.getClubId())) {
            throw conflict("场次不存在或不属于当前俱乐部");
        }
        Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(request.getClubId());
        // 权限首次读取到拿到 club 串行锁之间，co-owner 可能已被撤销。READ_COMMITTED 保证
        // 锁后第二次 require 读取的是撤销提交后的事实，而不是本事务第一次读建立的旧快照。
        access = requireCancellationAccess(actorMemberId, request.getClubId(), request.getActivityId());
        if (!positive(lockedOwner) || !lockedOwner.equals(access.getOwnerMemberId())) throw forbidden();
        ClubEventOccurrence occurrence = standalone ? null
                : clubEventOpsMapper.selectOccurrenceByActivityAndClubForUpdate(
                        request.getActivityId(), request.getClubId());
        if (!standalone && !validOccurrenceScope(occurrence, series, request.getActivityId())) {
            throw conflict("场次状态不完整或范围不一致");
        }

        String registeredRequestId = cancellationCampaignRequestId(
                "cancel-r-", actorMemberId, request.getClubId(), request.getActivityId(), requestId);
        String waitlistRequestId = cancellationCampaignRequestId(
                "cancel-w-", actorMemberId, request.getClubId(), request.getActivityId(), requestId);
        String waitlistContent = waitlistCancellationContent(reason);
        // 独立场次没有 occurrence 行可读状态,已取消与否以 cms_activity.cancel_time 为准
        // (selectOccurrenceCancellationState 在无 occurrence 时正是按它给 CANCELLED)。
        boolean alreadyCancelled = standalone
                ? standaloneActivityCancelled(request.getClubId(), request.getActivityId())
                : ClubEventOccurrence.STATUS_CANCELLED.equals(occurrence.getStatus());
        if (alreadyCancelled) {
            requirePersistedCancellationState(request.getClubId(), request.getActivityId());
            return new OccurrencePreparation(reason, requestId, lockedOwner, occurrence,
                    replayCancellation(actorMemberId, request.getClubId(), request.getActivityId(),
                            registeredRequestId, waitlistRequestId, reason, waitlistContent));
        }
        // S531-2:系列关闭(CLOSED)只停排新场次,已排场次仍须能从 event-ops 取消并收尾退款/候补/通知。
        if (!standalone && (!ClubEventOccurrence.STATUS_ACTIVE.equals(occurrence.getStatus())
                || !seriesServesScheduledOccurrences(series.getStatus()))) {
            throw conflict("场次已失效，无法取消");
        }
        if (teamGroupService == null) throw conflict("活动取消服务不可用");


        return new OccurrencePreparation(reason, requestId, lockedOwner, occurrence, null);
    }

    /**
     * [6-17] 两条取消路径共用的收尾:场次 CAS → 候补终止 → 站内取消通知。
     *
     * <p>必须先于本方法完成退款核心与受众快照:这两步的数据依赖调用方准备。
     * 失败一律抛出,由 {@code @Transactional} 整体回滚,不留「活动已取消、候补还在等」的夹层。
     *
     * @param receiptOrNull 需要回执时传入结果对象,通知单号写回它;不需要时传 null
     */
    private void finishOccurrenceCancellation(Long actorMemberId, Long clubId, Long activityId, String reason,
                                              ClubEventOccurrence occurrence,
                                              List<Long> registeredRecipients, List<Long> waitlistRecipients,
                                              String requestIdSeed, ClubEventCancellationResult receiptOrNull) {
        // 独立场次没有 occurrence 行:真正落「已取消」的是退款服务写的
        // cms_activity.cancel_time + status=0,那一步已在本事务内完成。
        if (occurrence != null
                && clubEventOpsMapper.cancelOccurrenceCas(occurrence.getId(), occurrence.getVersion()) != 1) {
            throw conflict("场次状态已变化，请刷新后重试");
        }
        terminateWaitlists(clubId, activityId);

        Date now = new Date();
        String registeredRequestId = cancellationCampaignRequestId(
                "cancel-r-", actorMemberId, clubId, activityId, requestIdSeed);
        String waitlistRequestId = cancellationCampaignRequestId(
                "cancel-w-", actorMemberId, clubId, activityId, requestIdSeed);
        ClubEventCampaign registeredCampaign = enqueueCancellationCampaign(
                actorMemberId, clubId, activityId, "REGISTERED",
                registeredRequestId, registeredCancellationContent(reason), registeredRecipients, now);
        ClubEventCampaign waitlistCampaign = enqueueCancellationCampaign(
                actorMemberId, clubId, activityId, "WAITLIST",
                waitlistRequestId, waitlistCancellationContent(reason), waitlistRecipients, now);
        if (receiptOrNull != null) {
            applyCampaignReceipt(receiptOrNull, registeredCampaign, true);
            applyCampaignReceipt(receiptOrNull, waitlistCampaign, false);
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ActivityCancelResult cancelActivityByOwnerWithClubWrapUp(Long actorMemberId, Long activityId,
                                                                     String reason) {
        return cancelWrapperOutsideTransaction(actorMemberId, null, null, activityId, reason, false, Isolation.READ_COMMITTED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ActivityCancelResult cancelActivityForAuthorizedTopicCancellation(
            Long actorMemberId, Long topicId, Long clubId, Long activityId, String reason) {
        if (!positive(topicId) || !positive(clubId)) throw badRequest("主题取消范围不完整");
        return cancelWrapperOutsideTransaction(actorMemberId, topicId, clubId, activityId, reason, true, Isolation.DEFAULT);
    }

    private ActivityCancelResult cancelActivityWithClubWrapUp(Long actorMemberId, Long authorizedTopicId,
                                                               Long authorizedClubId, Long activityId,
                                                               String reason,
                                                               boolean authorizedTopicCancellation, RegistrationCancellationPayments observed) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        WrapperPreparation prepared = prepareWrapperCancellation(actorMemberId, authorizedTopicId,
                authorizedClubId, activityId, reason, authorizedTopicCancellation);
        ClubEventSeries series = prepared.series();
        ClubEventOccurrence occurrence = prepared.occurrence();
        Long clubId = prepared.clubId();
        Long ownerMemberId = prepared.ownerMemberId();
        String normalizedReason = prepared.reason();
        // 受众快照必须先于退款核心:退款会改变报名状态,候补终止会改变 waitlist eligibility。
        List<Long> registeredRecipients = clubId == null ? null
                : cancellationRecipients(clubEventOpsMapper.selectRegisteredRecipients(clubId, activityId));
        List<Long> waitlistRecipients = clubId == null ? null
                : cancellationRecipients(clubEventOpsMapper.selectWaitlistRecipients(clubId, activityId));
        Long effectiveActorMemberId = prepared.effectiveActorMemberId();

        if (occurrence != null) {
            clubRoleAssignmentService.revokeEventLeadForCancelledOccurrence(
                    effectiveActorMemberId, clubId, activityId,
                    (authorizedTopicCancellation ? "topic-cancel-role-" : "api-activity-cancel-role-") + activityId);
        }
        ActivityCancelResult refund = teamGroupService.cancelActivityWithObservedPayments(
                activityId, series == null && !authorizedTopicCancellation ? actorMemberId : ownerMemberId,
                effectiveActorMemberId, normalizedReason, false, observed);
        if (refund == null || refund.getRefundedOrders() < 0 || refund.getManualOrders() < 0
                || refund.getClosedPendingOrders() < 0) {
            throw conflict("活动取消退款回执不完整");
        }
        if (occurrence != null) {
            finishOccurrenceCancellation(effectiveActorMemberId, clubId, activityId, normalizedReason,
                    occurrence, registeredRecipients, waitlistRecipients, "api-activity-cancel", null);
        }
        return refund;
    }

    private static void requireCancellationOutsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("取消支付观察必须先在原业务事务外完成，不能悬挂持锁事务");
    }

    private <T> T cancellationTransaction(Isolation isolation, java.util.function.Supplier<T> work) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(cancellationTransactions);
        transaction.setIsolationLevel(isolation.value());
        return transaction.execute(status -> work.get());
    }

    private <T> T cancellationAfterObservation(Isolation isolation, RegistrationCancellationPayments observed,
                                                java.util.function.Supplier<T> work) {
        try {
            return cancellationTransaction(isolation, work);
        } catch (RuntimeException failure) {
            boolean closeIssued = observed.scopes().stream().flatMap(scope -> scope.orders().stream())
                    .anyMatch(order -> order.confirmedClosed()
                            && !"CLOSED".equalsIgnoreCase(order.providerFacts().get("trade_state")));
            if (!closeIssued) throw failure;
            com.chengyinhub.common.exception.ServiceException uncertain =
                    conflict("原支付已关闭，本地取消尚未确认，请按原订单重试或人工核对")
                            .setDetailMessage("CANCELLATION_APPLY_AFTER_CONFIRMED_CLOSE");
            uncertain.initCause(failure);
            throw uncertain;
        }
    }

    private static void requireCancellationTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("取消支付业务应用必须加入原范围事务");
    }

    private ClubEventCancellationRequest freezeCancellationRequest(ClubEventCancellationRequest request) {
        if (request == null) throw badRequest("取消场次参数不完整");
        ClubEventCancellationRequest frozen = new ClubEventCancellationRequest();
        frozen.setClubId(request.getClubId()); frozen.setActivityId(request.getActivityId());
        frozen.setReason(request.getReason()); frozen.setRequestId(request.getRequestId());
        return frozen;
    }

    private ActivityCancelResult cancelWrapperOutsideTransaction(Long actorMemberId, Long topicId, Long clubId,
            Long activityId, String reason, boolean authorizedTopicCancellation, Isolation isolation) {
        requireCancellationOutsideTransaction();
        RegistrationCancellationPayments captured = cancellationTransaction(isolation, () -> {
            prepareWrapperCancellation(actorMemberId, topicId, clubId, activityId, reason, authorizedTopicCancellation);
            return teamGroupService.captureCancellationPayments(2L, activityId);
        });
        RegistrationCancellationPayments observed = teamGroupService.observeCancellationPayments(captured);
        return cancellationAfterObservation(isolation, observed, () -> cancelActivityWithClubWrapUp(actorMemberId, topicId,
                clubId, activityId, reason, authorizedTopicCancellation, observed));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RegistrationCancellationPayments captureAuthorizedTopicCancellationPayments(Long actorMemberId,
            Long topicId, Long clubId, Long activityId, String reason) {
        requireCancellationTransaction();
        if (!positive(topicId) || !positive(clubId)) throw badRequest("主题取消范围不完整");
        prepareWrapperCancellation(actorMemberId, topicId, clubId, activityId, reason, true);
        return teamGroupService.captureCancellationPayments(2L, activityId);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public ActivityCancelResult cancelAuthorizedTopicWithObservedPayments(Long actorMemberId, Long topicId,
            Long clubId, Long activityId, String reason, RegistrationCancellationPayments observed) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireCancellationTransaction();
        if (!positive(topicId) || !positive(clubId)) throw badRequest("主题取消范围不完整");
        return cancelActivityWithClubWrapUp(actorMemberId, topicId, clubId, activityId, reason, true, observed);
    }

    private record WrapperPreparation(ClubEventSeries series, ClubEventOccurrence occurrence, Long clubId,
                                      Long ownerMemberId, Long effectiveActorMemberId, String reason) { }

    private WrapperPreparation prepareWrapperCancellation(Long actorMemberId, Long authorizedTopicId,
            Long authorizedClubId, Long activityId, String reason, boolean authorizedTopicCancellation) {
        if (!positive(actorMemberId) || !positive(activityId)) throw badRequest("活动取消参数不完整");
        if (teamGroupService == null) throw conflict("活动取消服务不可用");
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty()) throw badRequest("请填写取消原因");

        com.chengyinhub.business.domain.CmsActivity observedActivity = lockCancellationTopic(activityId,
                authorizedTopicCancellation ? authorizedTopicId : null,
                authorizedTopicCancellation ? authorizedClubId : null);

        ClubEventSeries series = clubEventOpsMapper.selectSeriesByActivityAnyStatusForUpdate(activityId);
        com.chengyinhub.business.domain.CmsActivity currentActivity = RegistrationTopicFence.requireCurrentActivity(
                observedActivity, cancellationActivityMapper);
        requireCancellationActivityScope(currentActivity, authorizedTopicCancellation ? authorizedClubId : null, series);
        ClubEventOccurrence occurrence = null;
        Long clubId = null;
        Long ownerMemberId = actorMemberId;
        if (series != null) {
            if (!positive(series.getId()) || !positive(series.getClubId())) {
                throw conflict("场次状态不完整，请刷新后重试");
            }
            clubId = series.getClubId();
            if (authorizedTopicCancellation && (!clubId.equals(authorizedClubId)
                    || !authorizedTopicId.equals(series.getTopicId()))) {
                throw conflict("场次不属于已授权主题或俱乐部");
            }
            occurrence = clubEventOpsMapper.selectOccurrenceByActivityAndClubForUpdate(activityId, clubId);
            if (!validOccurrenceScope(occurrence, series, activityId)) {
                throw conflict("场次状态不完整或范围不一致");
            }
            ClubAccessContext access = authorizedTopicCancellation ? null
                    : requireCancellationAccess(actorMemberId, clubId, activityId);
            Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(clubId);
            if (!authorizedTopicCancellation) {
                access = requireCancellationAccess(actorMemberId, clubId, activityId);
            }
            if (!positive(lockedOwner) || (!authorizedTopicCancellation
                    && !lockedOwner.equals(access.getOwnerMemberId()))) throw forbidden();
            ownerMemberId = lockedOwner;
            if (ClubEventOccurrence.STATUS_CANCELLED.equals(occurrence.getStatus())) {
                // 已被 event-ops 收尾过:不重复终止候补/发通知,这里只补退款。
                occurrence = null;
            }
        } else if (authorizedTopicCancellation) {
            clubId = authorizedClubId;
            Long boundTopicId = clubEventOpsMapper.selectActivityTopicForUpdate(activityId, clubId);
            if (!authorizedTopicId.equals(boundTopicId)) throw conflict("场次不属于已授权主题或俱乐部");
            Long lockedOwner = clubEventOpsMapper.selectClubOwnerForUpdate(clubId);
            if (!positive(lockedOwner)) throw forbidden();
            ownerMemberId = lockedOwner;
        }


        return new WrapperPreparation(series, occurrence, clubId, ownerMemberId,
                authorizedTopicCancellation ? ownerMemberId : actorMemberId, normalizedReason);
    }

    private com.chengyinhub.business.domain.CmsActivity lockCancellationTopic(
            Long activityId, Long expectedTopicId, Long expectedClubId) {
        com.chengyinhub.business.domain.CmsActivity observed = cancellationActivityMapper.selectCmsActivityById(activityId);
        if (observed == null || !activityId.equals(observed.getId())
                || (expectedTopicId != null && !expectedTopicId.equals(observed.getTopicId()))
                || (expectedClubId != null && !expectedClubId.equals(observed.getClubId())))
            throw conflict("场次不属于原主题或俱乐部");
        RegistrationTopicFence.lockActivityTopic(observed, cancellationTopicMapper);
        return observed;
    }

    private void requireCancellationActivityScope(com.chengyinhub.business.domain.CmsActivity current,
                                                   Long expectedClubId, ClubEventSeries series) {
        if ((expectedClubId != null && !expectedClubId.equals(current.getClubId()))
                || (series != null && (!java.util.Objects.equals(series.getClubId(), current.getClubId())
                    || !java.util.Objects.equals(series.getTopicId(), current.getTopicId()))))
            throw conflict("场次当前主题或俱乐部与原系列不一致");
    }

    @Override
    public ClubEventCancellationStateVO getCancellationState(Long actorMemberId, Long clubId,
                                                              Long activityId) {
        requireCancellationAccess(actorMemberId, clubId, activityId);
        ClubEventCancellationStateVO state = clubEventOpsMapper
                .selectOccurrenceCancellationState(activityId, clubId);
        if (state == null || !clubId.equals(state.getClubId()) || !activityId.equals(state.getActivityId())
                || state.getPublishStatus() == null
                || !(Long.valueOf(0L).equals(state.getPublishStatus())
                || Long.valueOf(1L).equals(state.getPublishStatus()))) {
            throw conflict("场次取消状态无法确认");
        }
        if (ClubEventOccurrence.STATUS_CANCELLED.equals(state.getOccurrenceStatus())) {
            if (!state.isActivityCancelled() || !Long.valueOf(0L).equals(state.getPublishStatus())) {
                throw conflict("场次与活动取消状态不一致");
            }
        } else if (ClubEventOccurrence.STATUS_ACTIVE.equals(state.getOccurrenceStatus())) {
            if (state.isActivityCancelled() || !Long.valueOf(1L).equals(state.getPublishStatus())) {
                throw conflict("场次与活动取消状态不一致");
            }
        } else {
            throw conflict("场次取消状态无法确认");
        }
        return state;
    }

    private ClubAccessContext requireCancellationAccess(Long actorMemberId, Long clubId, Long activityId) {
        if (!positive(actorMemberId) || !positive(clubId) || !positive(activityId)) {
            throw badRequest("场次范围不完整");
        }
        ClubAccessContext access = clubAccessService.require(
                actorMemberId, clubId, activityId, ClubPermission.ACTIVITY_MANAGE);
        boolean privilegedRole = access != null && (access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)
                || access.getRoleCodes().contains(ClubRoleCode.CLUB_CO_OWNER));
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId()) || !activityId.equals(access.getActivityId())
                || !positive(access.getOwnerMemberId())
                || !access.hasPermission(ClubPermission.ACTIVITY_MANAGE) || !privilegedRole) {
            throw forbidden();
        }
        return access;
    }

    private boolean validOccurrenceScope(ClubEventOccurrence occurrence, ClubEventSeries series,
                                         Long activityId) {
        return occurrence != null && positive(occurrence.getId())
                && series.getId().equals(occurrence.getSeriesId())
                && activityId.equals(occurrence.getActivityId())
                && occurrence.getVersion() != null && occurrence.getVersion() >= 0
                && (ClubEventOccurrence.STATUS_ACTIVE.equals(occurrence.getStatus())
                || ClubEventOccurrence.STATUS_CANCELLED.equals(occurrence.getStatus()));
    }

    private List<Long> cancellationRecipients(List<Long> raw) {
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        if (raw != null) for (Long memberId : raw) {
            if (!positive(memberId)) throw conflict("取消通知受众存在无效成员");
            unique.add(memberId);
        }
        return new ArrayList<>(unique);
    }

    private void terminateWaitlists(Long clubId, Long activityId) {
        List<ClubWaitlistEntry> rows = clubEventOpsMapper
                .selectActiveWaitlistsForUpdate(clubId, activityId);
        if (rows == null) return;
        for (ClubWaitlistEntry row : rows) {
            if (row == null || !positive(row.getId()) || !clubId.equals(row.getClubId())
                    || !activityId.equals(row.getActivityId()) || !positive(row.getTicketId())
                    || !positive(row.getMemberId()) || row.getVersion() == null || row.getVersion() < 0
                    || !(ClubWaitlistEntry.WAITING.equals(row.getState())
                    || ClubWaitlistEntry.OFFERED.equals(row.getState())
                    || ClubWaitlistEntry.CLAIMED.equals(row.getState()))) {
                throw conflict("候补状态不完整，请刷新后重试");
            }
            if (clubEventOpsMapper.cancelActiveWaitlistCas(row.getId(), row.getVersion(), row.getState(),
                    row.getMemberId(), activityId, row.getTicketId()) != 1) {
                throw conflict("候补状态已变化，请刷新后重试");
            }
            boolean mustRestoreHold = ClubWaitlistEntry.OFFERED.equals(row.getState())
                    || (ClubWaitlistEntry.CLAIMED.equals(row.getState()) && row.getRegistrationId() == null);
            if (mustRestoreHold
                    && clubEventOpsMapper.restoreWaitlistHold(row.getTicketId(), activityId) != 1) {
                throw conflict("候补预留名额释放失败，请刷新后重试");
            }
        }
    }

    private ClubEventCampaign enqueueCancellationCampaign(Long actorMemberId, Long clubId, Long activityId,
                                                           String audience, String requestId, String content,
                                                           List<Long> recipients, Date now) {
        ClubEventCampaign campaign = new ClubEventCampaign();
        campaign.setClubId(clubId);
        campaign.setActivityId(activityId);
        campaign.setAudienceType(audience);
        campaign.setChannel("IN_APP");
        campaign.setTitle("场次取消通知");
        campaign.setContent(content);
        campaign.setStatus(recipients.isEmpty()
                ? ClubEventCampaign.COMPLETED : ClubEventCampaign.PROCESSING);
        campaign.setActorMemberId(actorMemberId);
        campaign.setRequestId(requestId);
        campaign.setTotalCount(recipients.size());
        campaign.setSuccessCount(0);
        campaign.setFailedCount(0);
        campaign.setCreateTime(now);
        campaign.setUpdateTime(now);
        if (clubEventOpsMapper.insertCampaign(campaign) != 1 || !positive(campaign.getId())) {
            throw conflict("取消通知任务回执创建失败");
        }
        for (Long recipient : recipients) {
            ClubEventDelivery delivery = new ClubEventDelivery();
            delivery.setCampaignId(campaign.getId());
            delivery.setRecipientMemberId(recipient);
            delivery.setChannel("IN_APP");
            delivery.setStatus(ClubEventDelivery.PENDING);
            delivery.setAttemptCount(0);
            delivery.setVersion(0);
            if (clubEventOpsMapper.insertDelivery(delivery) != 1 || !positive(delivery.getId())
                    || clubEventOpsMapper.insertOutbox(delivery.getId(), now) != 1) {
                throw conflict("取消通知收件回执创建失败");
            }
        }
        return campaign;
    }

    private ClubEventCancellationResult replayCancellation(Long actorMemberId, Long clubId, Long activityId,
                                                            String registeredRequestId, String waitlistRequestId,
                                                            String reason, String waitlistContent) {
        ClubEventCampaign registered = clubEventOpsMapper.selectCampaignByRequest(
                clubId, actorMemberId, registeredRequestId);
        ClubEventCampaign waitlist = clubEventOpsMapper.selectCampaignByRequest(
                clubId, actorMemberId, waitlistRequestId);
        String registeredContent = registeredCancellationContent(reason);
        // 旧通知已持久化；同原因重放兼容旧文案，不重发通知或重走退款。
        if (registered != null && ("活动已取消：" + reason
                + "。退款任务已提交处理，请以订单退款状态为准。").equals(registered.getContent())) {
            registeredContent = registered.getContent();
        }
        validateCancellationCampaign(registered, clubId, activityId, actorMemberId,
                "REGISTERED", registeredRequestId, registeredContent);
        validateCancellationCampaign(waitlist, clubId, activityId, actorMemberId,
                "WAITLIST", waitlistRequestId, waitlistContent);
        ClubEventCancellationResult result = baseCancellationResult(activityId);
        result.setRefundStatus("ALREADY_ACCEPTED");
        applyCampaignReceipt(result, registered, true);
        applyCampaignReceipt(result, waitlist, false);
        return result;
    }

    /** 独立场次的「是否已取消」:只认 cms_activity 真源,不假设 occurrence 存在。 */
    private boolean standaloneActivityCancelled(Long clubId, Long activityId) {
        ClubEventCancellationStateVO state = clubEventOpsMapper
                .selectOccurrenceCancellationState(activityId, clubId);
        if (state == null || !clubId.equals(state.getClubId())
                || !activityId.equals(state.getActivityId())) {
            throw conflict("场次不存在或不属于当前俱乐部");
        }
        return state.isActivityCancelled()
                || ClubEventOccurrence.STATUS_CANCELLED.equals(state.getOccurrenceStatus());
    }

    private void requirePersistedCancellationState(Long clubId, Long activityId) {
        ClubEventCancellationStateVO state = clubEventOpsMapper
                .selectOccurrenceCancellationState(activityId, clubId);
        if (state == null || !clubId.equals(state.getClubId()) || !activityId.equals(state.getActivityId())
                || !ClubEventOccurrence.STATUS_CANCELLED.equals(state.getOccurrenceStatus())
                || !state.isActivityCancelled() || !Long.valueOf(0L).equals(state.getPublishStatus())) {
            throw conflict("场次与活动取消状态不一致");
        }
    }

    private void validateCancellationCampaign(ClubEventCampaign campaign, Long clubId, Long activityId,
                                              Long actorMemberId, String audience, String requestId,
                                              String content) {
        if (campaign == null || !positive(campaign.getId()) || !clubId.equals(campaign.getClubId())
                || !activityId.equals(campaign.getActivityId()) || !audience.equals(campaign.getAudienceType())
                || !"IN_APP".equals(campaign.getChannel()) || !"场次取消通知".equals(campaign.getTitle())
                || !content.equals(campaign.getContent()) || !actorMemberId.equals(campaign.getActorMemberId())
                || !requestId.equals(campaign.getRequestId()) || campaign.getTotalCount() == null
                || campaign.getTotalCount() < 0 || campaign.getSuccessCount() == null
                || campaign.getFailedCount() == null) {
            throw conflict("取消回执不存在或 requestId 已用于不同取消请求");
        }
    }

    private ClubEventCancellationResult baseCancellationResult(Long activityId) {
        ClubEventCancellationResult result = new ClubEventCancellationResult();
        result.setActivityId(activityId);
        result.setOccurrenceStatus(ClubEventOccurrence.STATUS_CANCELLED);
        return result;
    }

    private void applyCampaignReceipt(ClubEventCancellationResult result, ClubEventCampaign campaign,
                                      boolean registered) {
        String status = campaign.getTotalCount() == 0 ? "NOT_REQUIRED"
                : (ClubEventCampaign.COMPLETED.equals(campaign.getStatus()) ? "COMPLETED"
                : (ClubEventCampaign.PARTIAL_FAILED.equals(campaign.getStatus())
                ? "PARTIAL_FAILED" : "QUEUED"));
        if (registered) {
            result.setRegisteredCampaignId(campaign.getId());
            result.setRegisteredRecipientCount(campaign.getTotalCount());
            result.setRegisteredNotificationStatus(status);
        } else {
            result.setWaitlistCampaignId(campaign.getId());
            result.setWaitlistRecipientCount(campaign.getTotalCount());
            result.setWaitlistNotificationStatus(status);
        }
    }

    private String registeredCancellationContent(String reason) {
        return "活动已取消：" + reason + "。报名与款项处理请查看订单状态。";
    }

    private String waitlistCancellationContent(String reason) {
        return "活动已取消：" + reason + "。候补已终止，预留名额已释放，无需继续等待。";
    }

    private String cancellationReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 2 || value.length() > 255) throw badRequest("取消原因需为 2 至 255 字");
        return value;
    }

    private String cancellationRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.isEmpty() || value.length() > 40) throw badRequest("requestId 需为 1 至 40 字");
        return value;
    }

    private String cancellationCampaignRequestId(String prefix, Long actorMemberId, Long clubId,
                                                  Long activityId, String requestId) {
        String canonical = actorMemberId + "|" + clubId + "|" + activityId + "|" + requestId;
        return prefix + sha256(canonical).substring(0, 48);
    }

    private void createOccurrence(Long seriesId, Long clubId, Long topicId, LocalDate date, String startTime,
                                  Long leadMemberId, Integer capacity, Long ownerMemberId,
                                  Long actorMemberId, String username, Date now) {
        ClubSessionOpenRequest open = new ClubSessionOpenRequest();
        open.setClubId(clubId);
        open.setTopicId(topicId);
        open.setSessionDate(assemblyMoment(date, startTime));
        open.setSessionTime(startTime);
        Long activityId = clubSessionService.open(open, ownerMemberId, username);
        if (!positive(activityId)) throw conflict("系列场次创建失败");
        applyCapacity(activityId, capacity);
        ClubEventOccurrence occurrence = new ClubEventOccurrence();
        occurrence.setSeriesId(seriesId);
        occurrence.setActivityId(activityId);
        occurrence.setOccurrenceAt(open.getSessionDate());
        occurrence.setDefaultLeadMemberId(leadMemberId);
        occurrence.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        occurrence.setVersion(0);
        occurrence.setCreateTime(now);
        if (clubEventOpsMapper.insertOccurrence(occurrence) != 1) throw conflict("系列场次关联失败");
        ClubLeadState leadState = new ClubLeadState();
        leadState.setActivityId(activityId);
        leadState.setLeaderMemberId(leadMemberId);
        leadState.setStatus(1);
        leadState.setCreateTime(now);
        if (clubLeadMapper.insertClubLeadState(leadState) != 1) throw conflict("默认负责人绑定失败");
        bindCanonicalEventLead(actorMemberId, clubId, ownerMemberId,
                leadMemberId, activityId, seriesId);
    }

    private void bindCanonicalEventLead(Long actorMemberId, Long clubId, Long ownerMemberId,
                                        Long leadMemberId, Long activityId, Long seriesId) {
        // Owner 的全量权限由 ClubAccessService 派生；普通成员必须写入唯一的角色真源，
        // 否则 club_lead_state 虽显示为负责人，名册/通知/签到仍会 403。
        if (ownerMemberId.equals(leadMemberId)) return;
        clubRoleAssignmentService.assignEventLeadForOccurrence(
                actorMemberId, clubId, leadMemberId, activityId,
                "series-lead-" + seriesId + "-" + activityId);
    }

    private void applyCapacity(Long activityId, Integer capacity) {
        if (capacity == null) return;
        List<Long> ticketIds = clubEventOpsMapper.selectActivityTicketIdsForUpdate(activityId);
        if (ticketIds == null || ticketIds.size() != 1 || !positive(ticketIds.get(0))) {
            throw badRequest("自定义容量仅支持恰有一个票种的合作主题");
        }
        if (clubEventOpsMapper.updateUnsoldActivityTicketCapacity(ticketIds.get(0), activityId, capacity) != 1) {
            throw conflict("活动票容量已变化，请刷新");
        }
    }

    private ClubAccessContext requireActivityManage(Long actorMemberId, Long clubId) {
        if (!positive(actorMemberId) || !positive(clubId)) throw badRequest("俱乐部参数不完整");
        ClubAccessContext access = clubAccessService.require(actorMemberId, clubId, null,
                ClubPermission.ACTIVITY_MANAGE);
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId()) || !positive(access.getOwnerMemberId())
                || !access.hasPermission(ClubPermission.ACTIVITY_MANAGE)
                || !(access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)
                || access.getRoleCodes().contains(ClubRoleCode.CLUB_CO_OWNER))) {
            throw forbidden();
        }
        return access;
    }

    /** S531-2:ACTIVE 与 CLOSED 系列的已排场次都仍在服务(可读、可取消、候补可收尾);只有 ACTIVE 能排新场次。 */
    static boolean seriesServesScheduledOccurrences(String status) {
        return ClubEventSeries.STATUS_ACTIVE.equals(status) || ClubEventSeries.STATUS_CLOSED.equals(status);
    }

    private void validateReadableSeries(ClubEventSeries series, Long clubId) {
        if (series == null || !positive(series.getId()) || !clubId.equals(series.getClubId())
                || !positive(series.getTopicId()) || !seriesServesScheduledOccurrences(series.getStatus())
                || series.getVersion() == null || series.getVersion() < 0
                || !positive(series.getDefaultLeadMemberId()) || series.getWaitlistEnabled() == null
                || series.getOfferMinutes() == null) {
            throw conflict("系列状态不完整或已失效");
        }
    }

    private ClubEventSeriesView toSeriesView(ClubEventSeries series, List<String> futureDates,
                                             List<ClubEventOccurrence> futureOccurrences) {
        ClubEventSeriesView view = new ClubEventSeriesView();
        view.setId(series.getId());
        view.setClubId(series.getClubId());
        view.setTopicId(series.getTopicId());
        view.setRecurrenceType(series.getRecurrenceType());
        view.setTimezone(series.getTimezone());
        view.setStartTime(seriesStartTime(series.getId(), futureOccurrences));
        view.setDefaultLeadMemberId(series.getDefaultLeadMemberId());
        view.setDefaultCapacity(series.getDefaultCapacity());
        view.setWaitlistEnabled(series.getWaitlistEnabled());
        view.setOfferMinutes(series.getOfferMinutes());
        view.setStatus(series.getStatus());
        view.setVersion(series.getVersion());
        view.setFutureDates(futureDates);
        return view;
    }

    /** 系列未另存默认时刻；只从现有未来场次的上海时刻还原，空/混合时刻不能猜成 09:00。 */
    private String seriesStartTime(Long seriesId, List<ClubEventOccurrence> occurrences) {
        String shared = null;
        if (occurrences == null) return null;
        for (ClubEventOccurrence row : occurrences) {
            if (row == null || !seriesId.equals(row.getSeriesId()) || !positive(row.getActivityId())
                    || row.getOccurrenceAt() == null
                    || !ClubEventOccurrence.STATUS_ACTIVE.equals(row.getStatus())) {
                throw conflict("未来场次状态不完整");
            }
            String time = row.getOccurrenceAt().toInstant().atZone(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            if (shared != null && !shared.equals(time)) return null;
            shared = time;
        }
        return shared;
    }

    private String safeUsername(String username, Long actorMemberId) {
        return username == null || username.trim().isEmpty() ? String.valueOf(actorMemberId) : username;
    }

    private void requireActiveLead(Long clubId, Long ownerMemberId, Long leadMemberId) {
        if (ownerMemberId.equals(leadMemberId)) return;
        ClubMember member = clubMemberMapper.selectByClubAndMember(clubId, leadMemberId);
        if (member == null || member.getId() == null || !clubId.equals(member.getClubId())
                || !leadMemberId.equals(member.getMemberId()) || !Integer.valueOf(1).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getDelFlag())) throw badRequest("默认负责人不是本俱乐部正常成员");
    }

    private String requiredRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.isEmpty() || value.length() > 64) throw badRequest("requestId 需为 1 至 64 字");
        return value;
    }

    private String seriesFingerprint(Long actorMemberId, Long clubId, Long topicId, String recurrenceType,
                                     List<LocalDate> dates, String startTime, Long leadMemberId, Integer capacity,
                                     boolean waitlistEnabled, int offerMinutes) {
        String canonical = actorMemberId + "|" + clubId + "|" + topicId + "|" + recurrenceType
                + "|" + dates + "|" + startTime + "|" + leadMemberId + "|" + capacity + "|" + waitlistEnabled
                + "|" + offerMinutes;
        return sha256(canonical);
    }

    /**
     * 物化场次的集合绝对时刻(Asia/Shanghai)。occurrence_at 与 activity.start_date 都用它 ——
     * 两个字段必须是同一个值,否则场次列表显示 00:00、退款钟却按另一个时刻算。
     */
    private Date futureDateBoundary() {
        return Date.from(todayShanghai().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private Date assemblyMoment(LocalDate date, String startTime) {
        LocalTime time = LocalTime.parse(startTime, ASSEMBLY_TIME_FORMAT);
        return Date.from(LocalDateTime.of(date, time).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    /** 集合时刻唯一入参口径:缺省或非 HH:mm 一律拒,不允许静默落 00:00。 */
    private String requiredStartTime(ClubEventSeriesRequest request) {
        String raw = request.getStartTime() == null ? "" : request.getStartTime().trim();
        if (raw.isEmpty()) throw badRequest("请选择集合时刻(HH:mm)");
        try {
            LocalTime.parse(raw, ASSEMBLY_TIME_FORMAT);
        } catch (java.time.DateTimeException malformed) {
            throw badRequest("集合时刻格式应为 HH:mm,实际:" + raw);
        }
        return raw;
    }

    /** 排期基准日与集合时刻校验共用的钟:上海日历 / 上海当前时刻。
     *  测试可覆盖(与 ClubEventAttendanceServiceImpl.now() 同一手法)。 */
    protected Date now() { return new Date(); }

    protected LocalDate todayShanghai() {
        return now().toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    /**
     * CU-C-35:物化器只比「日期」不比「时刻」—— 日期是上海今天而集合时刻已经过去时,
     * 表单上写的就是今天,后端却回一句「场次日期不能早于今天」:用户看到的日期没错,
     * 错的是时刻。这里按同一个上海钟在创建之前拦下,文案直说时刻。
     * (updateFuture 要求所有日期在明天之后,不存在这一档。)
     */
    private void requireAssemblyNotPast(List<LocalDate> dates, String startTime) {
        LocalDate today = todayShanghai();
        if (dates == null || !dates.contains(today)) return;
        if (!assemblyMoment(today, startTime).after(now())) {
            throw badRequest("集合时间必须晚于当前时间");
        }
    }

    private String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private com.chengyinhub.common.exception.ServiceException badRequest(String message) {
        return new com.chengyinhub.common.exception.ServiceException(message,
                com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST);
    }
    private com.chengyinhub.common.exception.ServiceException forbidden() {
        return new com.chengyinhub.common.exception.ServiceException("俱乐部身份无效或权限不足",
                com.chengyinhub.common.constant.HttpStatus.FORBIDDEN);
    }
    private com.chengyinhub.common.exception.ServiceException conflict(String message) {
        return new com.chengyinhub.common.exception.ServiceException(message,
                com.chengyinhub.common.constant.HttpStatus.CONFLICT);
    }
}
