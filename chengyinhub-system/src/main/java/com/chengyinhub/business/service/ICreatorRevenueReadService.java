package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;

import java.util.Date;

/**
 * 创作者收益只读服务：原账本口径（应计/结算，不是实收、不是可提现）。
 *
 * <p>未指定期间时按「最近 30 个自然日（不含今天）」取。
 * 读失败抛异常，由调用方表达为 {@code revenueState=UNKNOWN}，绝不返回 0 收入。
 */
public interface ICreatorRevenueReadService {

    CreatorRevenueLedgerVO read(Date fromInclusive, Date toExclusive);

    /**
     * 单个会员视角：只保留 receiver_kind=MEMBER 且 member 匹配的条目；
     * ACCOUNTING 分项永不进入该会员的净额。
     */
    CreatorRevenueLedgerVO readForMember(Long memberId, Date fromInclusive, Date toExclusive);
}
