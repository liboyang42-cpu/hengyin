package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * NPC 人设实体。
 *
 * @author chengyinhub
 */
public class NpcProfile
{
    private Long id;
    /** 1=剧本角色 2=门店分身 3=全局城瘾NPC */
    private Integer scopeType;
    private Long scopeId;
    private String name;
    private String avatar;
    private String persona;
    private String greeting;
    /** 店铺知识:菜单/招牌/营业信息/老板故事。喂给对话事实包,scope_type=2 才有意义。 */
    private String knowledge;
    private Integer sort;
    private Integer auditStatus;
    /** 驳回原因。仅 audit_status=2 时有值;下发给商家,让他知道该改什么。 */
    private String auditReason;
    private Integer enabled;
    private String delFlag;

    /** 声音复刻音色 id(阿里云百炼返回)。没配声音时为 null。 */
    private String voiceId;

    /** 0=未配置 1=复刻中 2=可用 3=复刻失败。默认 0 而不是 null —— null 参与比较恒为 null,
     *  写出 voice_status = 2 之外的条件时很容易得到「永远取不到」而不报错。 */
    private Integer voiceStatus;
    /** 声音每次写入递增，阻止清除或换任务后旧结果落库。 */
    private Long voiceVersion = 0L;

    /** 商家录的那段音频,留着供音色重建。 */
    private String voiceSample;
    /** 本次复刻请求已取得声音权利人明确授权；仅作接口入参，不落库。 */
    private Boolean voiceConsent;
    /** Enrollment recovery metadata, only populated by the authorized voice management service. */
    private String voiceRequestId;
    private Long voicePreviousIntentId;
    private Boolean voiceDuplicateChargeConsent;
    private Boolean voiceSelected;
    private Long voiceIntentId;
    private String voiceIntentState;
    private String voiceReasonCode;
    private Date createTime;
    private Date updateTime;
    /** 后台列表联查 mms_merchant.name 用,不落库(insert/update 均不含该列)。 */
    private String merchantName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getVoiceStatus() { return voiceStatus; }
    public void setVoiceStatus(Integer voiceStatus) { this.voiceStatus = voiceStatus; }
    @JsonIgnore
    public Long getVoiceVersion() { return voiceVersion; }
    public void setVoiceVersion(Long voiceVersion) { this.voiceVersion = voiceVersion; }
    public String getVoiceSample() { return voiceSample; }
    public void setVoiceSample(String voiceSample) { this.voiceSample = voiceSample; }
    public Boolean getVoiceConsent() { return voiceConsent; }
    public void setVoiceConsent(Boolean voiceConsent) { this.voiceConsent = voiceConsent; }

    public String getVoiceRequestId() { return voiceRequestId; }
    public void setVoiceRequestId(String value) { voiceRequestId = value; }
    public Long getVoicePreviousIntentId() { return voicePreviousIntentId; }
    public void setVoicePreviousIntentId(Long value) { voicePreviousIntentId = value; }
    public Boolean getVoiceDuplicateChargeConsent() { return voiceDuplicateChargeConsent; }
    public void setVoiceDuplicateChargeConsent(Boolean value) { voiceDuplicateChargeConsent = value; }
    public Boolean getVoiceSelected() { return voiceSelected; }
    public void setVoiceSelected(Boolean value) { voiceSelected = value; }
    public Long getVoiceIntentId() { return voiceIntentId; }
    public void setVoiceIntentId(Long value) { voiceIntentId = value; }
    public String getVoiceIntentState() { return voiceIntentState; }
    public void setVoiceIntentState(String value) { voiceIntentState = value; }
    public String getVoiceReasonCode() { return voiceReasonCode; }
    public void setVoiceReasonCode(String value) { voiceReasonCode = value; }

    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getKnowledge() { return knowledge; }
    public void setKnowledge(String knowledge) { this.knowledge = knowledge; }
    public String getVoiceId() { return voiceId; }
    public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }
    public Integer getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Integer auditStatus) { this.auditStatus = auditStatus; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
}
