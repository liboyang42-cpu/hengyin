package com.chengyinhub.business.domain.vo;

import java.util.Date;

/**
 * 内容修订窄视图(BE-06 给 BE-10 的只读合同)。
 *
 * <p>刻意<b>不含</b> {@code snapshotJson}:私有冻结 payload 里有答案与判题配置,
 * 只能由服务端内部路径读取,绝不随本视图序列化出去。公共可下发的只有
 * {@code publicManifestJson},且它本身已是白名单投影。
 *
 * <p>取用规则(授权仍是既有 scope):调用方必须先用现有活动/报名/归属解析出合法 topicId,
 * 再用 {@code (topicId, revisionId)} 取修订。没有「只凭 revisionId 取一条」的入口,
 * 因此任意 releaseId 猜不到别人的内容。
 *
 * @author chengyinhub
 * @date 2026-11-08
 */
public class TopicContentRevisionView {

    private Long id;
    private Long topicId;
    private Long revisionNo;
    private Integer schemaVersion;
    private String contentSha256;
    private String publicManifestJson;
    private Long sourceContentRevisionId;
    private String origin;
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

    public String getPublicManifestJson() { return publicManifestJson; }
    public void setPublicManifestJson(String publicManifestJson) { this.publicManifestJson = publicManifestJson; }

    public Long getSourceContentRevisionId() { return sourceContentRevisionId; }
    public void setSourceContentRevisionId(Long sourceContentRevisionId) { this.sourceContentRevisionId = sourceContentRevisionId; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "TopicContentRevisionView{id=" + id + ", topicId=" + topicId + ", revisionNo=" + revisionNo
                + ", contentSha256='" + contentSha256 + '\'' + ", origin='" + origin + '\'' + '}';
    }
}
