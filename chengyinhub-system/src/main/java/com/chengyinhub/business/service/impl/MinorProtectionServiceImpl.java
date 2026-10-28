package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MinorGuardianAuthorizationMapper;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.GuardianPurpose;
import com.chengyinhub.business.service.minor.MinorDecision;
import com.chengyinhub.business.service.minor.MinorPolicy;
import com.chengyinhub.business.service.minor.OrienteeringParticipation;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.Objects;

/**
 * DOC2 F7 未成年模式规则（业主定稿）：
 * <ul>
 *   <li>未满 8 岁：不能自行支付 —— 由监护人购买并陪同（同队）；</li>
 *   <li>8-15 岁：支付须监护人 PURCHASE 授权；城市定向仅白天低风险线路且监护人同队；</li>
 *   <li>16-17 岁：支付须 PURCHASE 授权；白天低风险线路可在 SOLO_DAYTIME_PLAY 授权后单独参与，否则须监护人同队；</li>
 *   <li>年龄未知：一律不按成年人放行。</li>
 * </ul>
 * 授权只认当前 {@link MinorPolicy#POLICY_VERSION} 且在有效期内的 ACTIVE 记录。
 */
@Service
public class MinorProtectionServiceImpl implements IMinorProtectionService {

    private final IUmsMemberService memberService;
    private final MinorGuardianAuthorizationMapper authorizationMapper;
    private final ComplianceFeatureSwitches switches;
    private Clock clock = Clock.system(MinorPolicy.ZONE);

    public MinorProtectionServiceImpl(IUmsMemberService memberService,
                                      MinorGuardianAuthorizationMapper authorizationMapper,
                                      ComplianceFeatureSwitches switches) {
        this.memberService = memberService;
        this.authorizationMapper = authorizationMapper;
        this.switches = switches;
    }

    /** 测试注入固定时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AgeBand ageBand(Long memberId) {
        if (memberId == null || memberId <= 0) return AgeBand.UNKNOWN;
        UmsMember member = memberService.selectUmsMemberById(memberId);
        LocalDate birthday = member == null ? null : MinorPolicy.toLocalDate(member.getBirthday());
        return AgeBand.of(birthday, LocalDate.now(clock));
    }

    @Override
    public boolean guardianRequired(Long memberId) {
        return ageBand(memberId) != AgeBand.ADULT;
    }

    @Override
    public boolean withinDaytime(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) return false;
        if (!start.toLocalDate().equals(end.toLocalDate())) return false;
        LocalTime from = start.toLocalTime();
        LocalTime to = end.toLocalTime();
        return !from.isBefore(MinorPolicy.DAYTIME_START) && !to.isAfter(MinorPolicy.DAYTIME_END);
    }

    @Override
    public boolean isGuardianOf(Long guardianMemberId, Long wardMemberId) {
        if (guardianMemberId == null || wardMemberId == null || guardianMemberId.equals(wardMemberId)) return false;
        return authorizationMapper.countActiveBetween(guardianMemberId, wardMemberId,
                MinorPolicy.POLICY_VERSION, now()) > 0;
    }

    @Override
    public MinorDecision checkPurchase(Long payerMemberId) {
        AgeBand band = ageBand(payerMemberId);
        switch (band) {
            case ADULT:
                return MinorDecision.allow(band);
            case UNKNOWN:
                return unknownAge(band);
            case UNDER_8:
                return MinorDecision.deny(band, MinorDecision.UNDER_8_GUARDIAN_PURCHASE,
                        "未满 8 周岁不能自行购买，请由监护人购买并陪同参与");
            default:
                return hasActive(payerMemberId, GuardianPurpose.PURCHASE)
                        ? MinorDecision.allow(band)
                        : MinorDecision.deny(band, MinorDecision.PURCHASE_CONSENT_REQUIRED,
                                "未成年人购买需监护人授权");
        }
    }

    @Override
    public MinorDecision checkInfoProcessing(Long memberId) {
        AgeBand band = ageBand(memberId);
        if (band == AgeBand.ADULT) return MinorDecision.allow(band);
        if (band == AgeBand.UNKNOWN) return unknownAge(band);
        return hasActive(memberId, GuardianPurpose.INFO_PROCESS)
                ? MinorDecision.allow(band)
                : MinorDecision.deny(band, MinorDecision.INFO_PROCESS_CONSENT_REQUIRED,
                        "处理未成年人个人信息需监护人同意");
    }

    @Override
    public MinorDecision checkOrienteering(Long memberId, OrienteeringParticipation participation) {
        AgeBand band = ageBand(memberId);
        if (band == AgeBand.ADULT) return MinorDecision.allow(band);
        if (band == AgeBand.UNKNOWN) return unknownAge(band);
        if (participation == null || !participation.lowRiskRoute()) {
            return MinorDecision.deny(band, MinorDecision.ROUTE_NOT_LOW_RISK, "未成年人仅可参与低风险线路");
        }
        if (!withinDaytime(participation.start(), participation.plannedEnd())) {
            return MinorDecision.deny(band, MinorDecision.CURFEW, "未成年人仅可在白天时段参与");
        }
        if (guardianInTeam(memberId, participation)) return MinorDecision.allow(band);
        if (band == AgeBand.AGE_16_17) {
            return hasActive(memberId, GuardianPurpose.SOLO_DAYTIME_PLAY)
                    ? MinorDecision.allow(band)
                    : MinorDecision.deny(band, MinorDecision.SOLO_AUTHORIZATION_REQUIRED,
                            "单独参与需监护人授权，或与监护人同队");
        }
        return MinorDecision.deny(band, MinorDecision.GUARDIAN_IN_TEAM_REQUIRED, "需与监护人同队参与");
    }

    @Override
    public MinorDecision enforceOrienteeringIfEnabled(Long memberId, OrienteeringParticipation participation) {
        if (!switches.minorOrienteeringGateOn()) return MinorDecision.allow(null);
        return checkOrienteering(memberId, participation);
    }

    private boolean guardianInTeam(Long wardMemberId, OrienteeringParticipation participation) {
        if (participation.teamMemberIds() == null) return false;
        for (Long teammate : participation.teamMemberIds()) {
            if (teammate == null || Objects.equals(teammate, wardMemberId)) continue;
            if (isGuardianOf(teammate, wardMemberId) && ageBand(teammate) == AgeBand.ADULT) return true;
        }
        return false;
    }

    private boolean hasActive(Long wardMemberId, GuardianPurpose purpose) {
        return authorizationMapper.countActive(wardMemberId, purpose.name(), MinorPolicy.POLICY_VERSION, now()) > 0;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    private static MinorDecision unknownAge(AgeBand band) {
        return MinorDecision.deny(band, MinorDecision.AGE_UNKNOWN, "请先在个人资料中填写出生日期");
    }
}
