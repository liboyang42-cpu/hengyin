package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ai.MerchantInsightResp;

/**
 * 商家店铺参谋:聚合本商家打卡/核销/客群/供给事实,再经 AI 网关生成经营解读与活动建议。
 * merchantMemberId 一律来自登录态(ums_member.id),不收前端参数。
 *
 * @author chengyinhub
 */
public interface IMerchantInsightService
{
    MerchantInsightResp insight(Long merchantMemberId);

    /** 升级5·商家数据看板:曝光/到店/核销率/复购率/在架供给 —— 纯数字直出,不走 AI。 */
    com.alibaba.fastjson2.JSONObject dashboard(Long merchantMemberId);
}
