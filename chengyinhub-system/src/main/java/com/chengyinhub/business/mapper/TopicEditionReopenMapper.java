package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 合同一专用读写面：字段列在 XML 固定，禁止借通用 copy mapper 扩散复制范围。 */
public interface TopicEditionReopenMapper {
    int insertReopenRequest(TopicEditionReopenRequest request);

    TopicEditionReopenRequest selectReopenRequest(@Param("sourceTopicId") Long sourceTopicId,
                                                   @Param("idempotencyKey") String idempotencyKey);

    CmsTopic selectSourceTopicForUpdate(@Param("sourceTopicId") Long sourceTopicId);

    ProductRole selectSourceContentForUpdate(@Param("sourceTopicId") Long sourceTopicId);

    List<CmsTopicChapter> selectSourceChapters(@Param("sourceTopicId") Long sourceTopicId);

    List<CmsTopicNode> selectSourceNodes(@Param("sourceTopicId") Long sourceTopicId);

    int insertReopenedTopic(CmsTopic topic);

    int insertReopenedChapter(CmsTopicChapter chapter);

    int insertReopenedNode(CmsTopicNode node);

    int updateReopenedChapterBlocks(@Param("chapterId") Long chapterId, @Param("blocksJson") String blocksJson);

    /** R8-STORY:新期剧情配置在章节重建后按 old→new 章节 id 重映射再回填。 */
    int updateReopenedJourneyStory(@Param("topicId") Long topicId, @Param("journeyStoryJson") String journeyStoryJson);

    int completeReopenRequest(@Param("requestId") Long requestId, @Param("newTopicId") Long newTopicId);
}
