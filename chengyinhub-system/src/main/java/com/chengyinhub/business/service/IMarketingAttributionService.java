package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.AttributionClaimRequest;
import com.chengyinhub.business.domain.dto.AttributionClaimResult;
import com.chengyinhub.business.domain.dto.ReferralTokenIssueRequest;
import com.chengyinhub.business.domain.dto.ReferralTokenView;

/**
 * 营销推广归因(CO-09 归因半边)。<b>只写归因事实,不产生任何钱或权益</b>:
 * 不改报价、不改锁价、不改支付状态、不写结算/应付、不发佣金、不碰 cms_registration。
 */
public interface IMarketingAttributionService {

    /**
     * 为当前登录会员签发一枚推广来源凭据(token 明文只返回一次)。
     * 目标须存在且已审核通过;凭据本身不含价格或分佣参数。
     */
    ReferralTokenView issueToken(long sharerMemberId, ReferralTokenIssueRequest request);

    /**
     * 把某笔报名单归因到一枚凭据。幂等:同凭据重复领取返回原事实且不新增写入;
     * 同单换凭据 → 409 ATTRIBUTION_CONFLICT(先写者胜,永不覆盖)。
     *
     * @param callerMemberId 当前登录会员,必须等于该报名单的 member_id
     */
    AttributionClaimResult claim(long callerMemberId, AttributionClaimRequest request);

    /**
     * 调用者自己那条已绑定 campaign 的成交聚合(给视频悬赏等按结果结算的调用方读)。
     *
     * <p><b>绑定门槛</b>:必须存在一条服务端签发的凭据,且 sharer=调用者、
     * campaignKey 与 target 完全一致。没有绑定就返回 {@code bound=false}、计数全 0,
     * 不会因为客户端传了同名字符串就把别人的单算进来。
     *
     * <p><b>不泄漏他人身份</b>:只返回计数与合计金额,不返回 orderId / memberId。
     *
     * @param callerMemberId 当前登录会员(只读自己那条 campaign)
     */
    com.chengyinhub.business.domain.dto.CampaignAttributionAggregate aggregateOwnedCampaign(
            long callerMemberId, String campaignKey, Integer targetType, Long targetId);

}
