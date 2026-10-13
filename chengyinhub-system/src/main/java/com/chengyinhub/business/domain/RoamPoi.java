package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** P2 漫游 POI roam_poi(type=1 地标/公园 · 2 商户招牌)。 */
public class RoamPoi {
    private Long id;
    private String name;
    private Integer type;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer radiusM;
    private String address;
    private String description;
    private Integer xp;
    private Long couponTemplateId;
    private String cityCode;
    private Integer status;
    private Integer auditStatus;
    private Integer applicationType;
    private Long applicantMerchantId;
    private String auditReason;
    private Date createTime;

    // --- 商户据点(type=2)字段 ---
    private Long merchantId;
    private Long templateId;
    private Integer nodeLevel;
    private String tags;
    private String coverImg;
    private Integer sort;

    /** 后台治理列表连表展示:商家门店名(连 mms_merchant —— 本表 merchant_id 是 mms_merchant.id 不是 memberId) */
    private String merchantName;
    /** 门店 AI 形象头像(npc_profile scope 2)。仅 selectPoisNear 联查填充,其余查询恒 null。 */
    private String npcAvatar;

    /**
     * C-13 该账号是否已发现过这个 POI。仅玩家侧 selectPoisNear 投影(查 roam_poi_found /
     * roam_poi_reward_log 的真源),其余查询恒 null —— 「换设备不回到迷雾」靠它。
     */
    private Boolean found;

    /**
     * CU-M-54 该账号是否「已打卡但券还没核销」(roam_poi_reward_log.status=0 且 coupon_id>0)。
     * 仅玩家侧 selectPoisNear 投影,其余查询恒 null。
     *
     * ★ 为什么必须单独一档:found 把 status=0/1 都当「到过店」,客户端只看得见 found ⇒
     * 待核销在重进地图后只能读成 passed→candidate —— 同一站在地图上先亮后灭,
     * 而详情里仍能出示核销码。这一档不是「已完成」:券要商家扫码才发。
     */
    private Boolean pendingRedeem;

    /**
     * 审核材料:商家资料 + 这个据点配的玩法内容。只在后台审核列表里由控制器装配,
     * 不落库、不进玩家侧任何投影。
     *
     * ★ 为什么必须有:原来审核列表只给「模板 #ID · 券 #ID · 半径 80m」,
     * 而据点审核要判的恰恰是「这个玩法跑不跑得起来、题目合不合规」——
     * 拿一个 id 是判不了的,只能靠人去数据库里翻。
     */
    private java.util.Map<String, Object> reviewMaterial;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer radiusM) { this.radiusM = radiusM; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getXp() { return xp; }
    public void setXp(Integer xp) { this.xp = xp; }
    public Long getCouponTemplateId() { return couponTemplateId; }
    public void setCouponTemplateId(Long couponTemplateId) { this.couponTemplateId = couponTemplateId; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Integer auditStatus) { this.auditStatus = auditStatus; }
    public Integer getApplicationType() { return applicationType; }
    public void setApplicationType(Integer applicationType) { this.applicationType = applicationType; }
    public Long getApplicantMerchantId() { return applicantMerchantId; }
    public void setApplicantMerchantId(Long applicantMerchantId) { this.applicantMerchantId = applicantMerchantId; }
    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getNodeLevel() { return nodeLevel; }
    public void setNodeLevel(Integer nodeLevel) { this.nodeLevel = nodeLevel; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Boolean getPendingRedeem() { return pendingRedeem; }
    public void setPendingRedeem(Boolean pendingRedeem) { this.pendingRedeem = pendingRedeem; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getNpcAvatar() { return npcAvatar; }
    public void setNpcAvatar(String npcAvatar) { this.npcAvatar = npcAvatar; }
    public Boolean getFound() { return found; }
    public void setFound(Boolean found) { this.found = found; }
    public java.util.Map<String, Object> getReviewMaterial() { return reviewMaterial; }
    public void setReviewMaterial(java.util.Map<String, Object> reviewMaterial) { this.reviewMaterial = reviewMaterial; }
}
