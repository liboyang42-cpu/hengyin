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
    /**
     * F-W5-08:主题自己的自办活动壳(generateSelfHostActivity 生成的公开售卖面),不是俱乐部开的场次。
     * 玩家购买仍要用它,所以不从列表里删;俱乐部「接下来」场次列表按此过滤。
     */
    private boolean selfHostShell;

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

    /**
     * 带主题上下文的投影。自办壳的判据与建壳/刷壳同源(CmsTopicServiceImpl.ensureSelfHostActivityShell /
     * refreshSelfHostActivity 都按「本主题 + 发布者本人」认壳):发布者本人名下、且没有挂俱乐部
     * (成员限定主题的壳会继承本主题俱乐部)的活动。
     */
    public static PublicActivitySummaryVO from(CmsActivity source, com.chengyinhub.business.domain.CmsTopic topic) {
        PublicActivitySummaryVO view = from(source);
        view.setSelfHostShell(isSelfHostShell(source, topic));
        return view;
    }

    static boolean isSelfHostShell(CmsActivity activity, com.chengyinhub.business.domain.CmsTopic topic) {
        if (activity == null || topic == null || activity.getMemberId() == null
                || !activity.getMemberId().equals(topic.getMemberId())) {
            return false;
        }
        Long clubId = activity.getClubId();
        if (clubId == null || clubId <= 0L) {
            return true;
        }
        return Integer.valueOf(1).equals(topic.getClubMemberOnly()) && clubId.equals(topic.getClubId());
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
    public boolean isSelfHostShell() { return selfHostShell; }
    public void setSelfHostShell(boolean selfHostShell) { this.selfHostShell = selfHostShell; }
}
