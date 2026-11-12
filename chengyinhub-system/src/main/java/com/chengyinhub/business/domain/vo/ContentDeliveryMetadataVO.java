package com.chengyinhub.business.domain.vo;

import java.util.List;

/**
 * 创作者交付元数据(BE-13)。模板货架 / 详情页共用,只补齐「这一版内容到底交付了什么」,
 * 不改既有 subject/topic 字段。
 *
 * <p>三条来源纪律:
 * <ul>
 *   <li>修订号 / 内容哈希只来自既有不可变修订(BE-06),没有修订就不写,不拿 live 模板拼一个;</li>
 *   <li>许可摘要只来自已冻结的销售 canonical 的 {@code contentRightsPackage};
 *       没有权威事实就是 {@code UNKNOWN} 且各字段为 null,<b>不创造授权</b>;</li>
 *   <li>地点 / 人手 / 物料来自既有章节元数据与节点行;运营投入仍沿用原招募闸,
 *       非合作方只得到 {@code PARTNER_ONLY},数值不下发。</li>
 * </ul>
 *
 * <p>语言:本基线正文只有中文。{@code sourceLocale} 是真实的源语言,
 * {@code translations} 永远不是「假装已支持多语」的占位数组 —— 没有译文就是空列表 + {@code SINGLE_LOCALE}。
 */
public class ContentDeliveryMetadataVO {

    public static final String LICENSE_AUTHORITATIVE = "AUTHORITATIVE";
    public static final String LICENSE_UNKNOWN = "UNKNOWN";

    public static final String VISIBILITY_INCLUDED = "INCLUDED";
    public static final String VISIBILITY_PARTNER_ONLY = "PARTNER_ONLY";
    public static final String VISIBILITY_NOT_PROVIDED = "NOT_PROVIDED";

    public static final String TRANSLATION_SINGLE_LOCALE = "SINGLE_LOCALE";

    public static final String PLATFORM_UNKNOWN = "UNKNOWN";

    private Revision revision;
    private List<ContentManifestVO.Requirement> requiredCapabilities;
    private LocationRequirement locationRequirement;
    private OperatingRequirements operatingRequirements;
    private String operatingRequirementsVisibility;
    private EvidenceSummary evidence;
    private LicenseSummary license;
    private RuntimeSupport runtimeSupport;
    private String sourceLocale;
    private String translationStatus;
    private List<String> translations;

    public Revision getRevision() { return revision; }
    public void setRevision(Revision revision) { this.revision = revision; }
    public List<ContentManifestVO.Requirement> getRequiredCapabilities() { return requiredCapabilities; }
    public void setRequiredCapabilities(List<ContentManifestVO.Requirement> requiredCapabilities) { this.requiredCapabilities = requiredCapabilities; }
    public LocationRequirement getLocationRequirement() { return locationRequirement; }
    public void setLocationRequirement(LocationRequirement locationRequirement) { this.locationRequirement = locationRequirement; }
    public OperatingRequirements getOperatingRequirements() { return operatingRequirements; }
    public void setOperatingRequirements(OperatingRequirements operatingRequirements) { this.operatingRequirements = operatingRequirements; }
    public String getOperatingRequirementsVisibility() { return operatingRequirementsVisibility; }
    public void setOperatingRequirementsVisibility(String operatingRequirementsVisibility) { this.operatingRequirementsVisibility = operatingRequirementsVisibility; }
    public EvidenceSummary getEvidence() { return evidence; }
    public void setEvidence(EvidenceSummary evidence) { this.evidence = evidence; }
    public LicenseSummary getLicense() { return license; }
    public void setLicense(LicenseSummary license) { this.license = license; }
    public RuntimeSupport getRuntimeSupport() { return runtimeSupport; }
    public void setRuntimeSupport(RuntimeSupport runtimeSupport) { this.runtimeSupport = runtimeSupport; }
    public String getSourceLocale() { return sourceLocale; }
    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }
    public String getTranslationStatus() { return translationStatus; }
    public void setTranslationStatus(String translationStatus) { this.translationStatus = translationStatus; }
    public List<String> getTranslations() { return translations; }
    public void setTranslations(List<String> translations) { this.translations = translations; }

    /** 不可变修订引用;{@code contentRevisionId=null} 表示该内容尚无修订索引(老内容继续在线)。 */
    public static class Revision {
        private Long contentRevisionId;
        private Long revisionNo;
        private Integer schemaVersion;
        private String contentSha256;
        private String origin;

        public Long getContentRevisionId() { return contentRevisionId; }
        public void setContentRevisionId(Long contentRevisionId) { this.contentRevisionId = contentRevisionId; }
        public Long getRevisionNo() { return revisionNo; }
        public void setRevisionNo(Long revisionNo) { this.revisionNo = revisionNo; }
        public Integer getSchemaVersion() { return schemaVersion; }
        public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
        public String getContentSha256() { return contentSha256; }
        public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }
    }

    /**
     * 地点需求摘要:只给玩家可预期的事实(**不含经纬度** —— 坐标属于游玩授权范围)。
     * 计数只来自实际节点行,不编造「大概几个点」。
     */
    public static class LocationRequirement {
        private Integer stationCount;
        private Integer stationWithAddressCount;
        private Integer stationWithScanCheckpointCount;
        private Integer rtlStationCount;
        private String cityCode;
        private Boolean physicalPlaceRequired;

        public Integer getStationCount() { return stationCount; }
        public void setStationCount(Integer stationCount) { this.stationCount = stationCount; }
        public Integer getStationWithAddressCount() { return stationWithAddressCount; }
        public void setStationWithAddressCount(Integer stationWithAddressCount) { this.stationWithAddressCount = stationWithAddressCount; }
        public Integer getStationWithScanCheckpointCount() { return stationWithScanCheckpointCount; }
        public void setStationWithScanCheckpointCount(Integer stationWithScanCheckpointCount) { this.stationWithScanCheckpointCount = stationWithScanCheckpointCount; }
        public Integer getRtlStationCount() { return rtlStationCount; }
        public void setRtlStationCount(Integer rtlStationCount) { this.rtlStationCount = rtlStationCount; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String cityCode) { this.cityCode = cityCode; }
        public Boolean getPhysicalPlaceRequired() { return physicalPlaceRequired; }
        public void setPhysicalPlaceRequired(Boolean physicalPlaceRequired) { this.physicalPlaceRequired = physicalPlaceRequired; }
    }

    /** 人手 / 物料 / 场地:沿用章节元数据原字段,不做估算,也不把 ESTIMATED 说成实测。 */
    public static class OperatingRequirements {
        private List<String> venues;
        private List<String> materials;
        private List<String> roleNeeds;
        private Integer staffMinutesMax;
        private Integer maxConcurrent;
        private Boolean needsBooking;
        private java.math.BigDecimal prepHoursMax;
        private List<String> availableSlots;
        private List<String> qualifications;

        public List<String> getVenues() { return venues; }
        public void setVenues(List<String> venues) { this.venues = venues; }
        public List<String> getMaterials() { return materials; }
        public void setMaterials(List<String> materials) { this.materials = materials; }
        public List<String> getRoleNeeds() { return roleNeeds; }
        public void setRoleNeeds(List<String> roleNeeds) { this.roleNeeds = roleNeeds; }
        public Integer getStaffMinutesMax() { return staffMinutesMax; }
        public void setStaffMinutesMax(Integer staffMinutesMax) { this.staffMinutesMax = staffMinutesMax; }
        public Integer getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(Integer maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public Boolean getNeedsBooking() { return needsBooking; }
        public void setNeedsBooking(Boolean needsBooking) { this.needsBooking = needsBooking; }
        public java.math.BigDecimal getPrepHoursMax() { return prepHoursMax; }
        public void setPrepHoursMax(java.math.BigDecimal prepHoursMax) { this.prepHoursMax = prepHoursMax; }
        public List<String> getAvailableSlots() { return availableSlots; }
        public void setAvailableSlots(List<String> availableSlots) { this.availableSlots = availableSlots; }
        public List<String> getQualifications() { return qualifications; }
        public void setQualifications(List<String> qualifications) { this.qualifications = qualifications; }
    }

    /** 运行可信度:原样转述既有 template_status / evidence_level,不做等级提升。 */
    public static class EvidenceSummary {
        private String templateStatus;
        private String evidenceLevel;
        private Integer isOfficial;
        private java.util.Date updatedAt;

        public String getTemplateStatus() { return templateStatus; }
        public void setTemplateStatus(String templateStatus) { this.templateStatus = templateStatus; }
        public String getEvidenceLevel() { return evidenceLevel; }
        public void setEvidenceLevel(String evidenceLevel) { this.evidenceLevel = evidenceLevel; }
        public Integer getIsOfficial() { return isOfficial; }
        public void setIsOfficial(Integer isOfficial) { this.isOfficial = isOfficial; }
        public java.util.Date getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(java.util.Date updatedAt) { this.updatedAt = updatedAt; }
    }

    /**
     * 许可摘要。只来自冻结销售 canonical 的 {@code contentRightsPackage};
     * 没有冻结事实时 {@code status=UNKNOWN} 且所有字段 null —— 前端必须显示「未提供」。
     */
    public static class LicenseSummary {
        private String status;
        private String unavailableReason;
        private String assetRef;
        private String owner;
        private String licensedEntity;
        private List<String> channels;
        private String validFrom;
        private String validTo;
        private Boolean editingRight;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getUnavailableReason() { return unavailableReason; }
        public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }
        public String getAssetRef() { return assetRef; }
        public void setAssetRef(String assetRef) { this.assetRef = assetRef; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public String getLicensedEntity() { return licensedEntity; }
        public void setLicensedEntity(String licensedEntity) { this.licensedEntity = licensedEntity; }
        public List<String> getChannels() { return channels; }
        public void setChannels(List<String> channels) { this.channels = channels; }
        public String getValidFrom() { return validFrom; }
        public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
        public String getValidTo() { return validTo; }
        public void setValidTo(String validTo) { this.validTo = validTo; }
        public Boolean getEditingRight() { return editingRight; }
        public void setEditingRight(Boolean editingRight) { this.editingRight = editingRight; }
    }

    /**
     * 运行端支持。{@code capabilities} 由内容推导(复用既有能力表);
     * {@code platformExecution} 在本基线没有平台运行时矩阵,如实 {@code UNKNOWN},
     * 不用「全端可玩」冒充已验证。
     */
    public static class RuntimeSupport {
        private List<ContentManifestVO.Requirement> capabilities;
        private String platformExecution;
        private String platformExecutionReason;

        public List<ContentManifestVO.Requirement> getCapabilities() { return capabilities; }
        public void setCapabilities(List<ContentManifestVO.Requirement> capabilities) { this.capabilities = capabilities; }
        public String getPlatformExecution() { return platformExecution; }
        public void setPlatformExecution(String platformExecution) { this.platformExecution = platformExecution; }
        public String getPlatformExecutionReason() { return platformExecutionReason; }
        public void setPlatformExecutionReason(String platformExecutionReason) { this.platformExecutionReason = platformExecutionReason; }
    }
}
