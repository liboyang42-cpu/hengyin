package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit text-only projection. Never serialize the internal capture row or canonical JSON. */
public final class ContentCapturePreviewVO {
    private final String auditTaskId, snapshotId, topicId, sourceConfigVersion, contentHash;
    private final int auditTaskVersion, status;
    private final List<TextEntry> entries;
    public ContentCapturePreviewVO(long task, int version, int status, long snapshot, long topic,
            long sourceVersion, String hash, List<TextEntry> entries) {
        this.auditTaskId=Long.toString(task); this.auditTaskVersion=version; this.status=status;
        this.snapshotId=Long.toString(snapshot); this.topicId=Long.toString(topic);
        this.sourceConfigVersion=Long.toString(sourceVersion); this.contentHash=hash;
        this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
    }
    public String getContract() { return "questify.content-capture-preview.v1"; }
    public String getAuditTaskId() { return auditTaskId; }
    public int getAuditTaskVersion() { return auditTaskVersion; }
    public int getStatus() { return status; }
    public String getSnapshotId() { return snapshotId; }
    public String getTopicId() { return topicId; }
    public String getSourceConfigVersion() { return sourceConfigVersion; }
    public String getContentHash() { return contentHash; }
    public String getTextEncoding() { return "HTML_ESCAPED_PLAIN_TEXT"; }
    public String getReviewScope() { return "INCOMPLETE_CANDIDATE_TEXT_ONLY"; }
    public boolean isCompleteRelease() { return false; }
    public boolean isPublicationEligible() { return false; }
    public boolean isPreviewComplete() { return false; }
    public List<TextEntry> getEntries() { return entries; }
    public static final class TextEntry {
        private final String path, text;
        public TextEntry(String path, String text) { this.path=path; this.text=text; }
        public String getPath() { return path; }
        public String getText() { return text; }
    }
}
