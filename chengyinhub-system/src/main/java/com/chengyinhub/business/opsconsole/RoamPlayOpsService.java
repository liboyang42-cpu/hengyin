package com.chengyinhub.business.opsconsole;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.mapper.opsconsole.RoamPlayOpsMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * R10 BE-25 漫游 / 揭雾 / 队伍 / 游玩局的只读运营视图。只给聚合与状态，不给任何个人轨迹或格子位置。
 * 日期筛选按 yyyy-MM-dd，until 为开区间（含当天则传次日）。每个列表最多 200 行。
 */
@Service
public class RoamPlayOpsService
{
    static final int LIMIT = 200;
    static final Set<String> RUN_STATES = Set.of("PAUSED", "ENDED");

    @Autowired
    private RoamPlayOpsMapper mapper;

    public RoamPlayOpsService() {}

    RoamPlayOpsService(RoamPlayOpsMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> sessions(Long memberId, Integer status, String since, String until)
    {
        String from = day(since), to = day(until);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totals", mapper.sessionTotals(from, to));
        body.put("items", mapper.sessions(memberId, status, from, to, LIMIT));
        return body;
    }

    public Map<String, Object> fog(Long memberId)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totals", mapper.fogTotals());
        body.put("items", mapper.fogByMember(memberId, LIMIT));
        return body;
    }

    public List<Map<String, Object>> teams(Integer ownerType, Long ownerId, Integer status)
    {
        return mapper.teams(ownerType, ownerId, status, LIMIT);
    }

    public Map<String, Object> runs(Long memberId, Long topicId, String runState, Integer ownerType, Long ownerId, Integer status)
    {
        if (runState != null && !runState.isBlank() && !RUN_STATES.contains(runState)) throw bad("不认识的局状态");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerRuns", mapper.playRuns(memberId, topicId, runState == null || runState.isBlank() ? null : runState, LIMIT));
        body.put("gameRuns", mapper.runtimeSessions(topicId, ownerType, ownerId, status, LIMIT));
        return body;
    }

    static String day(String raw)
    {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()).toString(); }
        catch (DateTimeParseException e) { throw bad("日期格式应为 yyyy-MM-dd"); }
    }

    private static ServiceException bad(String m) { return new ServiceException(m, HttpStatus.BAD_REQUEST); }
}
