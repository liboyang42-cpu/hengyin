package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 点亮城市「点到店」:非参与地点(店/地标)到点记录。
 * 唯一键 (member_id, geo_key) —— 同一地点每人终身一次(即防重复刷探索值)。
 * O-02:开关 roam.visited_poi.stable_identity 打开后,身份改由 poi_key(唯一键 member_id, poi_key)承担,
 * 见 VisitedPoiIdentityResolver;geo_key 只作旧唯一键的占位,不再是 3 位小数坐标。
 *
 * @author cy
 * @date 2026-07-10
 */
public class MemberVisitedPoi {
    private Long id;
    private Long memberId;
    private String geoKey;
    /** O-02 稳定身份:poi:{roam_poi.id} 或 pt:{首访锚点 5 位小数};旧行未回填为 null */
    private String poiKey;
    private String poiName;
    private BigDecimal lat;
    private BigDecimal lng;
    private Date firstVisitTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public String getGeoKey() { return geoKey; }
    public void setGeoKey(String geoKey) { this.geoKey = geoKey; }

    public String getPoiKey() { return poiKey; }
    public void setPoiKey(String poiKey) { this.poiKey = poiKey; }

    public String getPoiName() { return poiName; }
    public void setPoiName(String poiName) { this.poiName = poiName; }

    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }

    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }

    public Date getFirstVisitTime() { return firstVisitTime; }
    public void setFirstVisitTime(Date firstVisitTime) { this.firstVisitTime = firstVisitTime; }
}
