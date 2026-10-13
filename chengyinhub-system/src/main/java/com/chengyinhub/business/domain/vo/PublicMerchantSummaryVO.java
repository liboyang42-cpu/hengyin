package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MmsMerchant;

/** 主题公开详情中的商家展示字段。 */
public class PublicMerchantSummaryVO {
    private String name;
    private String businessTime;

    public static PublicMerchantSummaryVO from(MmsMerchant source) {
        PublicMerchantSummaryVO out = new PublicMerchantSummaryVO();
        if (source != null) {
            out.setName(source.getName());
            out.setBusinessTime(source.getBusinessTime());
        }
        return out;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBusinessTime() { return businessTime; }
    public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
}
