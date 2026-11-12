package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.GiftLandingView;
import com.chengyinhub.business.domain.vo.ReferralLandingView;

/**
 * 推广凭据 / 礼品领取码的公开落地页只读解析。
 *
 * <p><b>不写任何表</b>：查看链接既不能领取、不能下单、不能发权益，也不改礼品状态
 * （过期只按状态事实回报，不落库）。所有写事实仍由原有的归因领取、注册下单、
 * 礼品领取接口完成，并且需要真实登录身份。
 */
public interface IReferralLandingService {

    /** 解析推广凭据：只给分享者公开最小资料 + 目标公开摘要 + 稳定状态。 */
    ReferralLandingView describeReferral(String token);

    /** 解析礼品领取码：只给礼品商品公开摘要 + 领取状态，不透露买家或受益人。 */
    GiftLandingView describeGift(String token);
}
