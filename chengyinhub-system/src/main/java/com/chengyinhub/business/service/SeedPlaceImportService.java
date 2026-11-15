package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.dto.seed.SeedImportReport;
import com.chengyinhub.business.domain.dto.seed.SeedImportRow;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAM-09: bulk import / export of official places (roam_poi type=1, no merchant).
 *
 * <p>Every row is validated and checked against existing places (same name within a distance) before anything is
 * written; the preview and the commit run the same checks. A commit writes all new rows in one transaction or none.
 * Imported places are hidden drafts (status 0, audit_status 3): they reach players only after an operator attaches a
 * game in the official editor and the existing review approves it. Nothing is published by an import.
 */
@Service
public class SeedPlaceImportService {
    /** Column order of the template, the export and the preview. */
    public static final List<String> HEADERS = List.of("name", "category", "lat", "lng", "address", "cover_image_url",
            "tags", "opening_hours", "description", "city_code");
    static final Map<String, String> ALIASES = new LinkedHashMap<>();
    static {
        alias("name", "名称", "地点名称");
        alias("category", "分类", "类别");
        alias("lat", "纬度", "latitude");
        alias("lng", "经度", "longitude", "lon");
        alias("address", "地址");
        alias("cover_image_url", "封面", "封面图", "cover", "cover_url", "cover_img");
        alias("tags", "标签");
        alias("opening_hours", "开放时间", "营业时间", "hours");
        alias("description", "简介", "地点介绍", "介绍");
        alias("city_code", "城市码", "citycode");
    }
    public static final int DEFAULT_DEDUPE_METERS = 100;
    static final int NEARBY_WARNING_METERS = 15;

    private final SeedContentMapper mapper;
    private final IOfficialEventV2Service operators;

    public SeedPlaceImportService(SeedContentMapper mapper, IOfficialEventV2Service operators) {
        this.mapper = mapper; this.operators = operators;
    }

    private static void alias(String canonical, String... names) {
        ALIASES.put(canonical, canonical);
        for (String name : names) ALIASES.put(SeedTableReader.normalizeHeader(name), canonical);
    }

    @Transactional(rollbackFor = Exception.class)
    public SeedImportReport importPlaces(Long sysUserId, List<SeedTableReader.Line> lines, String defaultCityCode,
            Integer dedupeMeters, boolean dryRun) {
        if (!dryRun) {
            Long owner = operators.resolveAuthorizedOfficialMember(sysUserId);
            if (owner == null || owner <= 0) throw new ServiceException("请使用已授权的官方运营账号");
        }
        int radius = dedupeMeters == null ? DEFAULT_DEDUPE_METERS : dedupeMeters;
        if (radius < 10 || radius > 1000) throw new ServiceException("去重距离需在 10 到 1000 米之间");
        List<SeedImportRow> rows = new ArrayList<>();
        List<SeedPlace> places = new ArrayList<>();
        requireHeaders(lines);
        for (SeedTableReader.Line line : lines) {
            SeedImportRow row = new SeedImportRow();
            row.setRowNumber(line.number);
            Map<String, String> values = canonical(line.values);
            row.setValues(values);
            places.add(parse(values, defaultCityCode, row));
            rows.add(row);
        }
        dedupe(rows, places, radius);
        SeedImportReport report = SeedImportReport.of(rows, dryRun);
        if (dryRun) return report;
        if (report.getErrorCount() > 0) throw new ServiceException("有 " + report.getErrorCount() + " 行有错误，未导入任何地点");
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isNew()) continue;
            SeedPlace place = places.get(i);
            if (mapper.insertImportedPlace(place) != 1 || place.getId() == null)
                throw new ServiceException("第 " + rows.get(i).getRowNumber() + " 行保存失败，未导入任何地点");
            rows.get(i).setCreatedId(place.getId());
        }
        report.setCommitted(true);
        return report;
    }

    static Map<String, String> canonical(Map<String, String> raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String header : HEADERS) values.put(header, "");
        raw.forEach((key, value) -> {
            String canonical = ALIASES.get(key);
            if (canonical != null && (values.get(canonical).isEmpty())) values.put(canonical, value == null ? "" : value);
        });
        return values;
    }

    static void requireHeaders(List<SeedTableReader.Line> lines) {
        if (lines.isEmpty()) throw new ServiceException("文件里没有数据行");
        List<String> missing = new ArrayList<>();
        for (String required : List.of("name", "lat", "lng", "address")) {
            boolean found = lines.get(0).values.keySet().stream().anyMatch(key -> required.equals(ALIASES.get(key)));
            if (!found) missing.add(required);
        }
        if (!missing.isEmpty()) throw new ServiceException("缺少必填列：" + String.join("、", missing) + "（请用下载的模板）");
    }

    /** Validates one row into a place; problems go on the row, never thrown. */
    static SeedPlace parse(Map<String, String> v, String defaultCityCode, SeedImportRow row) {
        SeedPlace p = new SeedPlace();
        p.setName(text(v, "name", 80, true, "名称", row));
        p.setCategory(text(v, "category", 40, false, "分类", row));
        p.setAddress(text(v, "address", 255, true, "地址", row));
        p.setTags(text(v, "tags", 255, false, "标签", row));
        if (p.getTags() != null) p.setTags(p.getTags().replace('，', ',').replace('、', ','));
        p.setOpeningHours(text(v, "opening_hours", 120, false, "开放时间", row));
        p.setDescription(text(v, "description", 255, false, "简介", row));
        if (p.getDescription() == null) row.warn("没有简介：提交审核前需在地点编辑里补上地点介绍");
        String city = v.getOrDefault("city_code", "");
        if (city.isEmpty() && defaultCityCode != null) city = defaultCityCode.trim();
        if (city.isEmpty()) row.error("缺少城市码（在本行填写，或在导入时统一填写）");
        else if (city.length() > 20) row.error("城市码过长");
        else p.setCityCode(city);
        BigDecimal lat = number(v.get("lat"), "纬度", row);
        BigDecimal lng = number(v.get("lng"), "经度", row);
        if (lat != null && lng != null) {
            if (inChina(lng, lat) && !inChina(lat, lng)) row.error("纬度和经度像是填反了");
            else if (!inChina(lat, lng)) row.error("坐标不在中国境内，请填 GCJ-02（高德/腾讯）经纬度");
            else { p.setLat(lat.setScale(6, RoundingMode.HALF_UP)); p.setLng(lng.setScale(6, RoundingMode.HALF_UP)); }
        }
        String cover = v.getOrDefault("cover_image_url", "");
        if (!cover.isEmpty()) {
            try {
                OfficialRoamPoiService.asset(cover);
                p.setCoverImg(cover); p.setGalleryJson(JSON.toJSONString(Collections.singletonList(cover)));
            } catch (ServiceException notPlatformImage) {
                row.warn("封面不是平台上传的图片，未导入封面；配置地点游戏时请上传照片");
            }
        } else row.warn("没有封面：提交审核前需上传地点照片");
        return p;
    }

    private static String text(Map<String, String> v, String key, int limit, boolean required, String label, SeedImportRow row) {
        String value = v.getOrDefault(key, "").trim();
        if (value.isEmpty()) { if (required) row.error("缺少" + label); return null; }
        if (value.length() > limit) { row.error(label + "超过 " + limit + " 字"); return null; }
        return value;
    }

    private static BigDecimal number(String raw, String label, SeedImportRow row) {
        if (raw == null || raw.trim().isEmpty()) { row.error("缺少" + label); return null; }
        try { return new BigDecimal(raw.trim()); }
        catch (NumberFormatException e) { row.error(label + "不是数字"); return null; }
    }

    /** Rough mainland/HK/Macau/Taiwan box: GCJ-02 is only defined there. */
    static boolean inChina(BigDecimal lat, BigDecimal lng) {
        double a = lat.doubleValue(), o = lng.doubleValue();
        return a >= 3.8 && a <= 53.6 && o >= 73.5 && o <= 135.1;
    }

    /** Same normalised name within {@code radius} metres of an existing place, or of an earlier row, is a duplicate. */
    void dedupe(List<SeedImportRow> rows, List<SeedPlace> places, int radius) {
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        boolean any = false;
        for (SeedPlace p : places) {
            if (p.getLat() == null || p.getLng() == null) continue;
            any = true;
            minLat = Math.min(minLat, p.getLat().doubleValue()); maxLat = Math.max(maxLat, p.getLat().doubleValue());
            minLng = Math.min(minLng, p.getLng().doubleValue()); maxLng = Math.max(maxLng, p.getLng().doubleValue());
        }
        if (!any) return;
        double pad = radius / 90000.0 + 0.001;
        List<SeedPlace> existing = mapper.selectPlacesInBox(bd(minLat - pad), bd(maxLat + pad), bd(minLng - pad), bd(maxLng + pad));
        markDuplicates(rows, places, existing == null ? List.of() : existing, radius);
    }

    static void markDuplicates(List<SeedImportRow> rows, List<SeedPlace> places, List<SeedPlace> existing, int radius) {
        for (int i = 0; i < rows.size(); i++) {
            SeedPlace p = places.get(i);
            SeedImportRow row = rows.get(i);
            if (p.getLat() == null || p.getLng() == null || p.getName() == null) continue;
            String key = nameKey(p.getName());
            SeedPlace nearest = null;
            double nearestMeters = Double.MAX_VALUE;
            for (SeedPlace e : existing) {
                if (e.getLat() == null || e.getLng() == null) continue;
                double meters = meters(p.getLat(), p.getLng(), e.getLat(), e.getLng());
                if (meters <= radius && key.equals(nameKey(e.getName()))) { row.duplicateOf(e.getId(), e.getName()); break; }
                if (meters < nearestMeters) { nearestMeters = meters; nearest = e; }
            }
            if (SeedImportRow.DUPLICATE.equals(row.getOutcome())) continue;
            for (int j = 0; j < i; j++) {
                SeedPlace earlier = places.get(j);
                if (!rows.get(j).isNew() || earlier.getLat() == null || earlier.getLng() == null || earlier.getName() == null) continue;
                if (key.equals(nameKey(earlier.getName()))
                        && meters(p.getLat(), p.getLng(), earlier.getLat(), earlier.getLng()) <= radius) {
                    row.duplicateOf(null, "本文件第 " + rows.get(j).getRowNumber() + " 行");
                    break;
                }
            }
            if (row.isNew() && nearest != null && nearestMeters <= NEARBY_WARNING_METERS)
                row.warn("距已有地点「" + nearest.getName() + "」(#" + nearest.getId() + ") 仅 " + Math.round(nearestMeters) + " 米，请确认不是同一处");
        }
    }

    static String nameKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[\\s·・•()（）\\-_—,，.。]", "");
    }

    public static double meters(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        return meters(lat1.doubleValue(), lng1.doubleValue(), lat2.doubleValue(), lng2.doubleValue());
    }

    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static BigDecimal bd(double value) { return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP); }

    /** Template with the same columns as the export and two example rows (delete them before importing). */
    public void writeTemplate(OutputStream out) throws IOException {
        StringBuilder csv = new StringBuilder("﻿").append(String.join(",", HEADERS)).append("\r\n");
        csv.append("示例·城市公园（导入前删除）,公园,30.259300,120.148900,示例路 1 号,,\"公园,亲子\",06:00-22:00,湖边步道与草坪，适合散步拍照,330100\r\n");
        csv.append("示例·老街书店（导入前删除）,文化,30.261200,120.163400,示例街 88 号,,\"书店,文艺\",10:00-21:00,百年老街里的独立书店,330100\r\n");
        out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Export of official places in the import format (UTF-8 with BOM so spreadsheet apps read Chinese). */
    public void writeExport(String cityCode, OutputStream out) throws IOException {
        StringBuilder csv = new StringBuilder("﻿").append(String.join(",", HEADERS)).append("\r\n");
        for (SeedPlace p : mapper.selectOfficialPlacesForExport(cityCode == null || cityCode.isBlank() ? null : cityCode.trim())) {
            Object[] cells = {p.getName(), p.getCategory(), plain(p.getLat()), plain(p.getLng()), p.getAddress(),
                    p.getCoverImg(), p.getTags(), p.getOpeningHours(), p.getDescription(), p.getCityCode()};
            for (int i = 0; i < cells.length; i++) { if (i > 0) csv.append(','); csv.append(SeedTableReader.csvCell(cells[i])); }
            csv.append("\r\n");
        }
        out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(BigDecimal value) { return value == null ? null : value.toPlainString(); }
}
