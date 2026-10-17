package com.chengyinhub.business.service;
import com.chengyinhub.business.mapper.MonthlyRecapMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class MonthlyRecapService {
    private final MonthlyRecapMapper mapper;
    public MonthlyRecapService(MonthlyRecapMapper mapper) { this.mapper=mapper; }
    protected long now() { return System.currentTimeMillis(); }
    public static YearMonth month(String raw) {
        try {
            if (raw == null || !raw.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            YearMonth value=YearMonth.parse(raw);
            if (value.getYear()<2000 || value.getYear()>9998) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw new ServiceException("月份应为有效的 YYYY-MM"); }
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> read(Long owner, String rawMonth, String kind, Long beforeId, Integer requestedLimit) {
        CapsuleService.requireOwner(owner);
        YearMonth month=month(rawMonth);
        if (month.isAfter(YearMonth.from(Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Shanghai"))))) throw new ServiceException("未来月份暂无探索记录");
        if (!Arrays.asList("routes","places","stamps").contains(kind)) throw new ServiceException("记录类型不合法");
        if (beforeId!=null && beforeId<=0) throw new ServiceException("分页编号不合法");
        int limit=requestedLimit==null?20:requestedLimit;
        if (limit<1 || limit>50) throw new ServiceException("每页应为1至50条");
        String start=month.atDay(1)+" 00:00:00", end=month.plusMonths(1).atDay(1)+" 00:00:00";
        Map<String,Object> counts=new LinkedHashMap<>();
        for (String source : Arrays.asList("routes","places","stamps")) counts.put(source,mapper.count(owner,source,start,end));
        List<Map<String,Object>> all=mapper.page(owner,kind,start,end,beforeId,limit+1);
        boolean more=all.size()>limit; List<Map<String,Object>> items=new ArrayList<>(all.subList(0,Math.min(limit,all.size())));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("month",rawMonth); result.put("kind",kind); result.put("timeZone","Asia/Shanghai");
        result.put("generatedAtMs",now()); result.put("counts",counts); result.put("items",items);
        result.put("hasMore",more); result.put("nextBeforeId",more?String.valueOf(items.get(limit-1).get("id")):null);
        result.put("coverage","RECORDED_COMPLETED_ROUTE_SESSIONS_AND_COMPLETED_ROAM_POI_AND_OWN_STAMPS");
        return result;
    }
}
