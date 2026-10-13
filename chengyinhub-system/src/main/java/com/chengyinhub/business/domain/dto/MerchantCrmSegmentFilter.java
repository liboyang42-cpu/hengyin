package com.chengyinhub.business.domain.dto;

/** 可持久化分群的唯一白名单；不接受 SQL、排序、merchantId 或任意表达式。 */
public class MerchantCrmSegmentFilter {
    private String segment;
    private Long tagId;
    private Integer sourceType;
    private String sourceStart;
    private String sourceEnd;

    public String getSegment() { return segment; }
    public void setSegment(String value) { segment = value; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long value) { tagId = value; }
    public Integer getSourceType() { return sourceType; }
    public void setSourceType(Integer value) { sourceType = value; }
    public String getSourceStart() { return sourceStart; }
    public void setSourceStart(String value) { sourceStart = value; }
    public String getSourceEnd() { return sourceEnd; }
    public void setSourceEnd(String value) { sourceEnd = value; }
}
