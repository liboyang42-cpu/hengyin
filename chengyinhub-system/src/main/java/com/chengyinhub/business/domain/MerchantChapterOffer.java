package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;
import com.chengyinhub.common.constant.CoopTermsAxes;

/**
 * 商家×章节生效供给 merchant_chapter_offer(施工图 §3.2 · 执行层)。
 * 与 CoopPerk 的分工:CoopPerk 是申报层「商家有什么」,本表是执行层「这一章真正在供给什么」——
 * coop_perk 挂 invite 维度(一个 invite 覆盖整个主题),答不出「这份权益是哪一章的」,故另立本表补章节维。
 */
public class MerchantChapterOffer extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 冗余主题id:按主题聚合容量时免去 join 章节表 */
    private Long topicId;
    private Long chapterId;
    /** 本次承接所补位的原退出供给；由主办邀请与服务端确认链写入。 */
    private Long replacementOfOfferId;
    /** ⚠️ 商家memberId,不是 mms_merchant.id —— 库里「商家」一词被三个东西共用,语义超载曾致一次资损 */
    private Long merchantId;
    /** 引用 coop_perk.id(申报层)。⚠️ 仅 PERK 档非空 —— TRAFFIC/REVSHARE 没有权益可引用 */
    private Long perkId;
    /**
     * 仅开放招商权益档的请求字段，不落 merchant_chapter_offer。
     * Service 会校验模板归属后生成独立 coop_perk 快照，再把生成的 id 写入 perkId。
     */
    private Long perkTemplateId;
    /**
     * D21 条款档快照({@link com.chengyinhub.common.constant.TermsMode}),入驻时须与所属章节的 terms_mode 一致。
     * ⚠️ 这是**快照**:章节改档不追溯改写存量 offer(同 P0-06/P0-28 快照纪律)⇒ 与 chapter.termsMode 可能不一致。
     * 判「这章是不是计酬档」读 chapter.termsMode;判「这家商家按什么条款拿钱」读本列。
     */
    private String termsMode;
    /** 服务端承接时冻结的俱乐部直付主体；NULL保留历史平台采购语义。 */
    private Long payerClubId;
    /**
     * REVSHARE 档:每核销人次的固定人头费(条款快照)。仅 REVSHARE 非空。
     * ⚠️ 平台采购支出,非分账,与票价无关且可能 > 票价 —— 别塞进 settlement_detail 的 Σ明细==gross 模型。
     */
    private BigDecimal perHeadFee;
    /** 本offer可核销总份数 */
    private Integer quotaTotal;
    /** 已核销份数;核销时由 consumeQuota 条件update原子+1,不在应用层读改写 */
    private Integer quotaUsed;
    /** 供给有效期(空=不限) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validEnd;
    /**
     * 承接时冻结的「允许的验证方式」快照,逗号分隔。NULL = 不限。
     * ★ 为什么要冻:章节条款主办方随时可改。不冻的话商家按 A 条款承接、
     * 主办方事后改成 B,他已上线的节点会突然「超标」。冻结后改章节只影响后来者。
     * (与 coop_perk 快照、valid_end 强制复制同一范式。)
     */
    private String allowedValidationMethods;

    /** 承接时冻结的单节点探索值上限快照。NULL = 不限。 */
    private Integer maxNodeXp;

    /** 1生效 0失效。商家退出标失效而非删行:核销流水引用 offer_id,删了就断链查不出历史 */
    private Integer status;

    /** 商家主动退出理由；停用供给仍保留原条款、已用份数和核销引用。 */
    private String withdrawReason;
    /** 退出时该章是否必选；主办事后修改章节不能抹掉暂停义务。 */
    private Boolean withdrawRequired;

    /** 圈层候选入驻时一并提交；独立落 merchant_circle_supply_profile，不落本表。 */
    private MerchantCircleSupplyProfile circleSupplyProfile;

    /** 由 termsMode 确定性派生的只读两轴 DTO 字段，不落独立列。 */
    private String compensationMode;
    private String benefitMode;

    /* ---------- 以下为后台审核台队列的连表展示字段,非本表列(只读,insert/update 不认) ---------- */

    /** 主题名(连 cms_topic) */
    private String topicName;
    /** 主题生命周期(连 cms_topic;≥3=已锁价 ⇒ 审核台提示条款已冻结) */
    private Integer topicLifecycle;
    /** 章节名(连 cms_topic_chapter) */
    private String chapterName;
    /** 章节口径档(连 cms_topic_chapter.terms_mode)。判「这章什么档」看它;本行 termsMode 是快照,两者可不一致 */
    private String chapterTermsMode;
    /** 章节计酬人头费上限(连 cms_topic_chapter) */
    private BigDecimal chapterMaxPerHeadFee;
    /** 章节权益零售价门槛(连 cms_topic_chapter) */
    private BigDecimal chapterPerkMinValue;
    /** 章节承接商家数上限(连 cms_topic_chapter) */
    private Integer chapterMaxMerchant;
    /** 商家昵称(连 ums_member) */
    private String merchantName;
    /** 权益名(连 coop_perk) */
    private String perkName;
    /** 权益零售标价(连 coop_perk) */
    private BigDecimal perkRetailValue;

    /** 商家门店名(连 mms_merchant,member_id 同口径;多行取最新一行) */
    private String mmsName;
    /** 商家入驻审核状态(连 mms_merchant.status,字典 sys_merchant_status) */
    private Long mmsStatus;
    /** 营业执照图(连 mms_merchant)——引流档「只审接待资质」的审核落点 */
    private String businessLicense;
    /** 商家电话(连 mms_merchant) */
    private String mmsPhone;
    /** 商家地址(连 mms_merchant) */
    private String mmsAddress;
    /** 商家接待能力(连 mms_merchant.capacity) */
    private Integer mmsCapacity;
    /** 履约:该商家全平台权益档已核销人次 Σquota_used(含过期/停用——历史就是历史) */
    private Integer merchantPerkUsed;
    /** 履约:该商家全平台权益档接待上限 Σquota_total(口径同上) */
    private Integer merchantPerkTotal;
    /** 履约:计酬档净核销笔数(merchant_payable 的 ACCRUAL−REVERSAL) */
    private Integer merchantRevshareCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getReplacementOfOfferId() { return replacementOfOfferId; }
    public void setReplacementOfOfferId(Long value) { this.replacementOfOfferId = value; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getPerkId() { return perkId; }
    public void setPerkId(Long perkId) { this.perkId = perkId; }
    public Long getPerkTemplateId() { return perkTemplateId; }
    public void setPerkTemplateId(Long perkTemplateId) { this.perkTemplateId = perkTemplateId; }
    public Long getPayerClubId() { return payerClubId; }
    public void setPayerClubId(Long v) { payerClubId = v; }
    public String getTermsMode() { return termsMode; }
    public void setTermsMode(String termsMode) { this.termsMode = termsMode; }
    public BigDecimal getPerHeadFee() { return perHeadFee; }
    public void setPerHeadFee(BigDecimal perHeadFee) { this.perHeadFee = perHeadFee; }
    public Integer getQuotaTotal() { return quotaTotal; }
    public void setQuotaTotal(Integer quotaTotal) { this.quotaTotal = quotaTotal; }
    public Integer getQuotaUsed() { return quotaUsed; }
    public void setQuotaUsed(Integer quotaUsed) { this.quotaUsed = quotaUsed; }
    public Date getValidEnd() { return validEnd; }
    public void setValidEnd(Date validEnd) { this.validEnd = validEnd; }
    public String getAllowedValidationMethods() { return allowedValidationMethods; }
    public void setAllowedValidationMethods(String v) { this.allowedValidationMethods = v; }
    public Integer getMaxNodeXp() { return maxNodeXp; }
    public void setMaxNodeXp(Integer v) { this.maxNodeXp = v; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Boolean getWithdrawRequired() { return withdrawRequired; }
    public void setWithdrawRequired(Boolean value) { this.withdrawRequired = value; }
    public String getWithdrawReason() { return withdrawReason; }
    public void setWithdrawReason(String value) { this.withdrawReason = value; }
    public MerchantCircleSupplyProfile getCircleSupplyProfile() { return circleSupplyProfile; }
    public void setCircleSupplyProfile(MerchantCircleSupplyProfile value) { this.circleSupplyProfile = value; }
    public String getCompensationMode() {
        if (compensationMode != null) return compensationMode;
        return termsMode == null ? null : CoopTermsAxes.fromTermsMode(termsMode).getCompensationMode();
    }
    public void setCompensationMode(String value) { this.compensationMode = value; }
    public String getBenefitMode() {
        if (benefitMode != null) return benefitMode;
        return termsMode == null ? null : CoopTermsAxes.fromTermsMode(termsMode).getBenefitMode();
    }
    public void setBenefitMode(String value) { this.benefitMode = value; }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Integer getTopicLifecycle() { return topicLifecycle; }
    public void setTopicLifecycle(Integer topicLifecycle) { this.topicLifecycle = topicLifecycle; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getChapterTermsMode() { return chapterTermsMode; }
    public void setChapterTermsMode(String chapterTermsMode) { this.chapterTermsMode = chapterTermsMode; }
    public BigDecimal getChapterMaxPerHeadFee() { return chapterMaxPerHeadFee; }
    public void setChapterMaxPerHeadFee(BigDecimal chapterMaxPerHeadFee) { this.chapterMaxPerHeadFee = chapterMaxPerHeadFee; }
    public BigDecimal getChapterPerkMinValue() { return chapterPerkMinValue; }
    public void setChapterPerkMinValue(BigDecimal chapterPerkMinValue) { this.chapterPerkMinValue = chapterPerkMinValue; }
    public Integer getChapterMaxMerchant() { return chapterMaxMerchant; }
    public void setChapterMaxMerchant(Integer chapterMaxMerchant) { this.chapterMaxMerchant = chapterMaxMerchant; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getPerkName() { return perkName; }
    public void setPerkName(String perkName) { this.perkName = perkName; }
    public BigDecimal getPerkRetailValue() { return perkRetailValue; }
    public void setPerkRetailValue(BigDecimal perkRetailValue) { this.perkRetailValue = perkRetailValue; }

    public String getMmsName() { return mmsName; }
    public void setMmsName(String mmsName) { this.mmsName = mmsName; }
    public Long getMmsStatus() { return mmsStatus; }
    public void setMmsStatus(Long mmsStatus) { this.mmsStatus = mmsStatus; }
    public String getBusinessLicense() { return businessLicense; }
    public void setBusinessLicense(String businessLicense) { this.businessLicense = businessLicense; }
    public String getMmsPhone() { return mmsPhone; }
    public void setMmsPhone(String mmsPhone) { this.mmsPhone = mmsPhone; }
    public String getMmsAddress() { return mmsAddress; }
    public void setMmsAddress(String mmsAddress) { this.mmsAddress = mmsAddress; }
    public Integer getMmsCapacity() { return mmsCapacity; }
    public void setMmsCapacity(Integer mmsCapacity) { this.mmsCapacity = mmsCapacity; }
    public Integer getMerchantPerkUsed() { return merchantPerkUsed; }
    public void setMerchantPerkUsed(Integer merchantPerkUsed) { this.merchantPerkUsed = merchantPerkUsed; }
    public Integer getMerchantPerkTotal() { return merchantPerkTotal; }
    public void setMerchantPerkTotal(Integer merchantPerkTotal) { this.merchantPerkTotal = merchantPerkTotal; }
    public Integer getMerchantRevshareCount() { return merchantRevshareCount; }
    public void setMerchantRevshareCount(Integer merchantRevshareCount) { this.merchantRevshareCount = merchantRevshareCount; }
}
