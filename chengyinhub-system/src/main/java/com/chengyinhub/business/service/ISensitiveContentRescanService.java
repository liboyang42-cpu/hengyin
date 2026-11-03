package com.chengyinhub.business.service;

/** Round5D:词库变化后复查公开文本。默认只报告;开关 content.sensitive.rescan.remove=true 时自动下架。 */
public interface ISensitiveContentRescanService {

    /** 一轮结果:读了几条、命中几条、下架几条、是否已扫完本版本。 */
    final class Result {
        public final int scanned;
        public final int hits;
        public final int removed;
        public final boolean finished;

        public Result(int scanned, int hits, int removed, boolean finished) {
            this.scanned = scanned;
            this.hits = hits;
            this.removed = removed;
            this.finished = finished;
        }
    }

    Result runOnce();
}
