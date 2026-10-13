package com.chengyinhub.business.domain.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 后台主题编辑请求白名单(PUT /business/topic)。
 *
 * 只放后台主题管理页真正会改的字段。状态轴字段(lifecycle / isSettlement / status / checkStatus /
 * userStatus / merchantStatus)
 * 与归属、票价、删除标记一律不在此列 —— 它们只能由各自的流程写:
 * lifecycle 走招募/定价(PricingServiceImpl),审核决定走统一审核台,delFlag 走 remove 接口。
 *
 * 收窄入参而非收全量实体,是因为 updateCmsTopic 走的是动态 SQL(字段非 null 即写),
 * 全字段 @RequestBody 等于把整张表的写权限交给了 business:topic:edit。
 *
 * 字段与后台页的对应:
 *   - 编辑主题内容弹窗:name / description / imgUrl / imgArr
 *   - 运营日期:startDate / endDate / merchantSignUpStartDate / merchantSignUpEndDate
 *   - 推荐弹窗:isRecommend
 * 日期格式与 CmsTopic 上的保持一致,避免透传行为漂移。
 */
public class CmsTopicEditDTO {

    private Long id;

    private String name;

    private String subtitle;

    private String description;

    private String imgUrl;

    private String imgArr;

    /**
     * @deprecated 仅为兼容存量客户端反序列化；Controller 永不读取。
     * 审核决定必须由统一审核台写入。
     */
    @Deprecated
    private Integer checkStatus;

    /** @deprecated 同 checkStatus，只保留兼容，不进入普通编辑。 */
    @Deprecated
    private String reason;

    /**
     * @deprecated 旧可见性轴已收为 lifecycle 派生只读；仅兼容存量请求反序列化，Controller 永不读取。
     */
    @Deprecated
    private Integer userStatus;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;

    /**
     * @deprecated 旧商家可见性轴已收为 lifecycle 派生只读；仅兼容存量请求反序列化，Controller 永不读取。
     */
    @Deprecated
    private Integer merchantStatus;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date merchantSignUpStartDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date merchantSignUpEndDate;

    private Integer isRecommend;

    /** 本期是否允许漫游首次发现卡导流：0关闭，1开启。 */
    private Integer roamReferralEnabled;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgArr() { return imgArr; }
    public void setImgArr(String imgArr) { this.imgArr = imgArr; }
    public Integer getCheckStatus() { return checkStatus; }
    public void setCheckStatus(Integer checkStatus) { this.checkStatus = checkStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getUserStatus() { return userStatus; }
    public void setUserStatus(Integer userStatus) { this.userStatus = userStatus; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Integer getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(Integer merchantStatus) { this.merchantStatus = merchantStatus; }
    public Date getMerchantSignUpStartDate() { return merchantSignUpStartDate; }
    public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) { this.merchantSignUpStartDate = merchantSignUpStartDate; }
    public Date getMerchantSignUpEndDate() { return merchantSignUpEndDate; }
    public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) { this.merchantSignUpEndDate = merchantSignUpEndDate; }
    public Integer getIsRecommend() { return isRecommend; }
    public void setIsRecommend(Integer isRecommend) { this.isRecommend = isRecommend; }
    public Integer getRoamReferralEnabled() { return roamReferralEnabled; }
    public void setRoamReferralEnabled(Integer roamReferralEnabled) { this.roamReferralEnabled = roamReferralEnabled; }
}
