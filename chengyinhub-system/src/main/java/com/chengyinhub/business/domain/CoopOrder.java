package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.chengyinhub.common.core.domain.BaseEntity;
import com.chengyinhub.common.constant.CoopTermsAxes;

/**
 * 合作单对象 coop_order(报告§5.3 · D-4)。
 *
 * 邀约同意瞬间生成,冻结商业条款快照;后续结算只认合作单,聊天不构成变更。
 */
public class CoopOrder extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 来源邀约(邀约侧候选;报名侧为空) */
    private Long inviteId;

    /** 来源报名(候选池报名侧;邀约侧为空) */
    private Long regId;

    /** 主题 */
    private Long topicId;

    /** 经典定向:游戏 */
    private Long gameId;

    /** 发起方memberId */
    private Long fromId;

    /** 受邀方(merchant=memberId/club=clubId) */
    private Long toId;

    /** 受邀方类型 merchant/club */
    private String toType;

    /** 报酬模式 0引流 1分成 2固定 */
    private Integer shareMode;

    /** 分成比例 */
    private BigDecimal shareRate;

    /** 固定型每核销人头费 */
    private BigDecimal fixedFee;

    /** 同意瞬间的权益快照(JSON) */
    private String perksSnapshot;

    /** 平台规则版本号 */
    private String termsVersion;

    /** 接受时由服务端冻结的合同角色/范围/选集/证据/条款快照。 */
    private String roleSnapshot;
    private String scopeTypeSnapshot;
    private Long scopeIdSnapshot;
    private String eligiblePurchaseKindsSnapshot;
    private String fulfillmentEvidenceRuleSnapshot;
    private String termsSnapshot;
    /** 保证金与计费两轴独立，单独保留冻结值。 */
    private BigDecimal depositAmount;
    /** 由冻结快照确定性派生的只读 DTO 轴，不落独立编辑列。 */
    private String compensationMode;
    private String benefitMode;

    /** 状态 0生效 1作废 2已结算 */
    private Integer status;

    /** del_flag */
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long inviteId) { this.inviteId = inviteId; }
    public Long getRegId() { return regId; }
    public void setRegId(Long regId) { this.regId = regId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getToType() { return toType; }
    public void setToType(String toType) { this.toType = toType; }
    public Integer getShareMode() { return shareMode; }
    public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
    public BigDecimal getShareRate() { return shareRate; }
    public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
    public String getPerksSnapshot() { return perksSnapshot; }
    public void setPerksSnapshot(String perksSnapshot) { this.perksSnapshot = perksSnapshot; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getRoleSnapshot() { return roleSnapshot; }
    public void setRoleSnapshot(String roleSnapshot) { this.roleSnapshot = roleSnapshot; }
    public String getScopeTypeSnapshot() { return scopeTypeSnapshot; }
    public void setScopeTypeSnapshot(String scopeTypeSnapshot) { this.scopeTypeSnapshot = scopeTypeSnapshot; }
    public Long getScopeIdSnapshot() { return scopeIdSnapshot; }
    public void setScopeIdSnapshot(Long scopeIdSnapshot) { this.scopeIdSnapshot = scopeIdSnapshot; }
    public String getEligiblePurchaseKindsSnapshot() { return eligiblePurchaseKindsSnapshot; }
    public void setEligiblePurchaseKindsSnapshot(String value) { this.eligiblePurchaseKindsSnapshot = value; }
    public String getFulfillmentEvidenceRuleSnapshot() { return fulfillmentEvidenceRuleSnapshot; }
    public void setFulfillmentEvidenceRuleSnapshot(String value) { this.fulfillmentEvidenceRuleSnapshot = value; }
    public String getTermsSnapshot() { return termsSnapshot; }
    public void setTermsSnapshot(String termsSnapshot) { this.termsSnapshot = termsSnapshot; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public String getCompensationMode() {
        if (compensationMode != null) return compensationMode;
        // 存量缺少合同快照时禁止按旧 share_mode 猜测，交人工修复清单。
        if (termsSnapshot == null || roleSnapshot == null || scopeTypeSnapshot == null
                || scopeIdSnapshot == null || eligiblePurchaseKindsSnapshot == null
                || fulfillmentEvidenceRuleSnapshot == null) return null;
        return CoopTermsAxes.fromOrder(shareMode, perksSnapshot).getCompensationMode();
    }
    public void setCompensationMode(String compensationMode) { this.compensationMode = compensationMode; }
    public String getBenefitMode() {
        if (benefitMode != null) return benefitMode;
        // 与 compensationMode 使用同一完整快照门槛，避免存量订单运行时推断。
        if (termsSnapshot == null || roleSnapshot == null || scopeTypeSnapshot == null
                || scopeIdSnapshot == null || eligiblePurchaseKindsSnapshot == null
                || fulfillmentEvidenceRuleSnapshot == null) return null;
        return CoopTermsAxes.fromOrder(shareMode, perksSnapshot).getBenefitMode();
    }
    public void setBenefitMode(String benefitMode) { this.benefitMode = benefitMode; }
}
