package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;

/** 商家 CRM 客户页白名单筛选；merchant scope 始终由服务端访问上下文注入。 */
public class MerchantCrmCustomerQuery {
    private Integer pageNum;
    private Integer pageSize;
    private String keyword;
    private String segment;
    private Long tagId;
    private Integer sourceType;
    private String sourceStart;
    private String sourceEnd;
    @JsonIgnore private Long merchantId;
    /** 仅定向广播按主题收窄时由服务端设置,客户列表请求体不接收。 */
    @JsonIgnore private Long topicId;
    @JsonIgnore private String keywordLike;
    @JsonIgnore private Date sourceStartTime;
    @JsonIgnore private Date sourceEndExclusive;

    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer value) { pageNum = value; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer value) { pageSize = value; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String value) { keyword = value; }
    public String getSegment() { return segment; }
    public void setSegment(String value) { segment = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long value) { tagId = value; }
    public Integer getSourceType() { return sourceType; }
    public void setSourceType(Integer value) { sourceType = value; }
    public String getSourceStart() { return sourceStart; }
    public void setSourceStart(String value) { sourceStart = value; }
    public String getSourceEnd() { return sourceEnd; }
    public void setSourceEnd(String value) { sourceEnd = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public String getKeywordLike() { return keywordLike; }
    public void setKeywordLike(String value) { keywordLike = value; }
    public Date getSourceStartTime() { return sourceStartTime; }
    public void setSourceStartTime(Date value) { sourceStartTime = value; }
    public Date getSourceEndExclusive() { return sourceEndExclusive; }
    public void setSourceEndExclusive(Date value) { sourceEndExclusive = value; }
}
