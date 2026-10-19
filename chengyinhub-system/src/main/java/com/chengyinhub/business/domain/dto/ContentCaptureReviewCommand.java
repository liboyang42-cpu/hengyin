package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** Exact candidate identity only. Member identity and review authority are server derived. */
public class ContentCaptureReviewCommand {
    @JsonDeserialize(using = StrictLong.class)
    private Long topicId;
    @JsonDeserialize(using = StrictLong.class)
    private Long snapshotId;
    @JsonDeserialize(using = StrictLong.class)
    private Long expectedVersion;
    @JsonDeserialize(using = StrictString.class)
    private String expectedHash;
    @JsonDeserialize(using = StrictString.class)
    private String requestId;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long value) { snapshotId = value; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long value) { expectedVersion = value; }
    public String getExpectedHash() { return expectedHash; }
    public void setExpectedHash(String value) { expectedHash = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }

    // Fail closed even when the application's ObjectMapper ignores unknown properties.
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported candidate review field");
    }

    /** Do not silently truncate versions or coerce JSON strings/floats into identity numbers. */
    public static final class StrictLong extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                throw new IllegalArgumentException("Candidate identity requires an integer");
            return parser.getLongValue();
        }
    }
    public static final class StrictString extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                throw new IllegalArgumentException("Candidate identity requires a string");
            return parser.getText();
        }
    }

    public boolean valid() {
        return topicId != null && topicId > 0 && snapshotId != null && snapshotId > 0
                && expectedVersion != null && expectedVersion >= 0
                && expectedHash != null && expectedHash.matches("[0-9a-f]{64}")
                && requestId != null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }
}
