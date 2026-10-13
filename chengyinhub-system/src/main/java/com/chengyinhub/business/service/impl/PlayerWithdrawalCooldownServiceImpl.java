package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * A3：每笔正向 PLAYER 投诉补偿独立冷静 48 小时。
 *
 * <p>三条纪律：①起点与到期都由 SQL 取<b>库时钟</b>（{@code CURRENT_TIMESTAMP(3)}）——
 * 明细行的 {@code create_time} 是 Java 的 {@code DateUtils.getNowDate()} 写的，与求和时用的
 * 时钟不是同一个，不能用；②边界只在 SQL 里比；③查不出来就阻断提现，
 * 绝不把异常或空结果解释成「没有冻结」。
 *
 * <p>时长改写走 {@link #rewriteCooldownHours}：专用权限 + 前值/后值/理由落审计表，
 * <b>不是</b>通用参数页那种只写新值的普通编辑。
 */
@Service
public class PlayerWithdrawalCooldownServiceImpl implements IPlayerWithdrawalCooldownService {

    private static final int DEFAULT_HOURS = 48;

    private final PlayerWithdrawalCooldownMapper mapper;
    private final ISysConfigService configService;

    public PlayerWithdrawalCooldownServiceImpl(PlayerWithdrawalCooldownMapper mapper,
                                               ISysConfigService configService) {
        this.mapper = mapper;
        this.configService = configService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void registerFromPositiveComplaintDetail(Long balanceDetailId, Long memberId, Long complaintId,
                                                    BigDecimal amount) {
        if (balanceDetailId == null || memberId == null || complaintId == null
                || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("PLAYER_WITHDRAWAL_COOLDOWN_ARGUMENT_REQUIRED");
        }
        int hours = currentCooldownHours();
        // insert...select 会顺带校验这条明细确实是该 member 的 event_type=9 正向投诉补偿；
        // 挂错行 / 挂到恢复流水(event_type=4)上 ⇒ 0 行 ⇒ 整笔回滚。
        if (mapper.insertForPositiveComplaintDetail(balanceDetailId, memberId, complaintId, amount,
                CONFIG_KEY, hours) != 1) {
            throw new IllegalStateException("PLAYER_WITHDRAWAL_COOLDOWN_WRITE_FAILED");
        }
    }

    @Override
    public BigDecimal frozenAmount(Long memberId) {
        if (memberId == null) {
            return BigDecimal.ZERO;
        }
        // 与 MerchantReferralBountyService.frozenAmount 同形：空集返 0，异常照抛。
        BigDecimal frozen = mapper.sumUnexpiredFrozenByMemberAtDbNow(memberId);
        return frozen == null ? BigDecimal.ZERO : frozen;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rewriteCooldownHours(int hours, String reason, Long operatorId) {
        if (hours <= 0 || operatorId == null || reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("PLAYER_WITHDRAWAL_COOLDOWN_REWRITE_ARGUMENT_INVALID");
        }
        int before = currentCooldownHours();
        // ★先落审计再改配置：审计写不进去就整笔回滚，绝不留下一次「查不出是谁改的」变更。
        //   前值/后值/理由/操作人四样缺一，这次改动事后就无从追。sys_oper_log 帮不上忙 ——
        //   本仓 LogAspect 不写请求体。
        if (mapper.insertConfigChangeAudit(before, hours, reason.trim(), operatorId) != 1) {
            throw new IllegalStateException("PLAYER_WITHDRAWAL_COOLDOWN_AUDIT_WRITE_FAILED");
        }
        // ★必须走专用出口：通用 updateConfig 拿 config_id 反查再解引用，
        //   而这里手搓的 SysConfig 没有主键 ⇒ 第一句就 NPE、整笔回滚（含刚写的审计行）；
        //   就算绕过 NPE，UPDATE 的 where config_id = null 同样恒命中 0 行。
        //   updateFundsPolicyConfigByKey 按 key 读回整行、核命中行数、再从库里回读核值，
        //   三条任一不满足即抛 —— 「审计说改了、配置没动」在这条路上表达不出来。
        configService.updateFundsPolicyConfigByKey(CONFIG_KEY, String.valueOf(hours),
                String.valueOf(operatorId));
        // 只影响新事件：cooldown_hours / cooldown_until 是逐笔快照，既有行一行都不回写。
        return before;
    }

    @Override
    public int currentCooldownHours() {
        String configured = configService.selectConfigByKey(CONFIG_KEY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_HOURS;
        }
        int hours;
        try {
            hours = Integer.parseInt(configured.trim());
        } catch (NumberFormatException malformed) {
            // 配置被写坏时不许退回 0 小时（那等于冷静期消失），也不许静默用默认值掩盖问题。
            throw new IllegalStateException("PLAYER_WITHDRAWAL_COOLDOWN_CONFIG_INVALID:" + configured);
        }
        if (hours <= 0) {
            throw new IllegalStateException("PLAYER_WITHDRAWAL_COOLDOWN_CONFIG_INVALID:" + configured);
        }
        return hours;
    }
}
