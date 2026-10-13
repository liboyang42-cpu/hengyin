package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;

/** canonical 草稿保存合同；expectedParameterVersion 是防陈旧覆盖 CAS。 */
public class TopicSaleCanonicalDraftDTO {
    private String schemaVersion;
    private Long expectedParameterVersion;
    private TopicSaleCanonicalV1 canonical;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public Long getExpectedParameterVersion() { return expectedParameterVersion; }
    public void setExpectedParameterVersion(Long expectedParameterVersion) { this.expectedParameterVersion = expectedParameterVersion; }
    public TopicSaleCanonicalV1 getCanonical() { return canonical; }
    public void setCanonical(TopicSaleCanonicalV1 canonical) { this.canonical = canonical; }
}
