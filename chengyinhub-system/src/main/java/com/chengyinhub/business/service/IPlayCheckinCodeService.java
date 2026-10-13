package com.chengyinhub.business.service;

/**
 * 游戏打卡一次性码：商家手机出示，玩家扫一次即作废。
 *
 * <p>与海报静态 {@code checkin_code}、玩家个人小程序码都不是同一套。
 */
public interface IPlayCheckinCodeService {

    Issued issue(Long merchantMemberId, Long nodeId);

    /** 只读验码及定位节点，业务资格失败时不消耗码。 */
    ConsumeResult inspect(String code);

    ConsumeResult consume(String code);

    class Issued {
        public final Long nodeId;
        public final String nodeName;
        public final long ttlMs;
        public final String qrcodeUrl;
        public final String code;

        public Issued(Long nodeId, String nodeName, long ttlMs, String qrcodeUrl, String code) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.ttlMs = ttlMs;
            this.qrcodeUrl = qrcodeUrl;
            this.code = code;
        }
    }

    class ConsumeResult {
        public enum Kind { OK, USED, EXPIRED, INVALID }

        public final Kind kind;
        public final String message;
        public final Long nodeId;

        public ConsumeResult(Kind kind, String message, Long nodeId) {
            this.kind = kind;
            this.message = message;
            this.nodeId = nodeId;
        }

        public static ConsumeResult ok(Long nodeId) {
            return new ConsumeResult(Kind.OK, null, nodeId);
        }

        public static ConsumeResult used() {
            return new ConsumeResult(Kind.USED, "这张码已用过", null);
        }

        public static ConsumeResult expired() {
            return new ConsumeResult(Kind.EXPIRED, "这张码已过期", null);
        }

        public static ConsumeResult invalid() {
            return new ConsumeResult(Kind.INVALID, "无效的打卡码", null);
        }
    }
}
