package com.chengyinhub.business.service;

import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.MinorDecision;
import com.chengyinhub.business.service.minor.OrienteeringParticipation;

import java.time.LocalDateTime;

/**
 * DOC2 F7 未成年模式执行挂点，供结算（checkout）与城市定向（F1a-d）调用。
 *
 * <p>check* 方法总是按规则判定；enforce*IfEnabled 方法先看开关，开关关闭时恒放行 ——
 * 调用方接 enforce* 即可保证「开关缺省关闭、现网行为不变」。
 */
public interface IMinorProtectionService {
    /** 按会员自填出生日期给出年龄段；未填为 UNKNOWN。 */
    AgeBand ageBand(Long memberId);

    /** 是否需要监护人参与（任何非 ADULT，包括 UNKNOWN）。 */
    boolean guardianRequired(Long memberId);

    /** 宵禁检查：[start, end] 是否完整落在同一天的白天窗口内。任一为空视为无法确认 → false。 */
    boolean withinDaytime(LocalDateTime start, LocalDateTime end);

    /** guardian 当前是否对 ward 持有当前政策版本下的有效监护授权（任一用途）。 */
    boolean isGuardianOf(Long guardianMemberId, Long wardMemberId);

    /** 付款人能否自行购买。 */
    MinorDecision checkPurchase(Long payerMemberId);

    /** 未成年人个人信息处理是否已有监护人 INFO_PROCESS 同意（与购买同意相互独立）。 */
    MinorDecision checkInfoProcessing(Long memberId);

    /** 城市定向参与校验：低风险线路 / 白天 / 同队监护人 / 16-17 单独参与授权。 */
    MinorDecision checkOrienteering(Long memberId, OrienteeringParticipation participation);

    /** feature.flag.minorMode + feature.flag.minorOrienteeringGate 都开才校验，否则放行。 */
    MinorDecision enforceOrienteeringIfEnabled(Long memberId, OrienteeringParticipation participation);
}
