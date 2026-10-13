package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ClubCustomerVO;

import java.util.List;

/**
 * 俱乐部客户(按人聚合)。
 *
 * 与另外两件事分清楚,别合并:
 *   · 报名名册 IClubService/topicRegistrations —— 按团聚合,能清退退款(owner-only)
 *   · 本场名册 / 核销 —— 按场次聚合
 *   · 客户(本接口)—— 按人聚合,能打标签写备注
 */
public interface IClubCrmAppService {

    ClubCustomerVO.ListPage listCustomers(Long principalMemberId, Long clubId, String filter, String keyword);

    /** 稿 M 268:223「查看客户」那一行右侧的人数。只要一个数,别为它把整页 200 行端下来。 */
    int customerCount(Long principalMemberId, Long clubId);

    ClubCustomerVO.Detail customerDetail(Long principalMemberId, Long clubId, Long customerMemberId);

    void saveTagRemark(Long principalMemberId, Long clubId, Long customerMemberId,
                       List<String> tags, String remark, String requestId);

    ClubCustomerVO.TopicPage topicCustomers(Long principalMemberId, Long clubId, Long topicId, String filter);

    /**
     * 第二轮拍板第 1 条:俱乐部活动详情的管理向字段 —— nodeCount / sessionHeadcount(人数) /
     * pendingVerifyCount / verifiedByMeCount / canViewVerify / canManageSessions / canDirect。
     * 人数三项只给能看核销名单的岗位(canViewVerify),其他人拿到 null(不是 0)。
     */
    java.util.Map<String, Object> topicManageStats(Long principalMemberId, Long clubId, Long topicId, Long activityId);

    /** Figma K3 核销详情 · 俱乐部端。手机号按岗位权限由服务端裁。 */
    ClubCustomerVO.CheckinDetail checkinDetail(Long principalMemberId, Long clubId, Long registrationId);
}
