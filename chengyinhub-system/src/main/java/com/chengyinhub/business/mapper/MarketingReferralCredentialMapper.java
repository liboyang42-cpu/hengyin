package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingReferralCredential;
import org.apache.ibatis.annotations.Param;

/** 推广来源凭据。只有 insert 与按 publicId 读;不提供 update(凭据内容不可改)。 */
public interface MarketingReferralCredentialMapper {

    int insertCredential(MarketingReferralCredential credential);

    MarketingReferralCredential selectByPublicId(@Param("publicId") String publicId);
}
