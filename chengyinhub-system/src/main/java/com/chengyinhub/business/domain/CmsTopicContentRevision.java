package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 主题内容不可变修订(BE-06)。
 *
 * <p>一行 = 一次正式发布冻结下来的精确内容。本表只追加,任何路径都不得 UPDATE/DELETE 历史行;
 * 「恢复旧版」是复制成新草稿再发布,产生新的 revision_no。
 *
 * <p>三类 hash 的关系:
 * <ul>
 *   <li>{@code contentSha256} 是 {@code snapshotJson} 的字节哈希,只用于完整性/差异比较;</li>
 *   <li>{@code requestFingerprint} 是发起幂等比较时使用的请求内容指纹。当前发布入口的请求内容
 *       就是冻结 payload 本身,故与 {@code contentSha256} 同值;独立成列是为了将来把请求侧的
 *       其它字段(如 expectedConfigVersion)纳入指纹时不污染内容哈希;</li>
 *   <li>{@code (topicId, contentSha256)} 是<b>普通索引</b>。内容 hash 不是幂等键:
 *       A→B→恢复A 会产生第三条修订,其 hash 与第一条相同。</li>
 * </ul>
 *
 * @author chengyinhub
 * @date 2026-11-08
 */
public class CmsTopicContentRevision {

    /** 本次发布事务写入。 */
    public static final String ORIGIN_PUBLISH = "PUBLISH";

    /** 存量发布头被显式导入;hash 取其当时真实存储字节,不是重建结果。 */
    public static final String ORIGIN_LEGACY_IMPORTED = "LEGACY_IMPORTED";

    private Long id;
    private Long topicId;
    private Long revisionNo;
    private Integer schemaVersion;
    private String contentSha256;

    /** 精确冻结 payload,含答案等 secret。禁止进入任何公共序列化。 */
    private String snapshotJson;

    /** 白名单公共投影,不含答案/节点玩法配置/运营合同。 */
    private String publicManifestJson;

    private Long sourceContentRevisionId;
    private String publishRequestId;
    private String requestFingerprint;
    private String origin;
    private Long createBy;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Long revisionNo) { this.revisionNo = revisionNo; }

    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }

    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }

    public String getPublicManifestJson() { return publicManifestJson; }
    public void setPublicManifestJson(String publicManifestJson) { this.publicManifestJson = publicManifestJson; }

    public Long getSourceContentRevisionId() { return sourceContentRevisionId; }
    public void setSourceContentRevisionId(Long sourceContentRevisionId) { this.sourceContentRevisionId = sourceContentRevisionId; }

    public String getPublishRequestId() { return publishRequestId; }
    public void setPublishRequestId(String publishRequestId) { this.publishRequestId = publishRequestId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        // 刻意不含 snapshotJson:toString 常被日志/异常串使用,私有 payload 不得随之落日志。
        return "CmsTopicContentRevision{topicId=" + topicId + ", revisionNo=" + revisionNo
                + ", schemaVersion=" + schemaVersion + ", contentSha256='" + contentSha256 + '\''
                + ", origin='" + origin + '\'' + '}';
    }
}
