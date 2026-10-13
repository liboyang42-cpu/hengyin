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

    /** 命中「已送达」去重键、本轮跳过的数。 */
    private final int skipped;

    /** 微信 errcode==0 的真送达数(不是扫到的票种数)。 */
    private final int delivered;

    /** 进入发送但失败的数量(含契约违约抛出的)。失败不写去重键,下一轮会重试。 */
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
}
