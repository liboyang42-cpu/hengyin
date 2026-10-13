package com.chengyinhub.business.domain.enums;

/**
 * settlement_batch.status 值域固化(P1-5,任务书 1.3 节)。
 *
 * <p>建表注释原文是"0待审核 1已通过 2已失败"，但 2026-07-25 核实 {@code SettlementServiceImpl}
 * 全部代码路径(仅 {@code generateBatch}/{@code confirmBatch})从未写入或读取过 status=2——
 * 这是本表当前真实的值域，不是本枚举遗漏。若未来要支持批次拒绝/失败，需要新增写入路径后
 * 再启用 REJECTED，不应该假装它已经存在。</p>
 */
public enum SettlementBatchState {
    /** 已生成批次,尚未 confirmBatch 确认(generateBatch 写入) */
    PENDING(0, "待确认"),
    /** confirmBatch 原子流转确认,明细已同步置为已结算 */
    CONFIRMED(1, "已确认");

    private final int code;
    private final String label;

    SettlementBatchState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    /** 把裸 status 列读回来的 Integer 转成枚举，供读路径按需接入；未知值直接抛出，不静默吞掉脏数据。 */
    public static SettlementBatchState fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SettlementBatchState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知 settlement_batch.status=" + code);
    }
}
