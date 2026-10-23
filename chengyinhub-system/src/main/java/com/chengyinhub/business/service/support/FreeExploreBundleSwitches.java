package com.chengyinhub.business.service.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * F3 套票 Phase 1 开关(DOC2 D-11),三个全部默认关闭。
 *
 * <ul>
 *   <li>{@code ledger-enabled}:允许读写 explore_bundle_* 表(迁移落库后才可打开)。关闭时一切套票钩子空转,
 *       既有购票/核销/撤销/退款路径与改动前逐字节同行为。</li>
 *   <li>{@code sale-enabled}:新建 ③ 报名单时冻结快照并发放单位。须 ledger 同开才生效。</li>
 *   <li>{@code reservation-enabled}:玩家预约/取消/商家退出处置、商家记爽约。须 ledger 同开才生效。</li>
 * </ul>
 *
 * <p>关闭顺序:先关 sale,待在途套票单清零后才可关 ledger —— ledger 关闭后核销不再扣单位,
 * 已售套票单的余额会与真实核销脱节。
 */
@Component
public class FreeExploreBundleSwitches {
    private final boolean ledgerEnabled;
    private final boolean saleEnabled;
    private final boolean reservationEnabled;

    public FreeExploreBundleSwitches(
            @Value("${chengyin.free-explore.bundle.ledger-enabled:false}") boolean ledgerEnabled,
            @Value("${chengyin.free-explore.bundle.sale-enabled:false}") boolean saleEnabled,
            @Value("${chengyin.free-explore.bundle.reservation-enabled:false}") boolean reservationEnabled) {
        this.ledgerEnabled = ledgerEnabled;
        this.saleEnabled = saleEnabled;
        this.reservationEnabled = reservationEnabled;
    }

    public boolean ledgerEnabled() { return ledgerEnabled; }

    public boolean saleActive() { return ledgerEnabled && saleEnabled; }

    public boolean reservationActive() { return ledgerEnabled && reservationEnabled; }
}
