package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 主题详情内活动场次白名单；小程序只依赖 id/name，保留展示所需摘要。 */
public class PublicActivitySummaryVO {
    private Long id;
    private String name;
    private String imgUrl;
    private String addressName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;

    public static PublicActivitySummaryVO from(CmsActivity source) {
        PublicActivitySummaryVO view = new PublicActivitySummaryVO();
        if (source != null) {
            view.setId(source.getId());
            view.setName(source.getName());
            view.setImgUrl(source.getImgUrl());
            view.setAddressName(source.getAddressName());
            view.setStartDate(source.getStartDate());
            view.setEndDate(source.getEndDate());
        }
        return view;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
}
