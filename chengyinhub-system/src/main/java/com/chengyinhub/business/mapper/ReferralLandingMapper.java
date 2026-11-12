package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.ReferralLandingGiftRow;
import org.apache.ibatis.annotations.Param;

/**
 * 落地页只读查询（礼品按码摘要）。
 *
 * <p>只有 SELECT：落地页不能领取、不能过期落库、不能写任何表。
 * 领取仍走 {@code GiftEntitlementMapper.claimCas} 那条一次状态迁移。
 */
public interface ReferralLandingMapper {

    ReferralLandingGiftRow selectGiftByTokenHash(@Param("tokenHash") String tokenHash);
}
