package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantCustomerQuery;

import java.util.Map;

/**
 * 项目玩家名单。
 *
 * 商家开店前想知道的是「谁要来、谁还没来」,不是「已售 24 份」——
 * 所以这里给的是名单,不是统计。
 */
public interface IProjectPlayerReadService {

    /**
     * @param memberId 当前登录用户
     * @param topicId  主题
     * @return { rows: [...], summary: {...}, contactVisible, contactHint }
     *         两参默认不返回敏感联系方式。有权调用方必须显式使用三参重载。
     */
    Map<String, Object> listPlayers(Long memberId, Long topicId);

    /**
     * @param sensitiveContactVisible 当前操作者是否拥有联系方式权限。必须由 Controller 的主体权限门禁明确传入。
     */
    Map<String, Object> listPlayers(Long memberId, Long topicId, boolean sensitiveContactVisible);

    /**
     * 商家客户名册:把上面那份「按项目」的名单跨项目按人归拢。
     *
     * 商家真正要问的是「这个人到底什么情况」——买了票没来?买了又退了?来过几次?
     * 所以每行给的是状态分档(已到店 / 待到店 / 已退款),不是订单金额流水。
     *
     * ⚠️ 口径必须与 listPlayers 同源(都读 cms_registration 的
     * payment_status + verification_status),否则同一个人在项目页和名册页会显示成
     * 两种状态。联系方式同样沿用那套「谁组织谁负责联系」的闸,不在这里另开口子。
     *
     * 筛选与分页都在这里做:分段筛必须**先于**分页,放调用方筛就只能筛到当前这一页,
     * 「待核销 12」点进去只看到 3 个是最难解释的 bug。
     *
     * @param memberId 当前商家 Owner 的 memberId
     * @param query    关键词 / 分段 / 分页;为 null 时按默认第一页全部
     * @param sensitiveContactVisible 当前操作者是否拥有 CRM_SENSITIVE_READ
     * @return { rows: [...], total, summary: {...}, segmentCounts: {...} }
     */
    Map<String, Object> listMerchantCustomers(Long memberId, MerchantCustomerQuery query,
                                              boolean sensitiveContactVisible);
}
