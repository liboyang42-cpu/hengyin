package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.PlayerAffiliateSummaryVO;

/**
 * 玩家本人推广收入只读汇总。
 *
 * <p>唯一身份来源是 {@code callerMemberId}（控制器从登录 principal 取得）；
 * 不接收客户端 memberId，也没有任何写入口。
 */
public interface IPlayerAffiliateService {

    PlayerAffiliateSummaryVO summary(long callerMemberId, Integer limit);
}
