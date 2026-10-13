package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/** 小程序主理人自助资料；等级、状态、归属与审计字段均由服务端持有。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClubLeaderProfileRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String leaderName;
    private String phone;
    private String identity;
    private String coFounders;
    private Integer hasExperience;
    private String experience;
    private Integer maxEventSize;
    private Integer avgEventSize;
    private Integer canDesignRoute;
    private Integer canDesignTask;
    private Integer canNpc;
    private Integer canMerchantCoop;
    private Integer hasGuideCert;
    private String certImages;

    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }
    public String getCoFounders() { return coFounders; }
    public void setCoFounders(String coFounders) { this.coFounders = coFounders; }
    public Integer getHasExperience() { return hasExperience; }
    public void setHasExperience(Integer hasExperience) { this.hasExperience = hasExperience; }
    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }
    public Integer getMaxEventSize() { return maxEventSize; }
    public void setMaxEventSize(Integer maxEventSize) { this.maxEventSize = maxEventSize; }
    public Integer getAvgEventSize() { return avgEventSize; }
    public void setAvgEventSize(Integer avgEventSize) { this.avgEventSize = avgEventSize; }
    public Integer getCanDesignRoute() { return canDesignRoute; }
    public void setCanDesignRoute(Integer canDesignRoute) { this.canDesignRoute = canDesignRoute; }
    public Integer getCanDesignTask() { return canDesignTask; }
    public void setCanDesignTask(Integer canDesignTask) { this.canDesignTask = canDesignTask; }
    public Integer getCanNpc() { return canNpc; }
    public void setCanNpc(Integer canNpc) { this.canNpc = canNpc; }
    public Integer getCanMerchantCoop() { return canMerchantCoop; }
    public void setCanMerchantCoop(Integer canMerchantCoop) { this.canMerchantCoop = canMerchantCoop; }
    public Integer getHasGuideCert() { return hasGuideCert; }
    public void setHasGuideCert(Integer hasGuideCert) { this.hasGuideCert = hasGuideCert; }
    public String getCertImages() { return certImages; }
    public void setCertImages(String certImages) { this.certImages = certImages; }
}
