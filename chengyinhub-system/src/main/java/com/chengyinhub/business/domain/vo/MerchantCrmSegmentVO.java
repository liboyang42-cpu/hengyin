package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.dto.MerchantCrmSegmentFilter;
import java.util.Date;

public class MerchantCrmSegmentVO {
    private Long id;
    private String name;
    private MerchantCrmSegmentFilter filter;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public MerchantCrmSegmentFilter getFilter() { return filter; }
    public void setFilter(MerchantCrmSegmentFilter value) { filter = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
