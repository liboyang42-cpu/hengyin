package com.chengyinhub.business.domain;

import java.util.Date;

/** 开售时刻冻结的对公收款人事实；写入后不得更新，FINAL/应付只读它，不得回读最新 profile。 */
public class TopicSalePayeeSnapshot {
    private Long id;
    private Long topicId;
    private Long clubId;
    private Long profileId;
    private Long profileVersion;
    private String factsHash;
    private String licenseHash;
    private Date frozenAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public Long getProfileId() { return profileId; } public void setProfileId(Long v) { profileId = v; }
    public Long getProfileVersion() { return profileVersion; } public void setProfileVersion(Long v) { profileVersion = v; }
    public String getFactsHash() { return factsHash; } public void setFactsHash(String v) { factsHash = v; }
    public String getLicenseHash() { return licenseHash; } public void setLicenseHash(String v) { licenseHash = v; }
    public Date getFrozenAt() { return frozenAt; } public void setFrozenAt(Date v) { frozenAt = v; }
}
