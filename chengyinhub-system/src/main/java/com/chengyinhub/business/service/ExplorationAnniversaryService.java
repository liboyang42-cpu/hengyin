package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ExplorationAnniversary;
import com.chengyinhub.business.mapper.ExplorationAnniversaryMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Service
public class ExplorationAnniversaryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withResolverStyle(ResolverStyle.STRICT);
    private final ExplorationAnniversaryMapper mapper;
    public ExplorationAnniversaryService(ExplorationAnniversaryMapper mapper) { this.mapper = mapper; }
    protected long now() { return System.currentTimeMillis(); }
    public static int integer(String value, int min, int max, String label) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException error) { throw new ServiceException(label + "须为范围内的规范整数", 400); }
    }
    private static MonthDay selector(String month, String day, LocalDate today) {
        if (month == null && day == null) return MonthDay.from(today);
        try { return MonthDay.of(integer(month,1,12,"月份"), integer(day,1,31,"日期")); }
        catch (RuntimeException error) { throw new ServiceException("月份与日期须同时提供有效值，闰日仅匹配2月29日",400); }
    }
    public Map<String,Object> read(Long owner, String rawMonth, String rawDay, String cursor, String rawLimit) {
        if (owner == null || owner <= 0) throw new ServiceException("请先登录",401);
        long asOf = now(); LocalDate today = Instant.ofEpochMilli(asOf).atZone(ZONE).toLocalDate();
        MonthDay selected = selector(rawMonth,rawDay,today); String monthDay = selected.toString().substring(2);
        int limit = rawLimit == null ? 20 : integer(rawLimit,1,50,"分页数量");
        int year = today.getYear(); String beforeAt = null; Long beforeId = null;
        if (cursor != null) {
            try {
                if (!cursor.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
                String[] pieces = new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if (pieces.length != 6 || !"1".equals(pieces[0]) || !String.valueOf(owner).equals(pieces[1])
                    || !monthDay.equals(pieces[2]) || !String.valueOf(year).equals(pieces[3])) throw new IllegalArgumentException();
                LocalDateTime at = LocalDateTime.parse(pieces[4],WALL);
                if (!at.format(WALL).equals(pieces[4]) || !MonthDay.from(at).equals(selected) || at.getYear() >= year
                    || !pieces[5].matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                beforeId = Long.valueOf(pieces[5]); if (beforeId <= 0) throw new IllegalArgumentException(); beforeAt = pieces[4];
            } catch (RuntimeException error) { throw new ServiceException("回忆游标失效，请重新加载当前日期",400); }
        }
        List<ExplorationAnniversary> rows = mapper.page(owner,monthDay,year+"-01-01 00:00:00",beforeAt,beforeId,limit+1);
        boolean more = rows.size() > limit; List<Map<String,Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit,rows.size()); i++) {
            ExplorationAnniversary row = rows.get(i); LocalDateTime completed = LocalDateTime.parse(row.getCompletedAt(),WALL);
            Map<String,Object> item = new LinkedHashMap<>(); item.put("id",row.getId()); item.put("topicId",row.getTopicId());
            item.put("title",row.getTitle()); item.put("completedAt",row.getCompletedAt()); item.put("year",completed.getYear());
            item.put("yearsAgo",year-completed.getYear()); items.add(item);
        }
        String next = null;
        if (more) {
            ExplorationAnniversary last = rows.get(limit-1);
            String payload = "1|"+owner+"|"+monthDay+"|"+year+"|"+last.getCompletedAt()+"|"+last.getId();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        }
        Map<String,Object> out = new LinkedHashMap<>(); out.put("month",selected.getMonthValue()); out.put("day",selected.getDayOfMonth());
        out.put("referenceYear",year); out.put("timeZone","Asia/Shanghai"); out.put("asOfMs",asOf); out.put("items",items);
        out.put("hasMore",more); out.put("nextCursor",next); out.put("leapDayRule","EXACT_MONTH_DAY_ONLY");
        out.put("coverage","RECORDED_OWN_COMPLETED_SESSIONS_WITH_CURRENT_VISIBLE_ROUTE"); out.put("metadataMode","CURRENT_ROUTE_METADATA");
        return out;
    }
}
