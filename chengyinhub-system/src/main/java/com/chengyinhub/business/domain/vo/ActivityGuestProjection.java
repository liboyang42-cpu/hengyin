package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;

import java.util.List;

/**
 * walk-fix Round5 #6:游客(未登录)打开分享来的活动详情时的公开字段投影。
 * 只留活动本身的公开信息与聚合数(票种、价格、库存、时间地点、阵容、评分/评价数);
 * 去掉报名名单(会员头像/昵称/ID)、评价明细(评价人会员信息)与销售态(是否已有在售订单)。
 * 手机号本来就不在 ActivityInfoVO / Public*VO 白名单里。
 */
public final class ActivityGuestProjection {
    private ActivityGuestProjection() {
    }

    /** 游客只能看到已审核通过且在架的活动;下架、待审、驳回一律按不存在处理。 */
    public static boolean publiclyVisible(CmsActivity activity) {
        return activity != null
                && Long.valueOf(1L).equals(activity.getStatus())
                && Long.valueOf(1L).equals(activity.getPublishStatus());
    }

    public static ActivityInfoVO apply(ActivityInfoVO vo) {
        if (vo == null) return null;
        vo.setRegistrationList(null);
        vo.setCommentList(null);
        vo.setIsSignUp(0);
        vo.setTimeLocationLocked(Boolean.FALSE);
        List<PublicTicketVO> tickets = vo.getOmsTicketList();
        if (tickets != null) {
            for (PublicTicketVO ticket : tickets) {
                if (ticket != null) ticket.setCmsRegistrationList(null);
            }
        }
        return vo;
    }
}
