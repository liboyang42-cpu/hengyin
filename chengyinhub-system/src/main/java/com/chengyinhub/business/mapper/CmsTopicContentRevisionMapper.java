package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 不可变内容修订索引(BE-06)。只追加,没有任何 update/delete 入口。
 *
 * <p>调用约定:写侧必须持有主题行锁({@code cms_topic} 同一事务内 {@code for update}),
 * 由服务的 {@code recordPublishedRevision} 统一保证;唯一键是硬兜底,不是主串行手段。
 *
 * <p>注意:会话开局的「当前修订头」读在 {@code CmsTopicMapper.selectLatestContentRevisionHeader}
 * —— 那里已经注入 {@code CmsTopicMapper},不必为一次 header 读给运行期服务加依赖。
 * 重 payload(含 snapshot_json)与历史修订只走本 Mapper。
 */
public interface CmsTopicContentRevisionMapper {

    int insertRevision(CmsTopicContentRevision revision);

    /** 同一发布意图号的既有修订;没有返回 null。唯一键 uk_topic_publish_request 保证最多一条。 */
    CmsTopicContentRevision selectByPublishRequestId(@Param("topicId") Long topicId,
                                                     @Param("publishRequestId") String publishRequestId);

    /** 该主题当前修订号;没有任何修订返回 null(调用方按 0 处理)。 */
    Long selectMaxRevisionNo(@Param("topicId") Long topicId);

    /** 含私有 payload 的单条修订;revision 必须属于该主题。 */
    CmsTopicContentRevision selectByIdAndTopic(@Param("id") Long id, @Param("topicId") Long topicId);

    /** 当前发布头对应的修订(最大 revision_no);老主题没有修订返回 null。 */
    CmsTopicContentRevision selectLatestByTopicId(@Param("topicId") Long topicId);

    /** 采用写事务的当前读；必须先持 cms_topic 父行锁。 */
    CmsTopicContentRevision selectLatestForAdoption(@Param("topicId") Long topicId);

    /** 窄视图,不含 snapshot_json。 */
    TopicContentRevisionView selectViewByTopicIdAndId(@Param("topicId") Long topicId, @Param("id") Long id);

    /** 窄视图,当前发布头。 */
    TopicContentRevisionView selectLatestViewByTopicId(@Param("topicId") Long topicId);

    /** 该主题全部修订头(不含 payload),按修订号升序;给发布差异/历史读面用。 */
    List<TopicContentRevisionView> selectViewsByTopicId(@Param("topicId") Long topicId);
}
