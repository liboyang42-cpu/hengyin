package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;

import java.util.List;

/**
 * BIZ_ACTIVITY 审核单快照(AUDIT-G1-5)。
 *
 * <p>此前快照只有 name,审核员看不到自己批的是什么。现在带上简介、封面/图集、票价、
 * 俱乐部与主办会员,先审单、抽检单、编辑重提共用这一份口径。
 */
public final class ActivityAuditSnapshot {

    private ActivityAuditSnapshot() { }

    /** 按已落库(或即将落库)的活动行 + 活票生成。 */
    public static String of(Long activityId, CmsActivity activity, List<OmsTicket> tickets, String flagReason) {
        JSONObject snap = head(activityId, flagReason);
        if (activity != null) {
            snap.put("name", activity.getName());
            snap.put("description", activity.getDescription());
            snap.put("imgUrl", activity.getImgUrl());
            snap.put("imgArr", activity.getImgArr());
            snap.put("categoryIds", activity.getCategoryIds());
            snap.put("memberId", activity.getMemberId());
            snap.put("clubId", activity.getClubId());
            snap.put("topicId", activity.getTopicId());
        }
        JSONArray prices = new JSONArray();
        if (tickets != null) {
            for (OmsTicket ticket : tickets) {
                if (ticket == null) continue;
                prices.add(ticket(ticket.getName(), ticket.getPrice()));
            }
        }
        snap.put("tickets", prices);
        return snap.toJSONString();
    }

    /** 按发布/编辑请求生成;ownerMemberId 是主办主体(商家店员只是操作人)。 */
    public static String of(Long activityId, CmsActivityPublishRequest request, Long ownerMemberId, String flagReason) {
        JSONObject snap = head(activityId, flagReason);
        if (request != null) {
            snap.put("name", request.getName());
            snap.put("description", request.getDescription());
            snap.put("imgUrl", request.getImgUrl());
            snap.put("imgArr", request.getImgArr());
            snap.put("categoryIds", request.getCategoryIds());
            snap.put("clubId", request.getClubId());
        }
        snap.put("memberId", ownerMemberId);
        JSONArray prices = new JSONArray();
        if (request != null && request.getTickets() != null) {
            for (ActivityTicketRequest ticket : request.getTickets()) {
                if (ticket == null) continue;
                prices.add(ticket(ticket.getName(), ticket.getPrice()));
            }
        }
        snap.put("tickets", prices);
        return snap.toJSONString();
    }

    private static JSONObject head(Long activityId, String flagReason) {
        JSONObject snap = new JSONObject();
        snap.put("bizType", "ACTIVITY");
        snap.put("bizId", activityId);
        if (flagReason != null) {
            snap.put("flagReason", flagReason);
        }
        return snap;
    }

    private static JSONObject ticket(String name, java.math.BigDecimal price) {
        JSONObject row = new JSONObject();
        row.put("name", name);
        row.put("price", price);
        return row;
    }
}
