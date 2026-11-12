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

    /** D-93:支持的指标窗口 7 / 30 / 90 个自然日(营销首页只开放 7 / 30);与营销首页共用同一定义。 */
    com.chengyinhub.business.service.support.MerchantMetricPeriod metricWindow(int windowDays);

    /** D-93:同一时刻解析多个窗口,避免跨 0 点时各窗口「今天」不一致。 */
    java.util.List<com.chengyinhub.business.service.support.MerchantMetricPeriod> metricWindows(int... windowDays);

    /** D-93:各窗口核销指标(与入参窗口一一对应);ownerMemberId 为服务端登录态。 */
    java.util.List<com.chengyinhub.business.domain.dto.MerchantVerifyMetric> metricVerify(
            Long ownerMemberId, java.util.List<com.chengyinhub.business.service.support.MerchantMetricPeriod> windows);
}
