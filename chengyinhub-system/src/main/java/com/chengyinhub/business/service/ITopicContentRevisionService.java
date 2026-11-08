package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWrite;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;

/**
 * 不可变内容修订(BE-06)。
 *
 * <p>职责边界:只负责「一次正式发布 → 一条不可改写修订」这一层索引。
 * 冻结内容仍由 {@code TopicRouteRuntimeServiceImpl::freezePublication/freezePublishedJourney}
 * 生产,发布头仍由 {@code CmsTopicMapper::publishRouteSnapshot} 维护,本服务不复制任何编译器逻辑。
 *
 * <p>授权不变:本服务不做身份判定。调用方必须先用既有 scope 解析出合法 topicId,
 * 再按 (topicId, revisionId) 取修订 —— 没有「只凭 revisionId」的入口,所以任意 releaseId
 * 读不到别人主题的内容。公共视图里永远没有 {@code snapshotJson}。
 */
public interface ITopicContentRevisionService {

    /**
     * 在正式发布事务内写入本条修订。必须与发布头推进处于同一事务:
     * 先插修订,再推进 {@code published_config_version};发布头失败时整事务回滚,
     * 不会留下可被下载的孤立修订。
     *
     * @return {@code idempotentReplay=true} 表示同号同 payload 重试,发布头已推进过,调用方必须短路
     */
    TopicContentRevisionWriteResult recordPublishedRevision(TopicContentRevisionWrite write);

    /**
     * 存量已发布头 → LEGACY_IMPORTED 修订。显式迁移动作,只允许在写事务里调用;
     * hash 取 {@code published_route_graph_json} 的真实存储字节,绝不用当下 live 模板重建。
     * 当前发布头内容已被索引过则直接返回既有修订(幂等)。
     */
    CmsTopicContentRevision importLegacyPublished(Long topicId, Long createdBy);

    /** 当前发布头对应的修订;老主题没有任何修订时返回 null(调用方按 legacy 处理)。 */
    TopicContentRevisionView currentPublished(Long topicId);

    /** 指定修订的公共视图;revisionId 不属于该 topic 时返回 null。 */
    TopicContentRevisionView publishedRevision(Long topicId, Long revisionId);

    /**
     * 按发布意图号取既有修订(F1 发布入口重试用)。
     *
     * <p>与 {@link #recordPublishedRevision} 的幂等分支同一口径:同号同 payload 返回既有行,
     * {@code expectedContentSha256} 存在且与存量不一致时抛 409。没有该意图的修订返回 null
     * (调用方按「这是一次新发布」继续原流程,不得因此跳过任何既有闸)。
     */
    CmsTopicContentRevision publishedByRequestId(Long topicId, String publishRequestId,
                                                String expectedContentSha256);

    /**
     * 服务端内部:读取精确冻结私有 payload(含答案)。仅供开局/重放等权威路径使用,
     * <b>禁止</b>从控制器直接返回或塞进公共响应。
     */
    String frozenSnapshot(Long topicId, Long revisionId);
}
