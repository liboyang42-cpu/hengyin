package com.chengyinhub.business.service.support.release;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Server-only candidate content, immutable and never a player projection or release approval. */
public final class ContentReleaseManifest {
    public static final int SCHEMA_VERSION = 1;

    public enum UnresolvedDependency {
        REWARD_RULES, ASSET_RIGHTS, REGISTERED_RULE_REFERENCES, TEMPLATE_RIGHTS, NPC_KNOWLEDGE,
        ACTIVITY_MODULE_REFERENCES, LINEAR_NODE_ORDER
    }

    private final long topicId;
    private final long sourceConfigVersion;
    private final String serverManifestJson;
    private final String contentHash;
    private final List<UnresolvedDependency> unresolvedDependencies;

    ContentReleaseManifest(long topicId, long sourceConfigVersion, String serverManifestJson,
                           List<UnresolvedDependency> unresolvedDependencies) {
        this.topicId = topicId;
        this.sourceConfigVersion = sourceConfigVersion;
        this.serverManifestJson = serverManifestJson;
        this.contentHash = CanonicalContentJson.sha256(serverManifestJson);
        this.unresolvedDependencies = Collections.unmodifiableList(
                new ArrayList<UnresolvedDependency>(unresolvedDependencies));
    }

    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public long getTopicId() { return topicId; }
    public long getSourceConfigVersion() { return sourceConfigVersion; }
    public String getContentHash() { return contentHash; }
    public List<UnresolvedDependency> getUnresolvedDependencies() { return unresolvedDependencies; }

    /** Contains answers and unrevealed story. Never return this from a controller or log it. */
    @JsonIgnore
    public String serverOnlyJson() { return serverManifestJson; }

    public boolean isCompleteRelease() { return unresolvedDependencies.isEmpty(); }

    public void requireCompleteRelease() {
        if (!isCompleteRelease()) {
            throw new IllegalStateException("Snapshot foundation is not a complete publishable release: "
                    + unresolvedDependencies);
        }
    }
}
