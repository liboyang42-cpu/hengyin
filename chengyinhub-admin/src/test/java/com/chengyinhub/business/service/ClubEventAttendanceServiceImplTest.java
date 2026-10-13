package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventAttendanceCorrection;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.ClubEventAttendanceServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ClubEventAttendanceServiceImplTest {
    private static final Long ACTOR = 41L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 31L;
    private static final Date NOW = new Date(1_800_000_000_000L);
    private IClubAccessService access;
    private ClubEventOpsMapper mapper;
    private CmsActivityMapper activityMapper;
    private ClubEventAttendanceServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IClubAccessService.class);
        mapper = mock(ClubEventOpsMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new ClubEventAttendanceServiceImpl(access, mapper, activityMapper) {
            @Override protected Date now() { return NOW; }
        };
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_CHECKIN))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, 99L, ACTIVITY,
                        Collections.singleton(ClubRoleCode.EVENT_LEAD)));
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStartDate(new Date(NOW.getTime() - 3_600_000L));
        activity.setEndDate(new Date(NOW.getTime() - 60_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    @Test
    void eventLeadSeesOnlyEventRosterSplitIntoFourServerSegments() {
        Map<String, Object> arrived = row(51L, "已到", NOW, null, 0);
        Map<String, Object> noShow = row(52L, "未到", null, null, 0);
        Map<String, Object> waiting = new HashMap<>();
        waiting.put("memberId", 53L);
        waiting.put("nickname", "候补");
        waiting.put("state", "WAITING");
        when(mapper.selectPaidRoster(ACTIVITY)).thenReturn(Arrays.asList(arrived, noShow));
        when(mapper.selectWaitlistRoster(ACTIVITY)).thenReturn(Collections.singletonList(waiting));

        Map<String, Object> roster = service.roster(ACTOR, CLUB, ACTIVITY);

        assertEquals(1, ((List<?>) roster.get("arrived")).size());
        assertEquals(1, ((List<?>) roster.get("noShow")).size());
        assertEquals(1, ((List<?>) roster.get("waitlist")).size());
        assertEquals(0, ((List<?>) roster.get("registered")).size());
        verify(access).require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_CHECKIN);
    }

    @Test
    void jdbcLocalDateTimeCheckinIsReturnedAsArrived() {
        LocalDateTime arrivedAt = LocalDateTime.of(2026, 9, 22, 8, 3, 51);
        when(mapper.selectPaidRoster(ACTIVITY)).thenReturn(Collections.singletonList(
                row(51L, "已到", arrivedAt, null, 0)));
        when(mapper.selectWaitlistRoster(ACTIVITY)).thenReturn(Collections.emptyList());

        Map<String, Object> roster = service.roster(ACTOR, CLUB, ACTIVITY);

        List<?> arrived = (List<?>) roster.get("arrived");
        assertEquals(1, arrived.size());
        assertEquals(Date.from(arrivedAt.atZone(ZoneId.systemDefault()).toInstant()),
                ((Map<?, ?>) arrived.get(0)).get("arrivedAt"));
        assertEquals(0, ((List<?>) roster.get("noShow")).size());
    }

    @Test
    void rosterPublishesCorrectionWindowSoFutureSessionsCanBeDisabledUpFront() {
        when(mapper.selectPaidRoster(ACTIVITY)).thenReturn(Collections.singletonList(row(51L, "已报名", null, null, 0)));
        when(mapper.selectWaitlistRoster(ACTIVITY)).thenReturn(Collections.emptyList());

        Map<String, Object> inWindow = service.roster(ACTOR, CLUB, ACTIVITY);
        Map<?, ?> open = (Map<?, ?>) inWindow.get("correctionWindow");
        assertEquals(Boolean.TRUE, open.get("operable"), "活动进行中时更正时段应是开着的");
        assertEquals(new Date(NOW.getTime() - 3_600_000L), open.get("startAt"));
        // 结束后 48 小时是唯一守门的那个常量,回包要跟它一致(不是另算一份)
        assertEquals(new Date(NOW.getTime() - 60_000L + 48L * 60L * 60L * 1000L), open.get("endAt"));

        // CU-C-73:场次还没开始 —— 名册要能告诉前端「现在不能改」,而不是等提交那一刻才 409
        CmsActivity future = new CmsActivity();
        future.setId(ACTIVITY);
        future.setClubId(CLUB);
        future.setDelFlag(0);
        future.setStartDate(new Date(NOW.getTime() + 24L * 60L * 60L * 1000L));
        future.setEndDate(new Date(NOW.getTime() + 25L * 60L * 60L * 1000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(future);

        Map<String, Object> notYet = service.roster(ACTOR, CLUB, ACTIVITY);
        Map<?, ?> closed = (Map<?, ?>) notYet.get("correctionWindow");
        assertEquals(Boolean.FALSE, closed.get("operable"));
        assertEquals(future.getStartDate(), closed.get("startAt"));
    }

    @Test
    void correctionAppendsVersionedAuditWithoutRewritingBaseCheckin() {
        Map<String, Object> target = row(52L, "成员", null, null, 0);
        target.put("paidCount", 1L);
        when(mapper.selectAttendanceTargetForUpdate(ACTIVITY, 52L)).thenReturn(target);
        AtomicReference<ClubEventAttendanceCorrection> inserted = new AtomicReference<>();
        when(mapper.selectLatestCorrectionForUpdate(ACTIVITY, 52L)).thenAnswer(call -> inserted.get());
        when(mapper.insertAttendanceCorrection(any())).thenAnswer(invocation -> {
            ClubEventAttendanceCorrection row = invocation.getArgument(0);
            row.setId(71L);
            inserted.set(row);
            return 1;
        });
        ClubAttendanceCorrectionRequest request = new ClubAttendanceCorrectionRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setMemberId(52L);
        request.setArrived(true);
        request.setReason("现场漏扫，领队与本人核对后补记");
        request.setExpectedVersion(0);
        request.setRequestId("attendance-fix-1");

        ClubEventAttendanceCorrection correction = service.correct(ACTOR, request);

        assertEquals(Long.valueOf(71L), correction.getId());
        assertEquals(Boolean.FALSE, correction.getOriginalArrived());
        assertEquals(Boolean.TRUE, correction.getNewArrived());
        assertEquals(Integer.valueOf(1), correction.getVersion());
        verify(mapper).insertAttendanceCorrection(any(ClubEventAttendanceCorrection.class));
    }

    @Test
    void correctionPreservesJdbcLocalDateTimeAsOriginalArrival() {
        LocalDateTime baseArrivedAt = LocalDateTime.of(2026, 9, 22, 8, 3, 51);
        Map<String, Object> target = row(52L, "成员", baseArrivedAt, null, 0);
        target.put("paidCount", 1L);
        when(mapper.selectAttendanceTargetForUpdate(ACTIVITY, 52L)).thenReturn(target);
        AtomicReference<ClubEventAttendanceCorrection> inserted = new AtomicReference<>();
        when(mapper.selectLatestCorrectionForUpdate(ACTIVITY, 52L)).thenAnswer(call -> inserted.get());
        when(mapper.insertAttendanceCorrection(any())).thenAnswer(invocation -> {
            ClubEventAttendanceCorrection row = invocation.getArgument(0);
            row.setId(72L);
            inserted.set(row);
            return 1;
        });
        ClubAttendanceCorrectionRequest request = correctionRequest(
                false, "现场误扫，领队与本人核对后撤销", "attendance-fix-local-date-time");

        ClubEventAttendanceCorrection correction = service.correct(ACTOR, request);

        assertEquals(Boolean.TRUE, correction.getOriginalArrived());
        assertEquals(Date.from(baseArrivedAt.atZone(ZoneId.systemDefault()).toInstant()),
                correction.getOriginalArrivedAt());
        assertEquals(Boolean.FALSE, correction.getNewArrived());
    }

    @Test
    void exactCorrectionReplayReturnsCurrentReceiptAndChangedEnvelopeConflicts() {
        Map<String, Object> target = row(52L, "成员", null, null, 0);
        target.put("paidCount", 1L);
        when(mapper.selectAttendanceTargetForUpdate(ACTIVITY, 52L)).thenReturn(target);
        AtomicReference<ClubEventAttendanceCorrection> receipt = new AtomicReference<>();
        when(mapper.selectAttendanceCorrectionByRequestForUpdate(ACTOR, "attendance-replay-1"))
                .thenAnswer(call -> receipt.get());
        when(mapper.selectLatestCorrectionForUpdate(ACTIVITY, 52L)).thenAnswer(call -> receipt.get());
        when(mapper.insertAttendanceCorrection(any())).thenAnswer(invocation -> {
            ClubEventAttendanceCorrection row = invocation.getArgument(0);
            row.setId(71L);
            receipt.set(row);
            return 1;
        });
        ClubAttendanceCorrectionRequest request = correctionRequest(
                true, "现场漏扫，领队与本人核对后补记", "attendance-replay-1");

        ClubEventAttendanceCorrection first = service.correct(ACTOR, request);
        ClubEventAttendanceCorrection replay = service.correct(ACTOR, request);

        ClubAttendanceCorrectionRequest changedStatus = correctionRequest(
                false, "现场漏扫，领队与本人核对后补记", "attendance-replay-1");
        ServiceException statusConflict = assertThrows(ServiceException.class,
                () -> service.correct(ACTOR, changedStatus));
        ClubAttendanceCorrectionRequest changedReason = correctionRequest(
                true, "改写后的原因", "attendance-replay-1");
        ServiceException reasonConflict = assertThrows(ServiceException.class,
                () -> service.correct(ACTOR, changedReason));

        assertEquals(Long.valueOf(71L), first.getId());
        assertEquals(Long.valueOf(71L), replay.getId());
        assertEquals(HttpStatus.CONFLICT, statusConflict.getCode());
        assertEquals(HttpStatus.CONFLICT, reasonConflict.getCode());
        verify(mapper, times(1)).selectAttendanceTargetForUpdate(ACTIVITY, 52L);
        verify(mapper, times(1)).insertAttendanceCorrection(any());
    }

    @Test
    void correctionAfterFiniteWindowFailsBeforeAuditInsert() {
        CmsActivity expired = new CmsActivity();
        expired.setId(ACTIVITY);
        expired.setClubId(CLUB);
        expired.setDelFlag(0);
        expired.setStartDate(new Date(NOW.getTime() - 72L * 60L * 60L * 1000L));
        expired.setEndDate(new Date(NOW.getTime() - 49L * 60L * 60L * 1000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(expired);
        ClubAttendanceCorrectionRequest request = new ClubAttendanceCorrectionRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setMemberId(52L);
        request.setArrived(true);
        request.setReason("超窗补记");
        request.setExpectedVersion(0);
        request.setRequestId("attendance-expired-1");

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.correct(ACTOR, request));
        verify(mapper, never()).insertAttendanceCorrection(any());
    }

    @Test
    void mismatchedEventScopeFailsClosedBeforeRosterQuery() {
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_CHECKIN))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, 99L, 999L,
                        Collections.singleton(ClubRoleCode.EVENT_LEAD)));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.roster(ACTOR, CLUB, ACTIVITY));
        verify(mapper, never()).selectPaidRoster(any());
    }

    private Map<String, Object> row(Long memberId, String nickname, Object baseArrivedAt,
                                    Boolean correctedArrived, int version) {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", memberId);
        row.put("nickname", nickname);
        row.put("baseArrivedAt", baseArrivedAt);
        row.put("correctedArrived", correctedArrived);
        row.put("correctedArrivedAt", null);
        row.put("correctionVersion", version);
        return row;
    }

    private ClubAttendanceCorrectionRequest correctionRequest(boolean arrived, String reason, String requestId) {
        ClubAttendanceCorrectionRequest request = new ClubAttendanceCorrectionRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setMemberId(52L);
        request.setArrived(arrived);
        request.setReason(reason);
        request.setExpectedVersion(0);
        request.setRequestId(requestId);
        return request;
    }
}
