package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponRuleVersion;
import com.chengyinhub.business.domain.dto.CouponRuleChangeDTO;

/**
 * F0572 平台优惠券模板治理:白名单改规则 + 规则版本、停发、平台失效。
 *
 * <p>冲突/非法输入抛 {@link com.chengyinhub.common.exception.ServiceException},
 * code=409 为版本冲突,code=400 为非法输入。
 */
public interface ICouponRuleGovernanceService
{
    /** 带 rule_version 的券详情;不存在返回 null */
    SmsCoupon getWithRuleVersion(Long couponId);

    /** 只读预览:字段差异、校验结论、版本冲突、对新领取与已领取券的影响 */
    Map<String, Object> previewChange(CouponRuleChangeDTO change);

    /** 白名单规则变更;同事务写规则版本 */
    SmsCouponRuleVersion applyChange(CouponRuleChangeDTO change, Long operatorId);

    /** 停发:只拦新领取,已领取券不变 */
    SmsCouponRuleVersion stopIssue(Long couponId, Integer expectedVersion, String reason, Long operatorId);

    /** 平台失效:必须有独立原因;返回版本行 + 受影响持券人 */
    Map<String, Object> invalidate(Long couponId, Integer expectedVersion, String reason, Long operatorId);

    List<SmsCouponRuleVersion> listVersions(Long couponId);

    /** 当前受影响(未使用/未过期)持券人 + 最近一次平台失效记录 */
    Map<String, Object> affectedHolders(Long couponId);
}
