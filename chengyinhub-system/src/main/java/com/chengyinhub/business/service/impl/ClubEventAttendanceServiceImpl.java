package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventAttendanceCorrection;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IClubEventAttendanceService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClubEventAttendanceServiceImpl implements IClubEventAttendanceService {
    private static final long CORRECTION_WINDOW_MILLIS = 48L * 60L * 60L * 1000L;
    protected final IClubAccessService accessService;
    protected final ClubEventOpsMapper mapper;
    protected final CmsActivityMapper activityMapper;

    public ClubEventAttendanceServiceImpl(IClubAccessService accessService, ClubEventOpsMapper mapper,
                                          CmsActivityMapper activityMapper) {
        this.accessService = accessService;
        this.mapper = mapper;
        this.activityMapper = activityMapper;
    }

    @Override
    public Map<String, Object> roster(Long actorMemberId, Long clubId, Long activityId) {
        ClubAccessContext access = requireAccess(actorMemberId, clubId, activityId);
        CmsActivity activity = requireActivity(access, clubId, activityId);
        boolean ended = activity.getEndDate() != null && !activity.getEndDate().after(now());
        List<Map<String, Object>> registered = new ArrayList<>();
        List<Map<String, Object>> arrived = new ArrayList<>();
        List<Map<String, Object>> noShow = new ArrayList<>();
        for (Map<String, Object> raw : safeMaps(mapper.selectPaidRoster(activityId))) {
            Map<String, Object> row = sanitizeRosterRow(raw);
            boolean effectiveArrived = effectiveArrived(row);
            Date effectiveAt = effectiveArrivedAt(row);
            row.put("arrived", effectiveArrived);
            row.put("arrivedAt", effectiveAt);
            if (effectiveArrived) arrived.add(row);
            else if (ended) noShow.add(row);
            else registered.add(row);
        }
        List<Map<String, Object>> waitlist = new ArrayList<>();
        for (Map<String, Object> raw : safeMaps(mapper.selectWaitlistRoster(activityId))) {
            waitlist.add(sanitizeRosterRow(raw));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clubId", clubId);
        result.put("activityId", activityId);
        result.put("registered", registered);
        result.put("waitlist", waitlist);
        result.put("arrived", arrived);
        result.put("noShow", noShow);
        result.put("phoneIncluded", false);
        // CU-C-73:可更正时段(活动开始 → 结束后 48 小时)随名册一起下发。前端手上没有
        // 本场起止时间,只能在提交那一刻吃服务端 409;拿到这段区间就能先把按钮置灰。
        result.put("correctionWindow", correctionWindow(activity, now()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubEventAttendanceCorrection correct(Long actorMemberId,
                                                   ClubAttendanceCorrectionRequest request) {
        if (request == null || request.getArrived() == null || !positive(request.getMemberId())) {
            throw badRequest("签到更正参数不完整");
        }
        ClubAccessContext access = requireAccess(actorMemberId, request.getClubId(), request.getActivityId());
        CmsActivity activity = requireActivity(access, request.getClubId(), request.getActivityId());
        String reason = normalizeReason(request.getReason());
        String requestId = normalizeRequestId(request.getRequestId());
        ClubEventAttendanceCorrection receipt = mapper.selectAttendanceCorrectionByRequestForUpdate(
                actorMemberId, requestId);
        if (receipt != null) {
            requireExactReplay(receipt, actorMemberId, request, reason);
            ClubEventAttendanceCorrection current = mapper.selectLatestCorrectionForUpdate(
                    request.getActivityId(), request.getMemberId());
            if (!sameTarget(current, request) || current.getVersion() == null
                    || current.getVersion() < receipt.getVersion()) {
                throw conflict("签到更正回读失败");
            }
            return current;
        }
        Date correctedAt = now();
        requireCorrectionWindow(activity, correctedAt);
        Map<String, Object> target = mapper.selectAttendanceTargetForUpdate(
                request.getActivityId(), request.getMemberId());
        if (target == null || number(target.get("paidCount")) <= 0L
                || !request.getMemberId().equals(longValue(target.get("memberId")))) {
            throw forbidden("目标账号不是本场已支付报名成员");
        }
        ClubEventAttendanceCorrection latest = mapper.selectLatestCorrectionForUpdate(
                request.getActivityId(), request.getMemberId());
        int currentVersion = latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
        if (request.getExpectedVersion() == null || request.getExpectedVersion() != currentVersion) {
            throw conflict("签到状态已变化，请刷新");
        }
        boolean originalArrived = latest == null
                ? effectiveArrived(target) : Boolean.TRUE.equals(latest.getNewArrived());
        Date originalAt = latest == null ? dateValue(target.get("baseArrivedAt")) : latest.getNewArrivedAt();
        if (originalArrived == request.getArrived()) throw conflict("更正前后状态相同");

        ClubEventAttendanceCorrection correction = new ClubEventAttendanceCorrection();
        correction.setClubId(request.getClubId());
        correction.setActivityId(request.getActivityId());
        correction.setMemberId(request.getMemberId());
        correction.setOriginalArrived(originalArrived);
        correction.setNewArrived(request.getArrived());
        correction.setOriginalArrivedAt(originalAt);
        correction.setNewArrivedAt(Boolean.TRUE.equals(request.getArrived()) ? correctedAt : null);
        correction.setReason(reason);
        correction.setActorMemberId(actorMemberId);
        correction.setRequestId(requestId);
        correction.setVersion(currentVersion + 1);
        correction.setCorrectedAt(correctedAt);
        try {
            if (mapper.insertAttendanceCorrection(correction) != 1 || correction.getId() == null) {
                throw conflict("签到更正写入失败");
            }
        } catch (DuplicateKeyException duplicate) {
            throw conflict("签到状态已变化，请刷新");
        }
        ClubEventAttendanceCorrection current = mapper.selectLatestCorrectionForUpdate(
                request.getActivityId(), request.getMemberId());
        if (!sameTarget(current, request) || !correction.getId().equals(current.getId())) {
            throw conflict("签到更正回读失败");
        }
        return current;
    }

    private void requireExactReplay(ClubEventAttendanceCorrection receipt, Long actorMemberId,
                                    ClubAttendanceCorrectionRequest request, String reason) {
        boolean exact = actorMemberId.equals(receipt.getActorMemberId())
                && request.getClubId().equals(receipt.getClubId())
                && request.getActivityId().equals(receipt.getActivityId())
                && request.getMemberId().equals(receipt.getMemberId())
                && request.getArrived().equals(receipt.getNewArrived())
                && reason.equals(receipt.getReason())
                && receipt.getVersion() != null && receipt.getVersion() > 0
                && request.getExpectedVersion() != null
                && request.getExpectedVersion().intValue() == receipt.getVersion() - 1;
        if (!exact) throw conflict("requestId 已用于其他签到更正");
    }

    private boolean sameTarget(ClubEventAttendanceCorrection row,
                               ClubAttendanceCorrectionRequest request) {
        return row != null && request.getClubId().equals(row.getClubId())
                && request.getActivityId().equals(row.getActivityId())
                && request.getMemberId().equals(row.getMemberId());
    }

    private ClubAccessContext requireAccess(Long actorMemberId, Long clubId, Long activityId) {
        if (!positive(actorMemberId) || !positive(clubId) || !positive(activityId)) {
            throw badRequest("场次范围不完整");
        }
        ClubAccessContext access = accessService.require(
                actorMemberId, clubId, activityId, ClubPermission.EVENT_CHECKIN);
        if (access == null || !actorMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId()) || !activityId.equals(access.getActivityId())) {
            throw forbidden("仅本场领队或核销员可查看名册与更正签到");
        }
        return access;
    }

    private CmsActivity requireActivity(ClubAccessContext access, Long clubId, Long activityId) {
        CmsActivity activity = activityMapper.selectCmsActivityById(activityId);
        if (activity == null || !activityId.equals(activity.getId()) || !clubId.equals(activity.getClubId())
                || !Integer.valueOf(0).equals(activity.getDelFlag())
                || !activityId.equals(access.getActivityId())) throw forbidden("活动范围不一致");
        return activity;
    }

    private void requireCorrectionWindow(CmsActivity activity, Date current) {
        Map<String, Object> window = correctionWindow(activity, current);
        if (!Boolean.TRUE.equals(window.get("operable"))) {
            throw conflict("签到更正仅限活动开始至结束后 48 小时");
        }
    }

    /**
     * 签到更正时段(与 requireCorrectionWindow 同一判据，给前端前置置灰用)。
     * CU-C-73："operable" 是唯一判定结果，startAt/endAt 只用于就地写出可操作时段。
     */
    private Map<String, Object> correctionWindow(CmsActivity activity, Date current) {
        Date startAt = activity.getStartDate();
        Date endAt = activity.getEndDate() == null ? null
                : new Date(activity.getEndDate().getTime() + CORRECTION_WINDOW_MILLIS);
        boolean operable = startAt != null && endAt != null && current != null
                && !current.before(startAt) && current.getTime() <= endAt.getTime();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startAt", startAt);
        window.put("endAt", endAt);
        window.put("operable", operable);
        return window;
    }

    private Map<String, Object> sanitizeRosterRow(Map<String, Object> raw) {
        if (raw == null || !positive(longValue(raw.get("memberId")))) throw conflict("名册成员状态不完整");
        Map<String, Object> row = new LinkedHashMap<>(raw);
        row.remove("phone");
        row.remove("email");
        row.remove("address");
        return row;
    }

    private boolean effectiveArrived(Map<String, Object> row) {
        Object corrected = row.get("correctedArrived");
        if (corrected instanceof Boolean) return (Boolean) corrected;
        if (corrected instanceof Number) return ((Number) corrected).intValue() == 1;
        Object base = row.get("baseArrived");
        if (base instanceof Boolean) return (Boolean) base;
        if (base instanceof Number) return ((Number) base).intValue() == 1;
        return dateValue(row.get("baseArrivedAt")) != null;
    }

    private Date effectiveArrivedAt(Map<String, Object> row) {
        if (!effectiveArrived(row)) return null;
        Date corrected = dateValue(row.get("correctedArrivedAt"));
        return corrected != null ? corrected : dateValue(row.get("baseArrivedAt"));
    }

    private String normalizeReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 2 || value.length() > 500) throw badRequest("更正原因需为 2 至 500 字");
        return value;
    }

    private String normalizeRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.isEmpty() || value.length() > 64) throw badRequest("requestId 需为 1 至 64 字");
        return value;
    }

    private List<Map<String, Object>> safeMaps(List<Map<String, Object>> values) {
        return values == null ? Collections.<Map<String, Object>>emptyList() : values;
    }
    private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }
    private Long longValue(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private Date dateValue(Object value) {
        if (value instanceof Date) return (Date) value;
        if (value instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) value).atZone(ZoneId.systemDefault()).toInstant());
        }
        return null;
    }
    private boolean positive(Long value) { return value != null && value > 0; }
    protected Date now() { return new Date(); }
    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
}
