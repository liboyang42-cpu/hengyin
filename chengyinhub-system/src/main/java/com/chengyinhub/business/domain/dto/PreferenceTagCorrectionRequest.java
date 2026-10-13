package com.chengyinhub.business.domain.dto;

/** 玩家在确认前修正结果标签档位。 */
public class PreferenceTagCorrectionRequest {
    private String tagValue;

    public String getTagValue() { return tagValue; }
    public void setTagValue(String tagValue) { this.tagValue = tagValue; }
}
