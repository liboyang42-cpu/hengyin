package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 漫游集邮 UGC 邮票 roam_stamp。普通票 geohash7=NULL,盖销票(P1)一格一枚靠 uk。 */
public class RoamStamp {
    private Long id;
    private Long memberId;
    private String picUrl;
    private String caption;
    private Integer kind;
    private String geohash7;
    private BigDecimal lat;
    private BigDecimal lng;
    private String cityCode;
    private Integer checkState;
    private String checkTrace;
    private String idemKey;
    private Integer delFlag;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public Integer getKind() { return kind; }
    public void setKind(Integer kind) { this.kind = kind; }
    public String getGeohash7() { return geohash7; }
    public void setGeohash7(String geohash7) { this.geohash7 = geohash7; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Integer getCheckState() { return checkState; }
    public void setCheckState(Integer checkState) { this.checkState = checkState; }
    public String getCheckTrace() { return checkTrace; }
    public void setCheckTrace(String checkTrace) { this.checkTrace = checkTrace; }
    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
