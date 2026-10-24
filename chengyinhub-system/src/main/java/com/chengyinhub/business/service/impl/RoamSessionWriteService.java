package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.RoamSessionRecordMapper;

/** 发现事实持锁提交；可选掉落沿用调用方原有的独立失败处理。 */
@Service
public class RoamSessionWriteService {
    @Autowired
    private RoamMapper roamMapper;

    @Autowired private RoamLocationConsentService locationConsent;

    @Autowired
    private RoamSessionRecordMapper recordMapper;

    /** Lock original owner/SID, validate all records before any insert. No settlement/reward calls. */
    @Transactional(rollbackFor = Exception.class)
    public int archiveFinishedRecords(Long memberId, Long sessionId, String clientSessionKey, String payload) {
        locationConsent.requireCurrentAgreement(memberId); // 采集记录同属定位几何：沿用会员锁→会话锁顺序与撤回门禁
        RoamSession session = roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId())) throw new ServiceException("会话不存在");
        if (clientSessionKey != null && !clientSessionKey.equals(session.getClientSessionKey()))
            throw new ServiceException("会话关联不一致");
        if (!Integer.valueOf(RoamSession.STATUS_FINISHED).equals(session.getStatus()) || session.getStartedAt() == null || session.getFinishedAt() == null)
            throw new ServiceException("原行程尚未确认结束");
        if (session.getLocationClearedAt() != null) throw new ServiceException("原行程位置已按保留期清除");
        List<Map<String, Object>> records = validateArchive(payload, session.getStartedAt(), session.getFinishedAt(), new Date());
        List<String> tiles = new ArrayList<>();
        Map<String, Date> times = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String tile = (String) record.get("tile");
            tiles.add(tile); times.put(tile, (Date) record.get("collectedAt"));
        }
        for (Map<String, Object> existing : recordMapper.selectOriginalRecords(memberId, sessionId, tiles)) {
            Date stored = dbDate(existing.get("collectedAt"));
            if (stored == null
                    || times.get(String.valueOf(existing.get("tile"))) == null
                    || stored.getTime() != times.get(String.valueOf(existing.get("tile"))).getTime())
                throw new ServiceException("原采集记录不一致");
        }
        recordMapper.insertRecords(memberId, sessionId, records, new Date());
        return records.size();
    }

    static List<Map<String, Object>> validateArchive(String payload, Date startedAt, Date finishedAt, Date now) {
        if (payload == null || payload.length() > 16000) throw new ServiceException("采集记录结构无效");
        JSONArray input;
        try { input = JSON.parseArray(payload); }
        catch (RuntimeException ex) { throw new ServiceException("采集记录结构无效"); }
        if (input == null || input.isEmpty() || input.size() > 200) throw new ServiceException("采集记录数量无效");
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Object value : input) {
            if (!(value instanceof JSONObject)) throw new ServiceException("采集记录结构无效");
            JSONObject row = (JSONObject) value;
            Object tile = row.get("tile"), rawTime = row.get("collectedAt");
            if (row.size() != 2 || !(tile instanceof String)
                    || !((String) tile).matches("[0123456789bcdefghjkmnpqrstuvwxyz]{7}") || !(rawTime instanceof Number))
                throw new ServiceException("采集记录结构无效");
            Number time = (Number) rawTime;
            long millis = time.longValue();
            if (!Double.isFinite(time.doubleValue()) || time.doubleValue() != (double) millis || millis <= 0
                    || millis < startedAt.getTime() || millis > finishedAt.getTime() || millis > now.getTime())
                throw new ServiceException("采集时间不属于原行程");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("tile", tile); record.put("collectedAt", new Date(millis));
            Map<String, Object> prior = unique.putIfAbsent((String) tile, record);
            if (prior != null && !prior.get("collectedAt").equals(record.get("collectedAt")))
                throw new ServiceException("原采集记录不一致");
        }
        return new ArrayList<>(unique.values());
    }

    @Transactional(rollbackFor = Exception.class)
    public int recordPoiFound(Long memberId, Long sessionId, Long poiId) {
        locationConsent.requireCurrentAgreement(memberId);
        RoamSession session = roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId())) throw new ServiceException("会话不存在");
        if (!Integer.valueOf(RoamSession.STATUS_ACTIVE).equals(session.getStatus())) throw new ServiceException("本次漫游已结算");
        return roamMapper.insertPoiFoundIgnore(memberId, poiId, sessionId, new Date());
    }

    /** MySQL Connector/J 8 对 Map 结果里的 DATETIME 返回 LocalDateTime；按 JVM 默认时区还原为原 Date(与写入同口径)。 */
    private static Date dbDate(Object value) {
        if (value instanceof Date) return (Date) value;
        if (value instanceof java.time.LocalDateTime)
            return Date.from(((java.time.LocalDateTime) value).atZone(java.time.ZoneId.systemDefault()).toInstant());
        return null;
    }
}
