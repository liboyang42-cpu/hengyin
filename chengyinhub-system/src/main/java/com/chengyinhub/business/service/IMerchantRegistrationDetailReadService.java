package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ViewRegistrationMerchant;

/**
 * 商家报名详情只读投影。
 */
public interface IMerchantRegistrationDetailReadService {

    /**
     * 查询并富化当前商家自己的报名详情。
     *
     * @param memberId 当前商家
     * @param registrationId 报名记录 ID
     * @return 已富化的报名详情
     */
    ViewRegistrationMerchant getRegistrationDetail(Long memberId, Long registrationId);
}
