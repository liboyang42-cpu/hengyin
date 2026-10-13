package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.chengyinhub.business.domain.RoamStamp;

/** 小程序集邮册安全视图：不暴露 checkTrace、idemKey、memberId 等内部关联字段。 */
public class RoamStampListVO
{
    private Long id;
    private String picUrl;
    private String caption;
    private Integer kind;
    private String geohash7;
    private BigDecimal lat;
    private BigDecimal lng;
    private String cityCode;
    private Integer checkState;
    private Date createTime;

    public static RoamStampListVO from(RoamStamp source)
    {
        RoamStampListVO target = new RoamStampListVO();
        target.id = source.getId();
        target.picUrl = source.getPicUrl();
        target.caption = source.getCaption();
        target.kind = source.getKind();
        target.geohash7 = source.getGeohash7();
        target.lat = source.getLat();
        target.lng = source.getLng();
        target.cityCode = source.getCityCode();
        target.checkState = source.getCheckState();
        target.createTime = source.getCreateTime();
        return target;
    }

    public Long getId() { return id; }
    public String getPicUrl() { return picUrl; }
    public String getCaption() { return caption; }
    public Integer getKind() { return kind; }
    public String getGeohash7() { return geohash7; }
    public BigDecimal getLat() { return lat; }
    public BigDecimal getLng() { return lng; }
    public String getCityCode() { return cityCode; }
    public Integer getCheckState() { return checkState; }
    public Date getCreateTime() { return createTime; }
}
