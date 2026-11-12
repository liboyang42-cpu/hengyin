package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 玩家可见内容清单(BE-10)。
 *
 * <p>这是「出门前预取」的只读交付合同,不是内容真源:结构字段来自同一不可变修订的
 * {@code publicManifestJson},资源清单由服务端从该修订的冻结内容里按白名单提取。
 * 私有 {@code snapshotJson}(答案 / 判题配置 / 商家秘密)永远不进入本类型。
 *
 * <p>三个 {@code status} 的语义必须分清:
 * <ul>
 *   <li>{@link #STATUS_REVISION}:明确绑定一条已发布修订,可缓存、可校验 contentSha256;</li>
 *   <li>{@link #STATUS_RUN_SNAPSHOT}:本局已开局,按本局自己的快照给清单,不读当前发布修订;</li>
 *   <li>{@link #STATUS_ONLINE_LEGACY}:老内容没有修订清单,继续在线读取({@code onlineRequired=true}),
 *       不拿实时模板拼一份假修订。</li>
 * </ul>
 *
 * <p>资源项只证明「内容里引用了这个稳定媒介身份」。{@code sha256} / {@code bytes} / {@code mime}
 * 没有权威值时保持 null,{@code verified=false};请求期绝不代客户端抓取任意 URL。
 */
public class ContentManifestVO {

    public static final String STATUS_REVISION = "REVISION";
    public static final String STATUS_RUN_SNAPSHOT = "RUN_SNAPSHOT";
    public static final String STATUS_ONLINE_LEGACY = "ONLINE_LEGACY";

    public static final String SCOPE_START = "START";
    public static final String SCOPE_ROUTE = "ROUTE";
    public static final String SCOPE_OPTIONAL = "OPTIONAL";

    public static final String VERIFICATION_NONE = "NONE";
    public static final String VERIFICATION_UNVERIFIED = "UNVERIFIED";

    private String status;
    private Long topicId;
    private Long activityId;
    private Long contentRevisionId;
    private Long revisionNo;
    private Integer schemaVersion;
    private Integer manifestVersion;
    private String contentSha256;
    private String routeMode;
    private Long openingChapterId;
    private String openingChapterName;
    private List<Station> stations = new ArrayList<>();
    private List<Requirement> requiredCapabilities;
    private List<Asset> assets = new ArrayList<>();
    private String assetVerification;
    private Date expiresAt;
    private boolean onlineRequired;
    private String sourceLocale;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getContentRevisionId() { return contentRevisionId; }
    public void setContentRevisionId(Long contentRevisionId) { this.contentRevisionId = contentRevisionId; }
    public Long getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Long revisionNo) { this.revisionNo = revisionNo; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public Integer getManifestVersion() { return manifestVersion; }
    public void setManifestVersion(Integer manifestVersion) { this.manifestVersion = manifestVersion; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public Long getOpeningChapterId() { return openingChapterId; }
    public void setOpeningChapterId(Long openingChapterId) { this.openingChapterId = openingChapterId; }
    public String getOpeningChapterName() { return openingChapterName; }
    public void setOpeningChapterName(String openingChapterName) { this.openingChapterName = openingChapterName; }
    public List<Station> getStations() { return stations; }
    public void setStations(List<Station> stations) { this.stations = stations; }
    public List<Requirement> getRequiredCapabilities() { return requiredCapabilities; }
    public void setRequiredCapabilities(List<Requirement> requiredCapabilities) { this.requiredCapabilities = requiredCapabilities; }
    public List<Asset> getAssets() { return assets; }
    public void setAssets(List<Asset> assets) { this.assets = assets; }
    public String getAssetVerification() { return assetVerification; }
    public void setAssetVerification(String assetVerification) { this.assetVerification = assetVerification; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public boolean isOnlineRequired() { return onlineRequired; }
    public void setOnlineRequired(boolean onlineRequired) { this.onlineRequired = onlineRequired; }
    public String getSourceLocale() { return sourceLocale; }
    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    /** 站点骨架:只含玩家提前需要知道的导航事实。 */
    public static class Station {
        private Long nodeId;
        private String nodeName;
        private Long chapterId;
        private Boolean scanReady;
        private String scope;

        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
        public Boolean getScanReady() { return scanReady; }
        public void setScanReady(Boolean scanReady) { this.scanReady = scanReady; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }

    /** 端能力需求,直接复用 {@code ContentCapabilityRequirements} 的推导结果。 */
    public static class Requirement {
        private String key;
        private Integer minVersion;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Integer getMinVersion() { return minVersion; }
        public void setMinVersion(Integer minVersion) { this.minVersion = minVersion; }
    }

    /**
     * 一个可缓存媒介引用。{@code assetKey} 是稳定身份(章节/节点/模板 + 字段),
     * 不是 URL —— 签名地址刷新不代表内容变成另一份。
     */
    public static class Asset {
        private String assetKey;
        private String url;
        private String sha256;
        private Long bytes;
        private String mime;
        private Integer priority;
        private String scope;
        private String textAlternative;
        private Boolean verified;
        private String unverifiedReason;

        public String getAssetKey() { return assetKey; }
        public void setAssetKey(String assetKey) { this.assetKey = assetKey; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public Long getBytes() { return bytes; }
        public void setBytes(Long bytes) { this.bytes = bytes; }
        public String getMime() { return mime; }
        public void setMime(String mime) { this.mime = mime; }
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getTextAlternative() { return textAlternative; }
        public void setTextAlternative(String textAlternative) { this.textAlternative = textAlternative; }
        public Boolean getVerified() { return verified; }
        public void setVerified(Boolean verified) { this.verified = verified; }
        public String getUnverifiedReason() { return unverifiedReason; }
        public void setUnverifiedReason(String unverifiedReason) { this.unverifiedReason = unverifiedReason; }
    }
}
