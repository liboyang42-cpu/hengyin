package com.chengyinhub.business.service;

import java.util.List;

/**
 * 通行证覆盖只读投影（<b>从权威事实读取</b>）。
 *
 * <p>数据源是 {@code cms_registration}（自玩通行证：{@code owner_type=1}、
 * {@code owner_id=topicId}、{@code purchase_kind=2}、{@code payment_status=2}、
 * {@code registration_status=2}、未过期）。本接口<b>不</b>读营销商品表，
 * 也<b>不</b>替代 {@code PlayNodeAccessService} 的准入判定。
 */
public interface IMarketingPassAccessService {

    /** 该会员此刻是否持有覆盖该主题的有效自玩通行证。 */
    boolean hasActiveAccess(long memberId, long topicId);

    /** 该会员当前有效覆盖的主题 id 列表（只读投影）。 */
    List<Long> activeTopicIds(long memberId);
}
