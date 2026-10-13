package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 点亮城市「点到店」:非参与地点(店/地标)到点记录。
 * 唯一键 (member_id, geo_key) —— 同一地点每人终身一次(即防重复刷探索值)。
 *
 * @author cy
 * @date 2026-07-10
 */
public class MemberVisitedPoi {
    private Long id;
    private Long memberId;
    private String geoKey;
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

    public String getPoiName() { return poiName; }
    public void setPoiName(String poiName) { this.poiName = poiName; }

    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }

    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }

    public Date getFirstVisitTime() { return firstVisitTime; }
    public void setFirstVisitTime(Date firstVisitTime) { this.firstVisitTime = firstVisitTime; }
}
