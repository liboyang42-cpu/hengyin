package com.chengyinhub.business.util;

import java.util.Date;
import com.chengyinhub.business.domain.CmsActivity;

/**
 * 一场 cms_activity 在某一时刻的生命周期状态 —— 「我的项目」列表与活动详情共用这一个判定。
 *
 * 为什么要有这个类(CU-M-106):列表页读后端算出的 stateText,详情页却把落库的
 * cms_activity.status 当成 0草稿/1即将开始/2报名中/3进行中 那套**官方活动(official_event)**
 * 生命周期码来查文案。可 cms_activity.status 是**审核码**(建单 0、审核通过 1、未通过 2,
 * 见 CmsActivityServiceImpl 的 setStatus 与 CmsActivityMapper.xml 里 `status = 1` 的上架闸),
 * 于是同一行数据在列表说「进行中」、在详情说「即将开始」。状态判定只有一个函数,
 * 两个视图不可能再各说一套。
 *
 * 注意:本类只服务 cms_activity。official_event.status 仍是它自己那套 0..9 生命周期码,
 * 不归本类解释。
 */
public final class ActivityLifecycle
{
    private ActivityLifecycle() { }

    public static String state(CmsActivity activity)
    {
        if (activity.getCancelTime() != null) return "cancelled";
        return state(activity.getStatus(), activity.getPublishStatus(), activity.getStartDate(), activity.getEndDate());
    }

    /** 活动归一 state(无草稿态):审核未过 > 审核中 > 已下架 > 按档期分桶。 */
    public static String state(Long status, Long publishStatus, Date start, Date end)
    {
        int audit = status == null ? 0 : status.intValue();
        int publish = publishStatus == null ? 0 : publishStatus.intValue();
        if (audit == 2) return "rejected";
        if (audit == 0) return "pending";
        if (publish != 1) return "offline";
        return timeBucket(start, end);
    }

    /** 在架项按时间分桶:now<start=未开始 / now>end=已完成 / 其余(含无日期)=进行中。 */
    public static String timeBucket(Date start, Date end)
    {
        Date now = new Date();
        if (start != null && now.before(start)) return "notStarted";
        if (end != null && now.after(end)) return "completed";
        return "running";
    }

    public static String stateText(String state)
    {
        switch (state == null ? "" : state)
        {
            case "cancelled": return "已取消";
            case "draft": return "草稿";
            case "pending": return "审核中";
            case "notStarted": return "未开始";
            case "running": return "进行中";
            case "completed": return "已完成";
            case "offline": return "已下架";
            case "rejected": return "未通过";
            default: return "";
        }
    }
}
