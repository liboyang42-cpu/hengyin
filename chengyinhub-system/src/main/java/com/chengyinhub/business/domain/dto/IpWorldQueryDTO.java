package com.chengyinhub.business.domain.dto;

/**
 * P3 IP世界查询。
 */
public class IpWorldQueryDTO {
    private Long worldId;
    private String cityCode;

    public Long getWorldId() { return worldId; }
    public void setWorldId(Long worldId) { this.worldId = worldId; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
}
