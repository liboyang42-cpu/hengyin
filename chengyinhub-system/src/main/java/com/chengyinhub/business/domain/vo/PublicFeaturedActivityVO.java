package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;

import java.util.Date;

/** 商家公开主页的精选活动白名单。 */
public class PublicFeaturedActivityVO {

    private Long id;
    /**
     * 卡片类型:1=活动。前端(据点页/统一主页)据 featuredType/featuredId 判断卡片可点与跳转目标;
     * 缺了它们卡片能显示但点不动(S19-d)。
     */
    private Integer featuredType;
    /** 卡片指向的主体 ID(活动 ID),与 id 同值,供前端按统一合同取用。 */
    private Long featuredId;
    private String name;
    private String description;
    private String imgUrl;
    private String addressName;
    private String address;
    private Date startDate;
    private Date endDate;

    public static PublicFeaturedActivityVO from(CmsActivity activity) {
        PublicFeaturedActivityVO vo = new PublicFeaturedActivityVO();
        vo.setId(activity.getId());
        vo.setFeaturedType(1);
        vo.setFeaturedId(activity.getId());
        vo.setName(activity.getName());
        vo.setDescription(activity.getDescription());
        vo.setImgUrl(activity.getImgUrl());
        vo.setAddressName(activity.getAddressName());
        vo.setAddress(activity.getAddress());
        vo.setStartDate(activity.getStartDate());
        vo.setEndDate(activity.getEndDate());
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getFeaturedType() { return featuredType; }
    public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
    public Long getFeaturedId() { return featuredId; }
    public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
}
