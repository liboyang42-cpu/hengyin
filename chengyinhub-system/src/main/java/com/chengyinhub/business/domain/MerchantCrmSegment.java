package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantCrmSegment {
    private Long id;
    private Long merchantId;
    private String name;
    private String filterJson;
    private Long actorMemberId;
    private String requestId;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    /** mms_merchant.id；保存分群的门店级归属主键。 */
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String value) { filterJson = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
}
