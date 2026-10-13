package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MerchantCrmCustomerSummary;
import java.util.ArrayList;
import java.util.List;

public class MerchantCrmCustomerDetailVO {
    private MerchantCrmCustomerSummary summary;
    private List<MerchantCrmSystemTagVO> systemTags = new ArrayList<>();
    private List<MerchantCrmTagVO> merchantTags = new ArrayList<>();
    /** 兼容旧客户端；内容与 merchantTags 相同。 */
    private List<MerchantCrmTagVO> tags = new ArrayList<>();
    private List<MerchantCrmTimelineItemVO> timeline = new ArrayList<>();

    public MerchantCrmCustomerSummary getSummary() { return summary; }
    public void setSummary(MerchantCrmCustomerSummary value) { summary = value; }
    public List<MerchantCrmSystemTagVO> getSystemTags() { return systemTags; }
    public void setSystemTags(List<MerchantCrmSystemTagVO> value) { systemTags = value; }
    public List<MerchantCrmTagVO> getMerchantTags() { return merchantTags; }
    public void setMerchantTags(List<MerchantCrmTagVO> value) { merchantTags = value; }
    public List<MerchantCrmTagVO> getTags() { return tags; }
    public void setTags(List<MerchantCrmTagVO> value) { tags = value; }
    public List<MerchantCrmTimelineItemVO> getTimeline() { return timeline; }
    public void setTimeline(List<MerchantCrmTimelineItemVO> value) { timeline = value; }
}
