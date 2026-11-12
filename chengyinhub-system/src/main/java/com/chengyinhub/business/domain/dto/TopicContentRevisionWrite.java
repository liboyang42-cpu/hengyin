package com.chengyinhub.business.domain.dto;

/**
 * 一次正式发布要落成不可变修订的写入意图(BE-06)。
 *
 * <p>只在 {@code TopicPublicationService.persistRouteSnapshot} 内部构造;它不是 HTTP DTO,
 * 不接受任意外部字段直接透传 —— owner/审核/额度/供给闸全部在既有发布入口里先过。
 */
public class TopicContentRevisionWrite {

    /** 发布请求号上限。发布入口是表单参数,过长一律拒绝而不是截断(截断会把两个意图并成一个)。 */
    public static final int MAX_PUBLISH_REQUEST_ID = 64;

    private final Long topicId;
    private final String frozenSnapshot;
    private final String publishRequestId;
    private final Long sourceContentRevisionId;
    private final Long createdBy;

    private TopicContentRevisionWrite(Long topicId, String frozenSnapshot, String publishRequestId,
                                      Long sourceContentRevisionId, Long createdBy) {
        this.topicId = topicId;
        this.frozenSnapshot = frozenSnapshot;
        this.publishRequestId = normalizeRequestId(publishRequestId);
        this.sourceContentRevisionId = sourceContentRevisionId;
        this.createdBy = createdBy;
    }

    /** 旧调用:没有发布请求号,沿用既有「每次发布即一条新修订」语义。 */
    public static TopicContentRevisionWrite of(Long topicId, String frozenSnapshot) {
        return new TopicContentRevisionWrite(topicId, frozenSnapshot, null, null, null);
    }

    public static TopicContentRevisionWrite of(Long topicId, String frozenSnapshot, String publishRequestId) {
        return new TopicContentRevisionWrite(topicId, frozenSnapshot, publishRequestId, null, null);
    }

    public static TopicContentRevisionWrite of(Long topicId, String frozenSnapshot, String publishRequestId,
                                               Long sourceContentRevisionId, Long createdBy) {
        return new TopicContentRevisionWrite(topicId, frozenSnapshot, publishRequestId, sourceContentRevisionId, createdBy);
    }

    /**
     * 空白视为未提供(旧客户端会把可选字段传成空串)。超长直接拒绝:
     * publishRequestId 是幂等身份,截断等于把两个不同意图合并。
     */
    static String normalizeRequestId(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_PUBLISH_REQUEST_ID)
            throw new IllegalArgumentException("发布请求号过长(上限 " + MAX_PUBLISH_REQUEST_ID + " 字符)");
        return trimmed;
    }

    public Long getTopicId() { return topicId; }

    public String getFrozenSnapshot() { return frozenSnapshot; }

    public String getPublishRequestId() { return publishRequestId; }

    public Long getSourceContentRevisionId() { return sourceContentRevisionId; }

    public Long getCreatedBy() { return createdBy; }
}
