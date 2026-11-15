package com.chengyinhub.business.citygame;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Date;

/**
 * 领地与城市大富翁原始定位的 30 天留存（HIGH-S7）。只抹掉原始经纬度与精度，保留距离与判定。
 * roam_territory_checkin 的经纬度列为 NOT NULL，粗化到 2 位小数（约 1 km）；其余可空列直接置空，
 * 审计 detail_json 里的 prevLat/prevLng 一并移除。每批独立提交，WHERE 排除已处理行，可重跑。
 */
@Service
public class CityLocationRetentionService {

    static final String COARSEN_CHECKINS = "UPDATE roam_territory_checkin "
            + "SET latitude = ROUND(latitude, 2), longitude = ROUND(longitude, 2), accuracy_m = NULL "
            + "WHERE create_time < ? "
            + "AND (latitude <> ROUND(latitude, 2) OR longitude <> ROUND(longitude, 2) OR accuracy_m IS NOT NULL) "
            + "LIMIT ?";

    static final String CLEAR_AUDITS = "UPDATE roam_territory_audit "
            + "SET latitude = NULL, longitude = NULL, accuracy_m = NULL, "
            + "detail_json = JSON_REMOVE(detail_json, '$.prevLat', '$.prevLng') "
            + "WHERE create_time < ? "
            + "AND (latitude IS NOT NULL OR longitude IS NOT NULL OR accuracy_m IS NOT NULL "
            + "OR JSON_CONTAINS_PATH(detail_json, 'one', '$.prevLat', '$.prevLng')) "
            + "LIMIT ?";

    static final String CLEAR_EVIDENCE = "UPDATE city_claim_evidence "
            + "SET lat = NULL, lng = NULL, h_acc = NULL "
            + "WHERE created_at < ? AND (lat IS NOT NULL OR lng IS NOT NULL OR h_acc IS NOT NULL) "
            + "LIMIT ?";

    private final JdbcTemplate jdbc;

    public CityLocationRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int coarsenTerritoryCheckins(Date cutoff, int limit) {
        return jdbc.update(COARSEN_CHECKINS, new Timestamp(cutoff.getTime()), limit);
    }

    public int clearTerritoryAudits(Date cutoff, int limit) {
        return jdbc.update(CLEAR_AUDITS, new Timestamp(cutoff.getTime()), limit);
    }

    public int clearClaimEvidence(Date cutoff, int limit) {
        return jdbc.update(CLEAR_EVIDENCE, new Timestamp(cutoff.getTime()), limit);
    }
}
