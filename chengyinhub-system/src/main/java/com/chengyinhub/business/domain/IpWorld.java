package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 IP世界 ip_world。
 */
public class IpWorld extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String worldCode;
    private String worldName;
    private String subtitle;
    private String cityCode;
    private String coverUrl;
    private String intro;
    private String status;
    private String deleted;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWorldCode() { return worldCode; }
    public void setWorldCode(String worldCode) { this.worldCode = worldCode; }
    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDeleted() { return deleted; }
    public void setDeleted(String deleted) { this.deleted = deleted; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
