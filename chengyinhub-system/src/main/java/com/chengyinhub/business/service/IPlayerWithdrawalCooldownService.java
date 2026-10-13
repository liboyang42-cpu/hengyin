package com.chengyinhub.business.service;

import java.math.BigDecimal;

public interface IPlayerWithdrawalCooldownService {

    /** 配置键：变更只影响新事件，绝不回写既有 cooldown_until。 */
    String CONFIG_KEY = "withdrawal.player-complaint-cooldown-hours";

    /** 在写正向补偿明细的同一事务里登记；失败即整笔回滚，不允许「先补偿、事务外补冷静期」。 */
    void registerFromPositiveComplaintDetail(Long balanceDetailId, Long memberId, Long complaintId,
                                             BigDecimal amount);

    /**
     * 该会员仍未到期的投诉补偿冻结额，≥0。
     *
     * <p>★刻意不 try/catch：查询挂了就让提现整单失败。吞异常返回 0 = 冷静期静默失效、钱当场提走。
     */
    BigDecimal frozenAmount(Long memberId);

    /** 当前生效小时数；配置缺失或非法时 fail-closed 抛错，不默默按 0 小时放行。 */
    int currentCooldownHours();

    /** 专用改写权限：与通用参数页的 {@code system:config:edit} 分开，这不是普通编辑接口。 */
    String REWRITE_PERMISSION = "business:withdrawal:cooldown-config";

    /**
     * 专用改写入口：改冷静期时长，并把<b>前值 / 后值 / 理由 / 操作人</b>落进审计表。
     *
     * <p>审计 2 明写「资金页、成本录入、暂结、终结、审核、付款批次和<b>冷却配置</b>都不是普通编辑接口」。
     * RuoYi 通用参数页只写新值、不留前值，理由那一项它本来就不采集 —— 走那条路，
     * 「谁在什么时候把 48 改成了 1、为什么」永远查不出来。
     *
     * <p>只影响<b>新</b>事件：{@code cooldown_hours} / {@code cooldown_until} 是逐笔快照，
     * 本方法一行既有冷静期都不回写。
     *
     * @return 改写前的小时数
     */
    int rewriteCooldownHours(int hours, String reason, Long operatorId);
}
