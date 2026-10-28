package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamPassportMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不调用恢复/结算写链；仅返回白名单统计，不延长位置保留期。 */
@Service
public class RoamPassportReadService {
    private final RoamPassportMapper mapper;
    public RoamPassportReadService(RoamPassportMapper mapper) { this.mapper = mapper; }

    private void requireOwner(Long owner) {
        if (owner == null || owner <= 0) throw new ServiceException("请先登录", 401);
    }
    private Long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new ServiceException("记录编号无效", 400);
        try { return Long.valueOf(value); }
        catch (NumberFormatException e) { throw new ServiceException("记录编号无效", 400); }
    }
    public Map<String, Object> page(Long owner, String before, Integer limit) {
        requireOwner(owner);
        Long cursor = before == null || before.isEmpty() ? null : id(before);
        int size = limit == null ? 30 : limit;
        if (size < 1 || size > 100) throw new ServiceException("分页大小无效", 400);
        List<RoamSession> rows = mapper.page(owner, cursor, size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> items = new ArrayList<>();
        for (RoamSession row : rows.subList(0, Math.min(size, rows.size()))) items.add(project(row));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items); out.put("hasMore", more);
        out.put("nextBefore", more ? rows.get(size - 1).getId().toString() : null);
        return out;
    }
    public Map<String, Object> detail(Long owner, String sessionId) {
        requireOwner(owner);
        RoamSession row = mapper.detail(owner, id(sessionId));
        if (row == null) throw new ServiceException("找不到这次漫游", 404);
        return project(row);
    }
    public Map<String, Object> summary(Long owner) {
        requireOwner(owner);
        return mapper.summary(owner);
    }
    private Map<String, Object> project(RoamSession row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", row.getId().toString());
        out.put("startedAt", row.getStartedAt() == null ? null : row.getStartedAt().getTime());
        out.put("finishedAt", row.getFinishedAt() == null ? null : row.getFinishedAt().getTime());
        out.put("distanceM", row.getDistanceM());
        Object shops = null;
        if (row.getFinishResultJson() != null) {
            try {
                Map<String, Object> result = JSON.parseObject(row.getFinishResultJson());
                Object raw = result == null ? null : result.get("sessionShops");
                if (raw instanceof Number n && n.longValue() >= 0 && n.doubleValue() == n.longValue()) shops = n.longValue();
            } catch (RuntimeException ignored) { /* Legacy missing/corrupt optional statistic stays unknown. */ }
        }
        out.put("shops", shops);
        return out;
    }
}
