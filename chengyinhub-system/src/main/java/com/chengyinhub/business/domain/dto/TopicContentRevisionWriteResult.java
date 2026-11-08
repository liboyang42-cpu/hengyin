package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsTopicContentRevision;

/**
 * 修订写入结果(BE-06)。
 *
 * <p>{@code idempotentReplay} = 同一个 {@code publishRequestId} 用同一份 payload 重试,
 * 命中了既有修订。此时发布头<b>已经</b>推进过,调用方必须短路,不得再次
 * {@code published_config_version+1},否则重试会把版本号刷成两次。
 */
public class TopicContentRevisionWriteResult {

    private final CmsTopicContentRevision revision;
    private final boolean idempotentReplay;

    private TopicContentRevisionWriteResult(CmsTopicContentRevision revision, boolean idempotentReplay) {
        this.revision = revision;
        this.idempotentReplay = idempotentReplay;
    }

    /** 本次真正新写入一条修订。 */
    public static TopicContentRevisionWriteResult recorded(CmsTopicContentRevision revision) {
        return new TopicContentRevisionWriteResult(revision, false);
    }

    /** 同号同 payload 重试,返回既有修订。 */
    public static TopicContentRevisionWriteResult replay(CmsTopicContentRevision revision) {
        return new TopicContentRevisionWriteResult(revision, true);
    }

    public CmsTopicContentRevision getRevision() { return revision; }

    public boolean isIdempotentReplay() { return idempotentReplay; }
}
