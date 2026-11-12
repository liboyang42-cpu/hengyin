package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantVerifyMetric;
import com.chengyinhub.business.service.support.MerchantMetricPeriod;

import java.util.List;

/**
 * D-93(2026-11-11 owner):营销首页({@code /api/merchant/marketing-home}、{@code /api/merchant/funnel})
 * 与店铺参谋({@code /api/ai/merchant/insight})共用的商家指标服务。
 *
 * <p>时间窗与「核销」这两项两页都在显示的指标只在这里解析和计算;两页不得再各自现算。
 * ownerMemberId 一律是服务端 {@code MerchantAccessContext#getOwnerMemberId()},不收前端参数。
 */
public interface IMerchantMetricsService {

    /** 支持的窗口:7 / 30 / 90 个自然日(营销首页只开放 7 / 30)。 */
    MerchantMetricPeriod window(int windowDays);

    /** 同一时刻解析多个窗口,避免跨 0 点时各窗口「今天」不一致。 */
    List<MerchantMetricPeriod> windows(int... windowDays);

    /** 各窗口的核销指标(与入参窗口一一对应);一次查询覆盖最长窗口的上期至今。 */
    List<MerchantVerifyMetric> verify(Long ownerMemberId, List<MerchantMetricPeriod> windows);
}
