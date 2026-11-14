package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.service.impl.TopicPublicationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** 已批准主题发行的送审口：复用 TopicPublicationService 的 BIZ_TOPIC 组合审核冻结与送审，不触发发布前公开。 */
@Component
public class TopicReviewBundleSubmitter implements ApprovedTopicReleases.ReviewSubmitter {
    private final TopicPublicationService publication;

    public TopicReviewBundleSubmitter(@Lazy TopicPublicationService publication) { this.publication = publication; }

    @Override
    public long submit(long topicId, long memberId) {
        Long taskId = publication.submitReviewBundle(topicId, memberId);
        if (taskId == null || taskId <= 0) throw new IllegalStateException("送审任务创建失败");
        return taskId;
    }
}
