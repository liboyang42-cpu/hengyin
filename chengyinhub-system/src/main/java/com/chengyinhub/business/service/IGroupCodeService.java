package com.chengyinhub.business.service;

import com.chengyinhub.common.core.domain.AjaxResult;

/** 带队场次团码的签发与商家接待登记。 */
public interface IGroupCodeService {

    AjaxResult issue(Long activityId, long issuerMemberId);

    /** 与 issue 同一套场次与权限判定，但不签码：App {@code /api/club/group-code} 先问「这一场我能不能出码」。 */
    AjaxResult status(Long activityId, long memberId);

    AjaxResult redeem(String code, long merchantMemberId);

    AjaxResult redeem(String code, long merchantMemberId, long actorMemberId,
                      String actorRoleCode);
}
