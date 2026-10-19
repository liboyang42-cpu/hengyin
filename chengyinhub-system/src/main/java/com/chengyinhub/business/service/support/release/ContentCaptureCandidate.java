package com.chengyinhub.business.service.support.release;

/** Immutable server-only draft capture. Neither a release nor approval, license or issuance authority. */
public final class ContentCaptureCandidate {
    private final long topicId;
    private final long draftConfigVersion;
    private final String json;
    private final String hash;

    ContentCaptureCandidate(long topicId, long draftConfigVersion, String json) {
        this.topicId = topicId;
        this.draftConfigVersion = draftConfigVersion;
        this.json = json;
        this.hash = CanonicalContentJson.sha256(json);
    }

    public long topicId() { return topicId; }
    public long draftConfigVersion() { return draftConfigVersion; }
    public String contentHash() { return hash; }
    public String serverOnlyJson() { return json; }
    public boolean isCompleteRelease() { return false; }
    public void requireCompleteRelease() {
        throw new IllegalStateException("Capture lacks full validation, domain authority and release approval");
    }
}
