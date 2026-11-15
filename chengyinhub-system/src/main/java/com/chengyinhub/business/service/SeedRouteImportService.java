package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.seed.SeedImportReport;
import com.chengyinhub.business.domain.dto.seed.SeedImportRow;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.dto.seed.SeedRoute;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.business.service.support.JourneyDifficulty;
import com.chengyinhub.business.service.support.JourneyRulesValidator;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAM-09: bulk import of official routes. One row = one route: title, difficulty and the ordered ids of existing
 * official places. Each route becomes a draft topic (not approved, not on sale) whose nodes copy the places' name,
 * address and coordinates in order; the existing topic editor and review take it from there.
 */
@Service
public class SeedRouteImportService {
    public static final List<String> HEADERS = List.of("title", "difficulty", "place_ids", "description");
    static final Map<String, String> ALIASES = new LinkedHashMap<>();
    static {
        alias("title", "标题", "路线名称", "name");
        alias("difficulty", "难度");
        alias("place_ids", "地点id", "地点", "places", "poi_ids");
        alias("description", "简介", "路线介绍");
    }
    static final int MIN_STOPS = 2;
    static final int MAX_STOPS = 30;

    private final SeedContentMapper mapper;
    private final IOfficialEventV2Service operators;

    public SeedRouteImportService(SeedContentMapper mapper, IOfficialEventV2Service operators) {
        this.mapper = mapper; this.operators = operators;
    }

    private static void alias(String canonical, String... names) {
        ALIASES.put(canonical, canonical);
        for (String name : names) ALIASES.put(SeedTableReader.normalizeHeader(name), canonical);
    }

    @Transactional(rollbackFor = Exception.class)
    public SeedImportReport importRoutes(Long sysUserId, String username, List<SeedTableReader.Line> lines, boolean dryRun) {
        Long owner = null;
        if (!dryRun) {
            owner = operators.resolveAuthorizedOfficialMember(sysUserId);
            if (owner == null || owner <= 0) throw new ServiceException("请使用已授权的官方运营账号");
        }
        if (lines.isEmpty()) throw new ServiceException("文件里没有数据行");
        for (String required : List.of("title", "difficulty", "place_ids")) {
            if (lines.get(0).values.keySet().stream().noneMatch(key -> required.equals(ALIASES.get(key))))
                throw new ServiceException("缺少必填列：" + required + "（请用下载的模板）");
        }
        List<SeedImportRow> rows = new ArrayList<>();
        List<List<Long>> stops = new ArrayList<>();
        List<String> difficulties = new ArrayList<>();
        Set<Long> allIds = new LinkedHashSet<>();
        for (SeedTableReader.Line line : lines) {
            SeedImportRow row = new SeedImportRow();
            row.setRowNumber(line.number);
            Map<String, String> values = new LinkedHashMap<>();
            for (String header : HEADERS) values.put(header, "");
            line.values.forEach((key, value) -> {
                String canonical = ALIASES.get(key);
                if (canonical != null && values.get(canonical).isEmpty()) values.put(canonical, value);
            });
            row.setValues(values);
            String title = values.get("title");
            if (title.isEmpty()) row.error("缺少路线名称");
            else if (title.length() > 100) row.error("路线名称超过 100 字");
            if (values.get("description").length() > 2000) row.error("简介超过 2000 字");
            String difficulty = difficulty(values.get("difficulty"));
            if (difficulty == null) row.error("难度只能是 轻松/标准/硬核（easy/standard/hard）");
            difficulties.add(difficulty);
            List<Long> ids = placeIds(values.get("place_ids"), row);
            stops.add(ids);
            allIds.addAll(ids);
            rows.add(row);
        }
        Map<Long, SeedPlace> places = new HashMap<>();
        if (!allIds.isEmpty()) for (SeedPlace p : mapper.selectPlacesByIds(allIds)) places.put(p.getId(), p);
        Set<String> titles = new HashSet<>();
        for (SeedImportRow row : rows) if (row.isNew()) titles.add(row.getValues().get("title"));
        Set<String> existingTitles = titles.isEmpty() ? Set.of() : new HashSet<>(mapper.selectExistingRouteNames(titles));
        Map<String, Integer> seenTitles = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            SeedImportRow row = rows.get(i);
            checkStops(stops.get(i), places, row);
            String title = row.getValues().get("title");
            if (!row.isNew()) continue;
            if (existingTitles.contains(title)) row.duplicateOf(null, "已有同名官方路线");
            else if (seenTitles.containsKey(title)) row.duplicateOf(null, "本文件第 " + seenTitles.get(title) + " 行");
            else seenTitles.put(title, row.getRowNumber());
        }
        SeedImportReport report = SeedImportReport.of(rows, dryRun);
        if (dryRun) return report;
        if (report.getErrorCount() > 0) throw new ServiceException("有 " + report.getErrorCount() + " 行有错误，未导入任何路线");
        JourneyRulesValidator rules = new JourneyRulesValidator();
        for (int i = 0; i < rows.size(); i++) {
            SeedImportRow row = rows.get(i);
            if (!row.isNew()) continue;
            List<SeedPlace> route = new ArrayList<>();
            for (Long id : stops.get(i)) route.add(places.get(id));
            SeedRoute topic = new SeedRoute();
            topic.setMemberId(owner);
            topic.setName(row.getValues().get("title"));
            String description = row.getValues().get("description");
            topic.setDescription(description.isEmpty() ? null : description);
            String address = route.get(0).getAddress();
            topic.setAddressName(address == null ? "" : address.length() > 200 ? address.substring(0, 200) : address);
            topic.setLocationCount(route.size());
            topic.setTemplateCount((int) route.stream().filter(p -> p.getTemplateId() != null).count());
            topic.setTotalMileage(mileageKm(route));
            topic.setJourneyRulesJson(rules.validateAndNormalize(
                    "{\"schemaVersion\":" + JourneyRulesValidator.SCHEMA_VERSION + ",\"difficulty\":\"" + difficulties.get(i) + "\"}"));
            topic.setCreateBy(username);
            if (mapper.insertRouteDraft(topic) != 1 || topic.getId() == null)
                throw new ServiceException("第 " + row.getRowNumber() + " 行保存失败，未导入任何路线");
            for (int s = 0; s < route.size(); s++) {
                SeedPlace place = route.get(s);
                if (mapper.insertRouteNode(topic.getId(), s + 1, place, place.getTemplateId() == null ? 0L : place.getTemplateId(), username) != 1)
                    throw new ServiceException("第 " + row.getRowNumber() + " 行站点保存失败，未导入任何路线");
            }
            row.setCreatedId(topic.getId());
        }
        report.setCommitted(true);
        return report;
    }

    static String difficulty(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        switch (value) {
            case "easy": case "轻松": case "简单": case "1": return JourneyDifficulty.EASY;
            case "standard": case "标准": case "普通": case "2": return JourneyDifficulty.STANDARD;
            case "hard": case "硬核": case "困难": case "3": return JourneyDifficulty.HARD;
            default: return null;
        }
    }

    static List<Long> placeIds(String raw, SeedImportRow row) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) { row.error("缺少地点 id"); return ids; }
        for (String part : raw.trim().split("[\\s,;，；|>→、]+")) {
            if (part.isEmpty()) continue;
            if (!part.matches("[1-9][0-9]{0,18}")) { row.error("地点 id「" + part + "」不是数字"); return new ArrayList<>(); }
            Long id = Long.valueOf(part);
            if (ids.contains(id)) { row.error("地点 #" + id + " 在同一路线里重复"); return new ArrayList<>(); }
            ids.add(id);
        }
        if (ids.size() < MIN_STOPS || ids.size() > MAX_STOPS) row.error("每条路线需要 " + MIN_STOPS + " 到 " + MAX_STOPS + " 个地点");
        return ids;
    }

    static void checkStops(List<Long> ids, Map<Long, SeedPlace> places, SeedImportRow row) {
        int withoutGame = 0, notLive = 0;
        for (Long id : ids) {
            SeedPlace p = places.get(id);
            if (p == null) { row.error("地点 #" + id + " 不存在"); continue; }
            if (!Integer.valueOf(1).equals(p.getType()) || p.getMerchantId() != null) { row.error("地点 #" + id + " 不是官方地点"); continue; }
            if (p.getLat() == null || p.getLng() == null) { row.error("地点 #" + id + " 没有坐标"); continue; }
            if (p.getTemplateId() == null) withoutGame++;
            if (!Integer.valueOf(1).equals(p.getStatus())) notLive++;
        }
        if (withoutGame > 0) row.warn(withoutGame + " 个地点还没有游戏，这些站点导入后只是到访点");
        if (notLive > 0) row.warn(notLive + " 个地点还没上架，路线提交审核前请先让地点通过审核");
    }

    static BigDecimal mileageKm(List<SeedPlace> route) {
        double meters = 0;
        for (int i = 1; i < route.size(); i++)
            meters += SeedPlaceImportService.meters(route.get(i - 1).getLat(), route.get(i - 1).getLng(), route.get(i).getLat(), route.get(i).getLng());
        return BigDecimal.valueOf(meters / 1000.0).setScale(2, RoundingMode.HALF_UP);
    }

    public String template() {
        return "﻿" + String.join(",", HEADERS) + "\r\n"
                + "示例·湖滨慢行线（导入前删除）,easy,\"101;102;103;104\",沿湖四站，约 2 公里\r\n"
                + "示例·老城寻踪线（导入前删除）,standard,\"201;202;203;204;205\",老城五站，约 3 公里\r\n";
    }
}
