package com.chengyinhub.business.domain.dto;

/**
 * D-93:一个时间窗的核销指标(营销首页 VERIFY 阶段与店铺参谋「核销」同一份数)。
 * current/previous 为事件数;changeRate 为 SafeRatio.change(current, previous, 4),上期为 0 时 null;
 * daily 为本期逐日核销数(Asia/Shanghai 自然日,长度 = windowDays,合计 = current)。
 */
public class MerchantVerifyMetric {
    private final int windowDays;
    private final long current;
    private final long previous;
    private final String changeRate;
    private final long[] daily;

    public MerchantVerifyMetric(int windowDays, long current, long previous, String changeRate, long[] daily) {
        this.windowDays = windowDays;
        this.current = current;
        this.previous = previous;
        this.changeRate = changeRate;
        this.daily = daily == null ? new long[0] : daily.clone();
    }

    public int getWindowDays() { return windowDays; }
    public long getCurrent() { return current; }
    public long getPrevious() { return previous; }
    public String getChangeRate() { return changeRate; }
    public long[] getDaily() { return daily.clone(); }
}
