package com.chengyinhub.business.service.impl;

/**
 * GAME-19:时段限定站点的拒绝,说清是「还没开」还是「今天已关」,以及什么时候再来。
 *
 * <p>仍是 {@link IllegalStateException}:既有 catch(高级玩法 action 400、start 的统一 error)行为不变,
 * 控制器额外把 {@link #reasonCode()} 与开/关时刻放进回包,App 按码出中英文案,不解析中文 msg。
 *
 * <ul>
 *   <li>{@code TIME_WINDOW_NOT_YET}:下一次开放还在今天(普通时段开放前,或跨夜时段白天)。</li>
 *   <li>{@code TIME_WINDOW_CLOSED}:今天的时段已结束,下一次开放在明天(普通时段关闭后)。</li>
 * </ul>
 */
public final class TimeWindowRefusal extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public static final String NOT_YET = "TIME_WINDOW_NOT_YET";
    public static final String CLOSED = "TIME_WINDOW_CLOSED";

    private final String reasonCode;
    private final String opensAt;
    private final String closedAt;

    private TimeWindowRefusal(String reasonCode, String opensAt, String closedAt, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.opensAt = opensAt;
        this.closedAt = closedAt;
    }

    /**
     * 判定一次时段:开放时返回 null,否则返回对应的拒绝。
     *
     * @param from      开放起点(当天第几分钟)
     * @param to        结束点(当天第几分钟,不含);{@code from > to} 为跨夜时段
     * @param nowMinute 业务时区当前的当天第几分钟
     */
    public static TimeWindowRefusal judge(int from, int to, int nowMinute, String openFromText, String openToText) {
        boolean overnight = from > to;
        boolean open = overnight ? (nowMinute >= from || nowMinute < to) : (nowMinute >= from && nowMinute < to);
        if (open) return null;
        // 普通时段:开放前 → 今天稍后开放;关闭后 → 明天再开。跨夜时段在窗外时,下一次开放总在今天的 openFrom。
        if (!overnight && nowMinute >= to) {
            return new TimeWindowRefusal(CLOSED, openFromText, openToText,
                    "这一站今天 " + openToText + " 已关闭，明天 " + openFromText + " 再来");
        }
        return new TimeWindowRefusal(NOT_YET, openFromText, openToText,
                "这一站 " + openFromText + " 开放，到时再来");
    }

    public String reasonCode() {
        return reasonCode;
    }

    /** 下一次开放的时刻 "HH:mm"。 */
    public String opensAt() {
        return opensAt;
    }

    /** 时段结束的时刻 "HH:mm"。 */
    public String closedAt() {
        return closedAt;
    }
}
