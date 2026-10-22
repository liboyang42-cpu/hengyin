package com.chengyinhub.business.domain;

import com.chengyinhub.system.domain.SysCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.List;

/**
 * 商家对象 mms_merchant
 * 
 * @author ruoyi
 * @date 2025-08-22
 */
public class MmsMerchant extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** params 键:显式把 capacity 置 NULL(Integer 无法用 null 区分「不改」与「清空」) */
    public static final String CLEAR_CAPACITY = "clearCapacity";

    /** $column.columnComment */
    private Long id;

    /** 会员ID */
    @Excel(name = "会员ID")
    private Long memberId;

    /** 商家名称 */
    @Excel(name = "商家名称")
    private String name;

    /** 门店微信号(商家手填的微信 ID;2026-10-22 起不再存二维码 URL) */
    private String wechat;

    /** 门店微信二维码图片 URL(migration_merchant_wechat_qrcode_20261022) */
    private String wechatQrcode;

    /** 主行业类型;多选时为 categoryIds 的第一个,供只认单类目的旧读者使用 */
    private Integer categoryId;

    /** 行业类型多选(逗号分隔的 sys_category.id,migration_merchant_category_ids_20261022) */
    private String categoryIds;

    /** 商家LOGO */
    @Excel(name = "商家LOGO")
    private String logo;

    /** 介绍 */
    @Excel(name = "介绍")
    private String description;

    /** 衍生品 */
    @Excel(name = "衍生品")
    private String derivatives;

    /** 装修页衍生权益文字；derivatives 保持为注册品牌图片。 */
    private String derivativeBenefits;

    public String getDerivativeBenefits() { return derivativeBenefits; }
    public void setDerivativeBenefits(String value) { this.derivativeBenefits = value; }

    /** 品牌网址 */
    @Excel(name = "品牌网址")
    private String website;

    /** 品牌偏好 */
    @Excel(name = "品牌偏好")
    private String preference;


    /** 联系电话 */
    @Excel(name = "联系电话")
    private String phone;

    /** 商户地址 */
    @Excel(name = "商户地址")
    private String address;

    /** 营业时间 */
    @Excel(name = "营业时间")
    private String businessTime;

    /** 营业执照 */
    @Excel(name = "营业执照")
    private String businessLicense;

    /** 入住审核状态0 待审核  1审核通过  2审核失败 */
    @Excel(name = "入住审核状态0 待审核  1审核通过  2审核失败")
    private Long status;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String reson;

    /** 平台停用原因:只由平台停用入口写入,启用时清空;审核通过/驳回只写 reson,不复用本列 */
    @Excel(name = "平台停用原因")
    private String disableReason;

    /** 账号状态：1启用  2禁用 */
    @Excel(name = "账号状态：1启用  2禁用")
    private Long accountStatus;

    /** 营业状态 1营业中 0已打烊(纯展示,不做门禁) */
    private Integer businessStatus;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 可容纳/承接人数(承接档案) */
    private Integer capacity;

    /** 可承接时段(承接档案) */
    private String availableTime;

    /** 适合/想承接的活动类型(逗号分隔) */
    private String suitActivityTypes;

    /** 承接收费 0免费 1收费 */
    private Integer chargeType;

    /** 诉求(想要什么) */
    private String demand;

    /** 是否开放被达人对接 0否1是 */
    private Integer coopOpen;

    /** 店铺装修-封面头图URL */
    private String coverImage;

    /** 店铺装修-一句话slogan */
    private String slogan;

    /** 店铺装修-城市角色名 */
    private String cityRole;

    /** 店铺装修-品牌故事标题(正文用description) */
    private String storyTitle;

    /** 店铺装修-门店相册JSON数组 */
    private String gallery;

    /** 店铺装修-特色标签JSON/逗号 */
    private String tags;

    /** 店铺装修-招牌主推类型 0无/1活动主题/2券 */
    private Integer featuredType;

    /** 店铺装修-招牌主推目标id */
    private Long featuredId;

    /** 店铺装修-服务行短标签(如¥5/免费) */
    private String serviceTag;

    /** 店铺装修-服务行文案(如健身体验卡),空=不展示服务行 */
    private String serviceText;

    /** 商家纬度(手动打点/LBS) */
    private Double locationLat;

    /** 商家经度 */
    private Double locationLng;

    /** 0未定位 1已定位 */
    private Integer locationVerified;

    @Schema(description = "分类列表")
    private List<SysCategory> sysCategoryList;

    public String getWechat() {
        return wechat;
    }

    public void setWechat(String wechat) {
        this.wechat = wechat;
    }

    public String getWechatQrcode() {
        return wechatQrcode;
    }

    public void setWechatQrcode(String wechatQrcode) {
        this.wechatQrcode = wechatQrcode;
    }

    public String getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(String categoryIds) {
        this.categoryIds = categoryIds;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(String businessTime) {
        this.businessTime = businessTime;
    }

    public String getBusinessLicense() {
        return businessLicense;
    }

    public void setBusinessLicense(String businessLicense) {
        this.businessLicense = businessLicense;
    }

    public List<SysCategory> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<SysCategory> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }




    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setLogo(String logo) 
    {
        this.logo = logo;
    }

    public String getLogo() 
    {
        return logo;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setDerivatives(String derivatives) 
    {
        this.derivatives = derivatives;
    }

    public String getDerivatives() 
    {
        return derivatives;
    }

    public void setWebsite(String website) 
    {
        this.website = website;
    }

    public String getWebsite() 
    {
        return website;
    }

    public void setPreference(String preference) 
    {
        this.preference = preference;
    }

    public String getPreference() 
    {
        return preference;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setReson(String reson) 
    {
        this.reson = reson;
    }

    public String getReson() 
    {
        return reson;
    }

    public void setDisableReason(String disableReason)
    {
        this.disableReason = disableReason;
    }

    public String getDisableReason()
    {
        return disableReason;
    }

    public void setBusinessStatus(Integer businessStatus)
    {
        this.businessStatus = businessStatus;
    }

    public Integer getBusinessStatus()
    {
        return businessStatus;
    }

    public void setAccountStatus(Long accountStatus)
    {
        this.accountStatus = accountStatus;
    }

    public Long getAccountStatus()
    {
        return accountStatus;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag()
    {
        return delFlag;
    }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public String getAvailableTime() { return availableTime; }
    public void setAvailableTime(String availableTime) { this.availableTime = availableTime; }
    public String getSuitActivityTypes() { return suitActivityTypes; }
    public void setSuitActivityTypes(String suitActivityTypes) { this.suitActivityTypes = suitActivityTypes; }
    public Integer getChargeType() { return chargeType; }
    public void setChargeType(Integer chargeType) { this.chargeType = chargeType; }
    public String getDemand() { return demand; }
    public void setDemand(String demand) { this.demand = demand; }
    public Integer getCoopOpen() { return coopOpen; }
    public void setCoopOpen(Integer coopOpen) { this.coopOpen = coopOpen; }
    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }
    public String getSlogan() { return slogan; }
    public void setSlogan(String slogan) { this.slogan = slogan; }
    public String getCityRole() { return cityRole; }
    public void setCityRole(String cityRole) { this.cityRole = cityRole; }
    public String getStoryTitle() { return storyTitle; }
    public void setStoryTitle(String storyTitle) { this.storyTitle = storyTitle; }
    public String getGallery() { return gallery; }
    public void setGallery(String gallery) { this.gallery = gallery; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Integer getFeaturedType() { return featuredType; }
    public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
    public Long getFeaturedId() { return featuredId; }
    public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    public String getServiceTag() { return serviceTag; }
    public void setServiceTag(String serviceTag) { this.serviceTag = serviceTag; }
    public String getServiceText() { return serviceText; }
    public void setServiceText(String serviceText) { this.serviceText = serviceText; }
    public Double getLocationLat() { return locationLat; }
    public void setLocationLat(Double locationLat) { this.locationLat = locationLat; }
    public Double getLocationLng() { return locationLng; }
    public void setLocationLng(Double locationLng) { this.locationLng = locationLng; }
    public Integer getLocationVerified() { return locationVerified; }
    public void setLocationVerified(Integer locationVerified) { this.locationVerified = locationVerified; }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("name", getName())
            .append("logo", getLogo())
            .append("description", getDescription())
            .append("derivatives", getDerivatives())
            .append("website", getWebsite())
            .append("preference", getPreference())
            .append("status", getStatus())
            .append("reson", getReson())
            .append("disableReason", getDisableReason())
            .append("accountStatus", getAccountStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
