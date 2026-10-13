package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import org.apache.ibatis.annotations.Param;

public interface TopicSaleSnapshotMapper {
    TopicSaleCanonicalDraft selectDraftByTopicId(Long topicId);
    TopicSaleCanonicalDraft selectDraftByTopicIdForUpdate(Long topicId);
    int insertDraft(TopicSaleCanonicalDraft draft);
    int updateDraftVersioned(@Param("draft") TopicSaleCanonicalDraft draft,
                             @Param("expectedVersion") Long expectedVersion);
    int advanceDraftVersion(@Param("topicId") Long topicId,
                            @Param("expectedVersion") Long expectedVersion,
                            @Param("updateTime") java.util.Date updateTime);

    TopicSaleSnapshot selectSnapshotByTopicId(Long topicId);
    int insertSnapshot(TopicSaleSnapshot snapshot);
}
