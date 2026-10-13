package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerWithdrawalCooldown;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PlayerWithdrawalCooldownMapper {

    /**
     * 只挂在投诉正向补偿那一条余额明细上。
     * created_at / cooldown_until 都从 ums_member_balance_detail.create_time 派生，Java 不传时间。
     */
    int insertForPositiveComplaintDetail(@Param("balanceDetailId") Long balanceDetailId,
                                         @Param("memberId") Long memberId,
                                         @Param("complaintId") Long complaintId,
                                         @Param("amount") BigDecimal amount,
                                         @Param("policyKey") String policyKey,
                                         @Param("cooldownHours") Integer cooldownHours);

    /**
     * 该会员仍在冷静期内的补偿总额。
     *
     * <p>★不带时间入参：边界在 SQL 里用 CURRENT_TIMESTAMP(3) 比，避免 JVM 与 DB 时钟/时区错位。
     * 用 {@code cooldown_until > now} ⇒ 47:59 仍冻结、48:00 整放行。
     */
    BigDecimal sumUnexpiredFrozenByMemberAtDbNow(@Param("memberId") Long memberId);

    List<PlayerWithdrawalCooldown> selectByMember(@Param("memberId") Long memberId);

    /**
     * 冷静期时长改写的审计行：前值 / 后值 / 理由 / 操作人 / 时刻。
     *
     * <p>★不指望 {@code @Log}：本仓 LogAspect 已加固到不写请求体（{@code setOperParam}/
     * {@code setJsonResult} 全仓 0 命中），{@code sys_oper_log} 只有 title/method/url/操作人/耗时 ——
     * 「从 48 改成了 1、理由是什么」正好一个都留不下。改写时刻由 SQL 取库时钟，Java 不传时间。
     */
    int insertConfigChangeAudit(@Param("beforeHours") Integer beforeHours,
                                @Param("afterHours") Integer afterHours,
                                @Param("reason") String reason,
                                @Param("changedBy") Long changedBy);
}
