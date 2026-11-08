package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWrite;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.business.service.support.TopicContentPublicManifest;
import com.chengyinhub.business.service.support.TopicContentGeography;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 不可变内容修订(BE-06)。
 *
 * <p>为什么写侧坚持要求「已在主题写事务中」:修订一旦独立提交,就可能出现
 * 「有修订、发布头没动」或反之的半状态;两种半状态都会让玩家下载到并非当前发布的内容。
 * 该断言与 {@code TopicRoutePublicationCompiler#freezePublication} 同一口径。
 *
 * <p>串行化:同一主题的 revision_no 由 {@code cms_topic} 行锁串行(与发布头同一把锁,
 * 同一事务内重入不额外阻塞),唯一键 {@code uk_topic_revision_no} 是硬兜底。
 */
@Service
public class TopicContentRevisionServiceImpl implements ITopicContentRevisionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private CmsTopicContentRevisionMapper revisionMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Override
    public TopicContentRevisionWriteResult recordPublishedRevision(TopicContentRevisionWrite write) {
        requireWriteTransaction();
        if (write == null || write.getTopicId() == null || write.getTopicId() <= 0L)
            throw new IllegalArgumentException("修订主题缺失");
        String snapshot = write.getFrozenSnapshot();
        if (StringUtils.isBlank(snapshot))
            throw new IllegalArgumentException("冻结内容为空，拒绝写入修订");

        Long topicId = write.getTopicId();
        String contentSha = CanonicalDigest.sha256Hex(snapshot);
        String publishRequestId = write.getPublishRequestId();

        CmsTopic locked = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (locked == null) throw new IllegalStateException("发布主题当前版本不存在");

        if (publishRequestId != null) {
            CmsTopicContentRevision existing = revisionMapper.selectByPublishRequestId(topicId, publishRequestId);
            if (existing != null) return replayOrConflict(existing, contentSha);
        }

        JsonNode frozen = frozenTree(snapshot);
        long revisionNo = nextRevisionNo(topicId);
        CmsTopicContentRevision row = new CmsTopicContentRevision();
        row.setTopicId(topicId);
        row.setRevisionNo(revisionNo);
        row.setSchemaVersion(frozen.path("runtimeSnapshotVersion").asInt(0));
        row.setContentSha256(contentSha);
        row.setSnapshotJson(snapshot);
        row.setPublicManifestJson(TopicContentPublicManifest.buildJson(frozen, topicId, revisionNo, contentSha,
                // F1:冻结地理取本次发布时行锁读到的 city_code 与作者显式国家;供排行按本局真实地理取。
                TopicContentGeography.of(frozen, locked.getCityCode(), locked.getContentContractJson())));
        row.setSourceContentRevisionId(write.getSourceContentRevisionId());
        row.setPublishRequestId(publishRequestId);
        // 请求指纹 = 本次发布意图的内容字节哈希。当前发布入口的请求内容就是冻结 payload,
        // 故与 content_sha256 同值;单独成列以便将来把请求侧其它字段并入指纹而不污染内容哈希。
        row.setRequestFingerprint(contentSha);
        row.setOrigin(CmsTopicContentRevision.ORIGIN_PUBLISH);
        // 发布口不额外传操作人时,记录主题归属者 = 本次发布的作者;不编造系统账号。
        row.setCreateBy(write.getCreatedBy() != null ? write.getCreatedBy() : locked.getMemberId());
        row.setCreateTime(DateUtils.getNowDate());
        try {
            if (revisionMapper.insertRevision(row) != 1)
                throw new IllegalStateException("内容修订保存失败");
        } catch (DuplicateKeyException concurrent) {
            // 行锁已按主题串行;能走到这里说明有绕过发布口的写入或未来的并发路径。
            // 有请求号时按「同号同 payload = 重试」收口,否则如实失败,绝不静默改写历史。
            if (publishRequestId != null) {
                CmsTopicContentRevision existing = revisionMapper.selectByPublishRequestId(topicId, publishRequestId);
                if (existing != null) return replayOrConflict(existing, contentSha);
            }
            throw new IllegalStateException("并发发布产生相同修订号，请重试", concurrent);
        }
        return TopicContentRevisionWriteResult.recorded(row);
    }

    @Override
    public CmsTopicContentRevision importLegacyPublished(Long topicId, Long createdBy) {
        requireWriteTransaction();
        if (topicId == null || topicId <= 0L) throw new IllegalArgumentException("修订主题缺失");
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在");
        if (topic.getPublishedConfigVersion() == null || topic.getPublishedConfigVersion() <= 0L)
            throw new IllegalStateException("主题尚未发布，没有可导入的发布头");
        String snapshot = topic.getPublishedRouteGraphJson();
        if (StringUtils.isBlank(snapshot))
            throw new IllegalStateException("已发布头缺少真实快照，不能导入为修订");

        String contentSha = CanonicalDigest.sha256Hex(snapshot);
        CmsTopicContentRevision latest = revisionMapper.selectLatestByTopicId(topicId);
        // 幂等口径:当前发布头的真实内容已被索引过就返回既有行,不制造第二条同内容修订。
        if (latest != null && contentSha.equals(latest.getContentSha256())) return latest;

        JsonNode frozen = frozenTree(snapshot);
        long revisionNo = nextRevisionNo(topicId);
        CmsTopicContentRevision row = new CmsTopicContentRevision();
        row.setTopicId(topicId);
        row.setRevisionNo(revisionNo);
        row.setSchemaVersion(frozen.path("runtimeSnapshotVersion").asInt(0));
        row.setContentSha256(contentSha);
        row.setSnapshotJson(snapshot);
        row.setPublicManifestJson(TopicContentPublicManifest.buildJson(frozen, topicId, revisionNo, contentSha,
                // 历史导入:hash 与 payload 仍是当时真实存储的那份。地理只能取该 payload 已冻结的值;
                // 老 payload 没有 contentGeography 就是 UNKNOWN —— 绝不拿导入时的 live cms_topic.city_code
                // 给历史补一个新城市(那会把老局算进后来的城市)。
                TopicContentGeography.of(frozen, null, null)));
        row.setSourceContentRevisionId(null);
        row.setPublishRequestId(null);
        row.setRequestFingerprint(contentSha);
        row.setOrigin(CmsTopicContentRevision.ORIGIN_LEGACY_IMPORTED);
        row.setCreateBy(createdBy);
        row.setCreateTime(DateUtils.getNowDate());
        if (revisionMapper.insertRevision(row) != 1)
            throw new IllegalStateException("历史修订导入失败");
        return row;
    }

    @Override
    public TopicContentRevisionView currentPublished(Long topicId) {
        if (topicId == null || topicId <= 0L) return null;
        return revisionMapper.selectLatestViewByTopicId(topicId);
    }

    @Override
    public TopicContentRevisionView publishedRevision(Long topicId, Long revisionId) {
        if (topicId == null || topicId <= 0L || revisionId == null || revisionId <= 0L) return null;
        return revisionMapper.selectViewByTopicIdAndId(topicId, revisionId);
    }

    @Override
    public String frozenSnapshot(Long topicId, Long revisionId) {
        if (topicId == null || topicId <= 0L || revisionId == null || revisionId <= 0L) return null;
        CmsTopicContentRevision row = revisionMapper.selectByIdAndTopic(revisionId, topicId);
        return row == null ? null : row.getSnapshotJson();
    }

    @Override
    public CmsTopicContentRevision publishedByRequestId(Long topicId, String publishRequestId,
                                                        String expectedContentSha256) {
        if (topicId == null || topicId <= 0L) return null;
        String requestId = trimToNull(publishRequestId);
        // 超过发布口上限的号不可能写出过修订(写入口已拒绝):当成「没有该意图」,不在读路径抛错。
        if (requestId == null || requestId.length() > TopicContentRevisionWrite.MAX_PUBLISH_REQUEST_ID) return null;
        CmsTopicContentRevision existing = revisionMapper.selectByPublishRequestId(topicId, requestId);
        if (existing == null) return null;
        // 真源是服务端已存的请求指纹(落库时由服务端算),客户端回显只是「同一意图」的附加断言:
        // 它不能替代指纹,也不能让一个不同 payload 的请求靠自填 hash 混过去。
        String expected = trimToNull(expectedContentSha256);
        String serverFingerprint = existing.getRequestFingerprint() != null
                ? existing.getRequestFingerprint() : existing.getContentSha256();
        if (expected != null && !expected.equalsIgnoreCase(serverFingerprint))
            throw new ServiceException("发布请求号已用于不同内容，请使用新的请求号重新发布", 409);
        return existing;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 同号同 payload = 重试;同号异 payload = 冲突(409),不覆盖、不新建。 */
    private static TopicContentRevisionWriteResult replayOrConflict(CmsTopicContentRevision existing, String contentSha) {
        if (contentSha.equals(existing.getContentSha256()))
            return TopicContentRevisionWriteResult.replay(existing);
        throw new ServiceException("发布请求号已用于不同内容，请使用新的请求号重新发布", 409);
    }

    private long nextRevisionNo(Long topicId) {
        Long max = revisionMapper.selectMaxRevisionNo(topicId);
        return (max == null ? 0L : max.longValue()) + 1L;
    }

    /**
     * 真实 schema version 取自冻结 payload 自己声明的 {@code runtimeSnapshotVersion};
     * 存量发布头没有该字段时记 0(历史无版本标记),不猜成 1。
     */
    private static JsonNode frozenTree(String snapshot) {
        try {
            JsonNode parsed = MAPPER.readTree(snapshot);
            if (parsed == null || !parsed.isObject())
                throw new IllegalStateException("冻结内容不是对象，拒绝写入修订");
            return parsed;
        } catch (IllegalStateException keep) {
            throw keep;
        } catch (Exception unusable) {
            throw new IllegalStateException("冻结内容不是合法 JSON，拒绝写入修订", unusable);
        }
    }

    /**
     * 与编译器同一口径:正式发布必须加入主题写事务;只读事务里也不许写。
     * 这条断言同时挡住「预检 / 预览 / 草稿」误用本写口。
     */
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("正式发布必须加入主题写事务");
    }
}
