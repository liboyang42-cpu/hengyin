package com.chengyinhub.business.domain.dto.seed;

import java.math.BigDecimal;

/** A located thing counted by the district density report; flag1/flag2 carry per-metric booleans. */
public class SeedGeoPoint {
    private Long id;
    private String key;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer flag1;
    private Integer flag2;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public Integer getFlag1() { return flag1; }
    public void setFlag1(Integer flag1) { this.flag1 = flag1; }
    public Integer getFlag2() { return flag2; }
    public void setFlag2(Integer flag2) { this.flag2 = flag2; }
}
