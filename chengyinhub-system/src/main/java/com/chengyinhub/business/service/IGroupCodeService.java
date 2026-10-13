package com.chengyinhub.business.service;

import com.chengyinhub.common.core.domain.AjaxResult;

/** 带队场次团码的签发与商家接待登记。 */
public interface IGroupCodeService {

    AjaxResult issue(Long activityId, long issuerMemberId);

    AjaxResult redeem(String code, long merchantMemberId);

    AjaxResult redeem(String code, long merchantMemberId, long actorMemberId,
                      String actorRoleCode);
}
