package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;

/**
 * 商家改自己那条报名。
 *
 * 原来报名只能创建和取消 —— 被驳回的商家想补一张现场图,只能取消重报,
 * 记录丢失、重新排队。这个服务把「可编辑窗口」这道闸集中在一处。
 */
public interface IMerchantRegistrationEditService {

    /**
     * @param registrationId 报名 id
     * @param memberId       当前登录商家
     * @param param          只取白名单字段,其余一律忽略
     * @param username       审计用
     * @return 是否落库成功
     */
    boolean updateByMerchant(Long registrationId, Long memberId, RegistrationMerchantDTO param, String username);
}
