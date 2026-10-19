package com.chengyinhub.business.service;

import com.chengyinhub.common.core.domain.AjaxResult;

/** 带队场次团码的签发与商家接待登记。 */
public interface IGroupCodeService {

    AjaxResult issue(Long activityId, long issuerMemberId);

    AjaxResult redeem(String code, long merchantMemberId);

    AjaxResult redeem(String code, long merchantMemberId, long actorMemberId,
                      String actorRoleCode);

    /** 商家回读自己接待过的团(按商家主体口径,不按操作员)。 */
    AjaxResult listReceptions(long merchantMemberId);

    /**
     * 撤销一条误扫/扫错团的接待记录。只允许本商家、且所在主题的合作结算尚未开始;
     * 撤销后该团不再计入本商家的分账基数,并追加一条不可变审计。
     */
    AjaxResult revoke(Long verifyId, long merchantMemberId, long actorMemberId,
                      String actorRoleCode, String reason);
}
