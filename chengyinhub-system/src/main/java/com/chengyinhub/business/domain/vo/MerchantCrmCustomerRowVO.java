package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Date;

public class MerchantCrmCustomerRowVO {
    private Long memberId;
    private String name;
    /** 第十二轮 k3(p2 N-6):本店负责联系的报名里填写的姓名(已解密);只在服务端判权后用来替换 name,不下发。 */
    @JsonIgnore
    private String signupName;
    private String avatar;
    private String phone;
    private Boolean merchantResponsibleForContact;
    private Boolean freeExploreShopContact;
    private String contactHint;
    private Integer arrivedCount;
    private Integer pendingCount;
    private Integer refundedCount;
    private BigDecimal paidAmount;
    private Date lastTime;
    private String lastAction;
    private String latestNote;
    private String tier;
    private String sourceType;
    /** 队伍名(本商家范围内参加过的队伍之一)。定向广播按它分组;客户没进过队就是 null。 */
    private String teamName;
    /** 角色码 NAVIGATOR/OBSERVER/RECORDER/NEGOTIATOR/DECODER，仅活动局才有。 */
    private String roleCode;
    /** roleCode 的中文名，由 IPlayerRoleService.roleName 映射，认不出就留 null 不编。 */
    private String roleName;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @JsonIgnore
    public String getSignupName() { return signupName; }
    public void setSignupName(String value) { signupName = value; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String value) { avatar = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    @JsonIgnore
    public Boolean getMerchantResponsibleForContact() { return merchantResponsibleForContact; }
    public void setMerchantResponsibleForContact(Boolean value) { merchantResponsibleForContact = value; }
    /** 本商家只是该客户自由探索票的参与商家:联系方式走单店授权,不在通用名册下发。 */
    @JsonIgnore
    public Boolean getFreeExploreShopContact() { return freeExploreShopContact; }
    public void setFreeExploreShopContact(Boolean value) { freeExploreShopContact = value; }
    public String getContactHint() { return contactHint; }
    public void setContactHint(String value) { contactHint = value; }
    public Integer getArrivedCount() { return arrivedCount; }
    public void setArrivedCount(Integer value) { arrivedCount = value; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer value) { pendingCount = value; }
    public Integer getRefundedCount() { return refundedCount; }
    public void setRefundedCount(Integer value) { refundedCount = value; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal value) { paidAmount = value; }
    public Date getLastTime() { return lastTime; }
    public void setLastTime(Date value) { lastTime = value; }
    public String getLastAction() { return lastAction; }
    public void setLastAction(String value) { lastAction = value; }
    public String getLatestNote() { return latestNote; }
    public void setLatestNote(String value) { latestNote = value; }
    public String getTier() { return tier; }
    public void setTier(String value) { tier = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String value) { teamName = value; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String value) { roleCode = value; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String value) { roleName = value; }
}
