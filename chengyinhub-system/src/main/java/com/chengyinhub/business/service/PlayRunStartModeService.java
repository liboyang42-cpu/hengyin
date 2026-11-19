package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayRunStartModeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CG-ALL-11 替代站 / 桌面完成(业主 2026-11-17 批准)。
 *
 * <p>DESK:不定位开局，整局奖励只发探索值与徽章;写入失败会让开局失败——
 * 不能出现「没记成 DESK 却免定位开了局」。
 * ALT:在路线配置的公共街面替代站 300 米内开局，之后与正常局相同。
 */
@Service
public class PlayRunStartModeService {

    private static final Logger log = LoggerFactory.getLogger(PlayRunStartModeService.class);

    public static final String DESK = "DESK";
    public static final String ALT = "ALT";

    @Autowired(required = false)
    private PlayRunStartModeMapper mapper;

    /** 客户端传来的开局方式;只认 DESK / ALT,其它一律当作正常开局(null)。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String mode = raw.trim().toUpperCase(Locale.ROOT);
        return DESK.equals(mode) || ALT.equals(mode) ? mode : null;
    }

    public String runMode(Long memberId, Long activityId, Long topicId) {
        if (mapper == null || memberId == null || topicId == null) return null;
        try {
            return normalize(mapper.selectRunMode(memberId, activityId == null ? 0L : activityId, topicId));
        } catch (RuntimeException unavailable) {
            log.warn("开局方式读取失败 memberId={} topicId={}", memberId, topicId, unavailable);
            return null;
        }
    }

    /** 本局是否桌面完成。读失败按 DESK 处理(只发探索值与徽章)，宁可少发不多发。 */
    public boolean deskRun(Long memberId, Long activityId, Long topicId) {
        if (mapper == null || memberId == null || topicId == null) return false;
        try {
            return DESK.equals(normalize(mapper.selectRunMode(memberId, activityId == null ? 0L : activityId, topicId)));
        } catch (RuntimeException unavailable) {
            log.warn("开局方式读取失败，按桌面完成封顶奖励 memberId={} topicId={}", memberId, topicId, unavailable);
            return true;
        }
    }

    /** 开局时记录方式:DESK/ALT 写行，正常开局清掉同主题旧行。写失败直接抛出(开局随之失败)。 */
    public void recordStart(Long memberId, Long activityId, Long topicId, String mode, Long altStationId) {
        if (mapper == null) {
            if (mode != null) throw new IllegalStateException("开局方式暂时不可用");
            return;
        }
        long act = activityId == null ? 0L : activityId;
        if (mode == null) mapper.deleteRunMode(memberId, act, topicId);
        else mapper.upsertRunMode(memberId, act, topicId, mode, ALT.equals(mode) ? altStationId : null);
    }

    public List<Map<String, Object>> altStations(Long topicId) {
        if (mapper == null || topicId == null) return Collections.emptyList();
        try {
            List<Map<String, Object>> rows = mapper.selectAltStations(topicId);
            return rows == null ? Collections.emptyList() : rows;
        } catch (RuntimeException unavailable) {
            log.warn("替代站读取失败 topicId={}", topicId, unavailable);
            return Collections.emptyList();
        }
    }

    /** 本主题启用中的某个替代站;不存在 / 不属于本主题返回 null。 */
    public Map<String, Object> altStation(Long topicId, Long altStationId) {
        if (altStationId == null) return null;
        for (Map<String, Object> row : altStations(topicId)) {
            Object id = row.get("id");
            if (id != null && altStationId.equals(Long.valueOf(String.valueOf(id)))) return row;
        }
        return null;
    }
}
