package com.chengyinhub.common.constant;

/**
 * 附录A 游玩模式唯一枚举(替换全仓魔法数 1/2)。
 *
 * <p>口径以 OmsTicket.mode 为准(ViewRegistrationMerchant 已注明对齐):
 * <ul>
 *   <li>{@link #LINEAR_ORIENTEERING} 1 = 线性城市定向(节点须按序解锁)</li>
 *   <li>{@link #FREE_COLLECT} 2 = 自由收集(节点全开,无序)</li>
 * </ul>
 * resolveMode 无票/未配置时默认 {@link #LINEAR_ORIENTEERING}。
 */
public final class PlayMode {
    private PlayMode() {}

    /** 1 = 线性城市定向 */
    public static final int LINEAR_ORIENTEERING = 1;

    /** 2 = 自由收集(自由探索) */
    public static final int FREE_COLLECT = 2;

    public static boolean isLinear(Integer mode) {
        return mode != null && mode == LINEAR_ORIENTEERING;
    }
}
