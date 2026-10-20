package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.TopicRouteSession;

/**
 * Immutable capture of existing server content snapshots, not a new runtime model.
 * Authorization and consistent database reads belong to the future caller; this is not a bean.
 * Never capture stateJson, actor/member/team IDs, seeds, action receipts or payment facts.
 */
public final class ContentReleaseSource {
    private final long topicId;
    private final long sourceConfigVersion;
    private final String routeSnapshot;
    private final String activityModuleSnapshot;

    private ContentReleaseSource(long topicId, long sourceConfigVersion,
                                 String routeSnapshot, String activityModuleSnapshot) {
        this.topicId = topicId;
        this.sourceConfigVersion = sourceConfigVersion;
        this.routeSnapshot = routeSnapshot;
        this.activityModuleSnapshot = activityModuleSnapshot;
    }

    /** Old frozen versions remain valid even when CmsTopic has a newer publishedConfigVersion. */
    public static ContentReleaseSource fromSnapshots(TopicRouteSession route, GameSession activity) {
        if (route == null || route.getTopicId() == null || route.getTopicId() <= 0
                || route.getConfigVersion() == null || route.getConfigVersion() <= 0) {
            throw new IllegalArgumentException("A versioned topic route snapshot is required");
        }
        if (activity != null && (!route.getTopicId().equals(activity.getTopicId())
                || route.getActivityId() == null || !route.getActivityId().equals(activity.getActivityId()))) {
            throw new IllegalArgumentException("Activity module snapshot must belong to the same topic and activity");
        }
        if (activity != null && activity.getModuleConfigSnapshotJson() == null) {
            throw new IllegalArgumentException("Activity module snapshot is missing");
        }
        return new ContentReleaseSource(route.getTopicId(), route.getConfigVersion(),
                route.getConfigSnapshotJson(), activity == null ? null : activity.getModuleConfigSnapshotJson());
    }

    long topicId() { return topicId; }
    long sourceConfigVersion() { return sourceConfigVersion; }
    String routeSnapshot() { return routeSnapshot; }
    String activityModuleSnapshot() { return activityModuleSnapshot; }

    /** Provenance only: neither an installation license nor evidence of rights or payment. */
    public static final class TemplateSource {
        final long nodeId;
        final String sourceReference;
        final String sourceVersion;
        final String sourceContentHash;

        public TemplateSource(long nodeId, String sourceReference, String sourceVersion, String sourceContentHash) {
            this.nodeId = nodeId;
            this.sourceReference = sourceReference;
            this.sourceVersion = sourceVersion;
            this.sourceContentHash = sourceContentHash;
        }
    }

    /** Optional AI contract metadata; a reference/hash never grants retrieval access. */
    public static final class NpcBinding {
        final long topicId;
        final long nodeId;
        final String roleKey;
        final boolean aiEnabled;
        final String knowledgeReference;
        final String knowledgeVersion;
        final String knowledgeContentHash;

        public NpcBinding(long topicId, long nodeId, String roleKey, boolean aiEnabled,
                          String knowledgeReference, String knowledgeVersion, String knowledgeContentHash) {
            this.topicId = topicId;
            this.nodeId = nodeId;
            this.roleKey = roleKey;
            this.aiEnabled = aiEnabled;
            this.knowledgeReference = knowledgeReference;
            this.knowledgeVersion = knowledgeVersion;
            this.knowledgeContentHash = knowledgeContentHash;
        }
    }
}
