package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** 精确读取当前玩家的漫游事实，不创建、结算或清理任何会话。 */
@Service
public class RoamRecoveryService {
    private final RoamMapper mapper;

    public RoamRecoveryService(RoamMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> read(Long owner, String sessionId, String clientSessionKey) {
        Long id = null;
        if (sessionId != null) {
            if (!sessionId.matches("[1-9][0-9]{0,18}")) throw new ServiceException("会话编号无效");
            try { id = Long.valueOf(sessionId); }
            catch (NumberFormatException ex) { throw new ServiceException("会话编号无效"); }
        }
        if (clientSessionKey != null && !clientSessionKey.matches("[a-f0-9]{32}"))
            throw new ServiceException("会话关联标识无效");
        if (id == null && clientSessionKey == null) throw new ServiceException("缺少会话关联");
        RoamSession row = mapper.selectOwnedRecoverySession(owner, id, id == null ? clientSessionKey : null);
        Map<String, Object> data = new LinkedHashMap<>();
        if (row == null) {
            if (id != null && clientSessionKey != null) throw new ServiceException("会话关联无法确认");
            data.put("state", "NOT_FOUND");
            return data;
        }
        if (clientSessionKey != null && !clientSessionKey.equals(row.getClientSessionKey()))
            throw new ServiceException("会话关联不一致");
        data.put("sessionId", String.valueOf(row.getId()));
        data.put("clientSessionKey", row.getClientSessionKey());
        if (Integer.valueOf(0).equals(row.getStatus())) {
            data.put("state", "ACTIVE");
        } else if (Integer.valueOf(1).equals(row.getStatus())) {
            data.put("state", "FINISHED");
            boolean complete = row.getFinishResultJson() != null;
            Map<String, Object> result;
            if (complete) {
                result = JSON.parseObject(row.getFinishResultJson());
                if (result == null || !(result.get("totalXp") instanceof Number))
                    throw new ServiceException("结算记录暂时无法读取");
            } else {
                result = new LinkedHashMap<>();
                result.put("totalXp", row.getXpAwarded());
                result.put("newTiles", row.getTilesRevealed());
                result.put("newPois", row.getPoisFound());
            }
            data.put("result", result);
            data.put("resultComplete", complete);
        } else {
            throw new ServiceException("会话状态暂时无法确认");
        }
        return data;
    }
}
