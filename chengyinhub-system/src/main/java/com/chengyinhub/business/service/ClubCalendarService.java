package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.vo.PublicActivityListVO;
import com.chengyinhub.business.mapper.ClubCalendarMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ClubCalendarService {
    @Autowired private ClubCalendarMapper mapper;
    @Autowired private ClubMemberMapper membershipMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> month(Long clubId, Long viewerId, String month, Long afterId, Long throughId) {
        if (clubId == null || clubId <= 0 || month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("俱乐部或月份不合法");
        }
        YearMonth selected;
        try { selected = YearMonth.parse(month); }
        catch (RuntimeException e) { throw new IllegalArgumentException("月份不合法"); }
        if (selected.getYear() < 1970 || selected.getYear() > 9998) throw new IllegalArgumentException("月份不合法");
        long after = afterId == null ? 0 : afterId;
        if (after < 0 || (throughId != null && (throughId <= 0 || after <= 0 || after > throughId))
                || (after > 0 && throughId == null)) throw new IllegalArgumentException("分页参数不合法");
        String start = selected.atDay(1) + " 00:00:00";
        String end = selected.plusMonths(1).atDay(1) + " 00:00:00";
        if (throughId != null && mapper.cursorIds(clubId, viewerId, start, end, after, throughId).size()
                != (after == throughId ? 1 : 2)) {
            throw new IllegalArgumentException("月份或可见场次已变化，请重新读取");
        }
        Long upper = throughId == null ? mapper.upperId(clubId, viewerId, start, end) : throughId;
        long through = upper == null ? 0 : upper;
        boolean member = viewerId != null && viewerId > 0
                && membershipMapper.selectByClubAndMember(clubId, viewerId) != null;
        List<CmsActivity> rows = mapper.page(clubId, viewerId, start, end, after, through);
        boolean more = rows.size() > 100;
        if (more) rows = rows.subList(0, 100);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clubId", clubId);
        result.put("month", month);
        result.put("timeZone", "Asia/Shanghai");
        result.put("visibilityScope", member ? "CLUB_MEMBER" : viewerId != null && viewerId > 0 ? "ACTIVITY_OWNER" : "ANONYMOUS");
        result.put("rows", rows.stream().map(PublicActivityListVO::from).collect(Collectors.toList()));
        result.put("throughId", through);
        result.put("nextAfterId", rows.isEmpty() ? after : rows.get(rows.size() - 1).getId());
        result.put("hasMore", more);
        result.put("readAt", ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return result;
    }
}
