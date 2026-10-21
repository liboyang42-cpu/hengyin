package com.chengyinhub.business.citygame.operations;

import java.util.Map;

/**
 * 规则、地点、证据、任务、骰子、报价、账本、归属和外部时间线的只读窄端口。
 * 没有写方法。生产实现一律返回缺失，不能用样例补履约。
 */
public interface CityGameOperationsFactPort {

    Slot read(String slot, String actionId, String regionId);

    final class Slot {
        public final boolean missing;
        public final String reason;
        public final Map<String, Object> value;

        private Slot(boolean missing, String reason, Map<String, Object> value) {
            this.missing = missing;
            this.reason = reason;
            this.value = value;
        }

        public static Slot missing(String reason) {
            return new Slot(true, reason == null ? "MISSING" : reason, null);
        }

        public static Slot present(Map<String, Object> value) {
            return new Slot(false, null, value);
        }
    }
}
