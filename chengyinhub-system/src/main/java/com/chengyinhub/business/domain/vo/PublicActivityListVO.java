package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** 公开活动列表白名单。 */
public class PublicActivityListVO {
    private Long id;
    private Long memberId;
    private Long topicId;
    /** 所属主题的产品类型:1 经典定向 / 2 自由探索(探店日)。
     *  瞬态取自 cms_topic.product_type(cms_activity 无此列);无关联主题时为 null。 */
    private Integer productType;
    private String name;
    private String description;
    private String imgUrl;
    private String imgArr;
    private String categoryIds;
    private String addressName;
    private String longitude;
    private String latitude;
    private String address;
    private String distance;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;
    private Integer isLiked;
    private Integer isFollowTheUser;
    private BigDecimal minAmout;
    private Long viewCount;
    private Double rating;
    private Integer ratingTotal;
    private List<SysCategory> sysCategoryList;

    public static PublicActivityListVO from(CmsActivity source) {
        PublicActivityListVO out = new PublicActivityListVO();
        if (source != null) {
            out.id = source.getId(); out.memberId = source.getMemberId(); out.topicId = source.getTopicId();
            out.name = source.getName(); out.description = source.getDescription(); out.imgUrl = source.getImgUrl();
            out.imgArr = source.getImgArr(); out.categoryIds = source.getCategoryIds(); out.addressName = source.getAddressName();
            out.longitude = source.getLongitude(); out.latitude = source.getLatitude(); out.address = source.getAddress();
            out.distance = source.getDistance(); out.startDate = source.getStartDate(); out.endDate = source.getEndDate();
            out.isLiked = source.getIsLiked(); out.isFollowTheUser = source.getIsFollowTheUser(); out.minAmout = source.getMinAmout();
            out.viewCount = source.getViewCount(); out.rating = source.getRating(); out.ratingTotal = source.getRatingTotal();
            out.sysCategoryList = source.getSysCategoryList();
            out.productType = source.getProductType();
        }
        return out;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Long getTopicId() { return topicId; }
    public Integer getProductType() { return productType; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getImgUrl() { return imgUrl; }
    public String getImgArr() { return imgArr; }
    public String getCategoryIds() { return categoryIds; }
    public String getAddressName() { return addressName; }
    public String getLongitude() { return longitude; }
    public String getLatitude() { return latitude; }
    public String getAddress() { return address; }
    public String getDistance() { return distance; }
    public Date getStartDate() { return startDate; }
    public Date getEndDate() { return endDate; }
    public Integer getIsLiked() { return isLiked; }
    public Integer getIsFollowTheUser() { return isFollowTheUser; }
    public BigDecimal getMinAmout() { return minAmout; }
    public Long getViewCount() { return viewCount; }
    public Double getRating() { return rating; }
    public Integer getRatingTotal() { return ratingTotal; }
    public List<SysCategory> getSysCategoryList() { return sysCategoryList; }
}
