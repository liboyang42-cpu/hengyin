package com.chengyinhub.business.domain.enums;

/**
 * settlement_detail.status 值域固化(P1-5,任务书 1.3 节)。
 *
 * <p>建表注释原文是"0待处理 1成功 2失败"，与 settlement_batch 注释("0待审核 1已通过 2已失败")
 * 是两套不一致的取值描述——本枚举按 2026-07-25 核实的真实代码语义定稿：
 * 2 并不是"失败"，而是 {@code voidPendingBySource} 在退款时把原本待结算(status=0)的明细
 * 标记作废（fail_reason='退款作废'），源头业务从未成功过，不是"处理后失败"。</p>
 */
public enum SettlementDetailState {
    /** 已入账待处理,batch_id=0,等待 generateBatch 归批或系统1直接同步结算 */
    PENDING(0, "待处理"),
    /** 已结算:随批次 confirmBatch 确认，或系统1真实打款后 markActivityRegistrationDetailsSettled 同步 */
    SETTLED(1, "已结算"),
    /** 作废:退款导致原本待结算的明细被撤销(voidPendingBySource)，不是"结算失败" */
    VOIDED(2, "作废(退款撤销)");

    private final int code;
    private final String label;

    SettlementDetailState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    /** 把裸 status 列读回来的 Integer 转成枚举，供读路径按需接入；未知值直接抛出，不静默吞掉脏数据。 */
    public static SettlementDetailState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SettlementDetailState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知 settlement_detail.status=" + code);
    }
}
