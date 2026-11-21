package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberDataCopyRequest;
import com.chengyinhub.business.domain.vo.DataCopyRequestVO;
import com.chengyinhub.business.mapper.MemberDataCopyRequestMapper;
import com.chengyinhub.business.service.IMemberDataCopyService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ONB-10 (PIPL Art. 45): data-copy requests. One open request per member (idempotent create); the deadline shown to
 * the member and the admin is the created date plus {@code compliance.data-copy.response-working-days} working days.
 */
@Service
public class MemberDataCopyServiceImpl implements IMemberDataCopyService {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DELIVERED = "DELIVERED";
    public static final String REJECTED = "REJECTED";
    static final int NOTE_MAX = 500;

    /** Allowed admin transitions; DELIVERED and REJECTED are final. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PENDING, Set.of(PROCESSING, REJECTED),
            PROCESSING, Set.of(DELIVERED, REJECTED));

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MemberDataCopyRequestMapper mapper;
    private final int responseWorkingDays;
    private final Clock clock;

    @Autowired
    public MemberDataCopyServiceImpl(MemberDataCopyRequestMapper mapper,
                                     @Value("${compliance.data-copy.response-working-days:15}") int responseWorkingDays) {
        this(mapper, responseWorkingDays, Clock.systemDefaultZone());
    }

    public MemberDataCopyServiceImpl(MemberDataCopyRequestMapper mapper, int responseWorkingDays, Clock clock) {
        if (responseWorkingDays < 1) throw new IllegalArgumentException("compliance.data-copy.response-working-days must be >= 1");
        this.mapper = mapper;
        this.responseWorkingDays = responseWorkingDays;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataCopyRequestVO request(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录");
        // Two taps at once must not open two tickets: hold the member row while checking for an open request.
        mapper.lockMember(memberId);
        MemberDataCopyRequest open = mapper.selectOpenByMember(memberId);
        if (open != null) return toVO(open);
        MemberDataCopyRequest created = new MemberDataCopyRequest();
        created.setMemberId(memberId);
        created.setStatus(PENDING);
        // Whole seconds: DATETIME has no fraction, and the reply must match what a later read returns.
        Date now = Date.from(clock.instant().truncatedTo(ChronoUnit.SECONDS));
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        mapper.insert(created);
        return toVO(created);
    }

    @Override
    public DataCopyRequestVO latest(Long memberId) {
        if (memberId == null || memberId <= 0) return null;
        MemberDataCopyRequest latest = mapper.selectLatestByMember(memberId);
        return latest == null ? null : toVO(latest);
    }

    @Override
    public List<MemberDataCopyRequest> list(String status) {
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        List<MemberDataCopyRequest> rows = mapper.selectList(filter);
        for (MemberDataCopyRequest row : rows) row.setDueBy(dueByText(row.getCreatedAt()));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemberDataCopyRequest changeStatus(Long id, String status, String note, Long adminUserId) {
        if (id == null || status == null || status.isBlank()) throw new ServiceException("缺少申请或目标状态");
        String target = status.trim().toUpperCase();
        String trimmedNote = note == null ? null : note.trim();
        if (trimmedNote != null && trimmedNote.isEmpty()) trimmedNote = null;
        if (trimmedNote != null && trimmedNote.length() > NOTE_MAX) throw new ServiceException("备注不能超过500字");
        MemberDataCopyRequest current = mapper.selectById(id);
        if (current == null) throw new ServiceException("申请不存在");
        if (!TRANSITIONS.getOrDefault(current.getStatus(), Set.of()).contains(target)) {
            throw new ServiceException("当前状态" + current.getStatus() + "不能改为" + target);
        }
        if (REJECTED.equals(target) && trimmedNote == null) throw new ServiceException("拒绝时请填写原因");
        Date now = Date.from(clock.instant());
        if (mapper.updateStatus(id, current.getStatus(), target, trimmedNote, adminUserId, now) != 1) {
            throw new ServiceException("申请状态已被他人更新，请刷新后重试");
        }
        MemberDataCopyRequest updated = mapper.selectById(id);
        if (updated != null) updated.setDueBy(dueByText(updated.getCreatedAt()));
        return updated;
    }

    /**
     * Response deadline: the created date plus N working days, where a working day is Monday to Friday.
     * Public holidays and make-up working days (调休) are NOT taken into account; the default of 15 working days
     * (owner/counsel decide the value via {@code compliance.data-copy.response-working-days}) leaves room for that.
     * Day 1 is the first working day after the created date, so a request made on a Saturday or Sunday counts from
     * Monday, and a Friday request with 15 days is due on the Friday three weeks later.
     */
    public static LocalDate dueBy(LocalDate created, int workingDays) {
        LocalDate day = created;
        int added = 0;
        while (added < workingDays) {
            day = day.plusDays(1);
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) added++;
        }
        return day;
    }

    private DataCopyRequestVO toVO(MemberDataCopyRequest row) {
        String created = row.getCreatedAt() == null ? null : localDateTime(row.getCreatedAt()).format(DATE_TIME);
        return new DataCopyRequestVO(row.getId(), row.getStatus(), created, dueByText(row.getCreatedAt()));
    }

    private String dueByText(Date createdAt) {
        if (createdAt == null) return null;
        return dueBy(localDateTime(createdAt).toLocalDate(), responseWorkingDays).format(DATE);
    }

    private LocalDateTime localDateTime(Date date) {
        ZoneId zone = clock.getZone();
        return LocalDateTime.ofInstant(date.toInstant(), zone);
    }
}
