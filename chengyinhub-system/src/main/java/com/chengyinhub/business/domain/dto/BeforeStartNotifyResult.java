package com.chengyinhub.business.domain.dto;

/**
 * 出发前提醒单轮批扫的结果。
 *
 * <p>为什么不是一个 int:原先只返回送达数,于是「0」同时表示三件完全不同的事 ——
 * 没人需要发、全都已在去重键里、全都发送失败。任务日志因此只能说「已下发 0 条」,
 * 而 sys_job_log 照样记成功;审查D §8 P1-1 的「失败零告警」正死在这个歧义上。
 * 把扫描/跳过/送达/失败拆开,任务层才能既留痕(0 条也打汇总)又对失败告警。
 */
public class BeforeStartNotifyResult {

    /** 本轮扫到的「票×人」候选数(含命中去重键而跳过的)。 */
    private final int scanned;

    /** 原持久回执已受理或命中迁移前成功缓存，本轮跳过的数。 */
    private final int skipped;

    /** 本轮首次持久确认微信受理的数量；不含原回执重放(不是扫到的票种数)。 */
    private final int delivered;

    /** 未明确送达的数量(UNKNOWN/PENDING/未就绪/依赖故障)。失败数不授予重新发送权限。 */
    private final int failed;

    public BeforeStartNotifyResult(int scanned, int skipped, int delivered, int failed) {
        this.scanned = scanned;
        this.skipped = skipped;
        this.delivered = delivered;
        this.failed = failed;
    }

    public int getScanned() {
        return scanned;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getDelivered() {
        return delivered;
    }

    public int getFailed() {
        return failed;
    }
    /** One durable identity result. An old acceptance is skipped, never counted as a new delivery. */
    public record Delivery(Long intentId, String state, boolean deliveredNow) {
        public Delivery {
            if("ACCEPTED".equals(state) && (intentId==null || intentId<=0))
                throw new IllegalArgumentException("送达必须有原持久回执");
            if(deliveredNow && !"ACCEPTED".equals(state))
                throw new IllegalArgumentException("未受理的通知不能计作送达");
        }
        public boolean alreadyDelivered() {
            return "ACCEPTED".equals(state) && !deliveredNow;
        }
    }
}
