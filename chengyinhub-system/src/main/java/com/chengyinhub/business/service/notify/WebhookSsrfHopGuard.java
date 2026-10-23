package com.chengyinhub.business.service.notify;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * C09 独占：Webhook 每一跳独立 SSRF 闸。不解析 DNS、不发 HTTP、不跟随真实重定向。
 *
 * <p>调用方必须提供夹具解析地址；主机名缺解析即 fail-closed。数字 IP 只做本地字节解析。
 * 共享 {@code HttpWebhookTransport} 仍归告警通道，本闸不改它。
 */
public final class WebhookSsrfHopGuard {

    public static final int MAX_HOPS = 3;
    public static final int HTTPS_PORT = 443;

    public enum Decision {
        ALLOW,
        REJECT_SCHEME,
        REJECT_USERINFO,
        REJECT_PORT,
        REJECT_UNRESOLVED,
        REJECT_PRIVATE,
        REJECT_HOP_LIMIT
    }

    public WebhookSsrfHopGuard() {
    }

    public HopVerdict inspect(Hop hop) {
        if (hop == null || isBlank(hop.url)) {
            return HopVerdict.reject(null, Decision.REJECT_UNRESOLVED, "缺少跳地址");
        }
        URI uri;
        try {
            uri = new URI(hop.url.trim());
        } catch (URISyntaxException e) {
            return HopVerdict.reject(hop.url, Decision.REJECT_UNRESOLVED, "URL 无法解析");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return HopVerdict.reject(hop.url, Decision.REJECT_SCHEME, "只允许 https");
        }
        if (uri.getUserInfo() != null) {
            return HopVerdict.reject(hop.url, Decision.REJECT_USERINFO, "禁止 userinfo");
        }
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_PORT) {
            return HopVerdict.reject(hop.url, Decision.REJECT_PORT, "只允许 443");
        }
        String host = uri.getHost();
        if (isBlank(host)) {
            return HopVerdict.reject(hop.url, Decision.REJECT_UNRESOLVED, "缺少 host");
        }
        List<InetAddress> addresses = resolveLocally(host, hop.resolved);
        if (addresses.isEmpty()) {
            return HopVerdict.reject(hop.url, Decision.REJECT_UNRESOLVED, "无夹具解析且非字面量 IP");
        }
        for (InetAddress address : addresses) {
            if (!publicUnicast(address)) {
                return HopVerdict.reject(hop.url, Decision.REJECT_PRIVATE,
                        "私网/回环/链路本地/组播");
            }
        }
        return HopVerdict.allow(hop.url, addresses);
    }

    /**
     * 整条重定向链逐跳检查；任一跳失败则整链拒绝。超过 {@link #MAX_HOPS} 拒绝。
     */
    public ChainVerdict inspectChain(List<Hop> hops) {
        if (hops == null || hops.isEmpty()) {
            return ChainVerdict.reject(Decision.REJECT_UNRESOLVED, "缺少跳");
        }
        if (hops.size() > MAX_HOPS) {
            return ChainVerdict.reject(Decision.REJECT_HOP_LIMIT, "超过每跳上限 " + MAX_HOPS);
        }
        List<HopVerdict> inspected = new ArrayList<HopVerdict>(hops.size());
        for (Hop hop : hops) {
            HopVerdict verdict = inspect(hop);
            inspected.add(verdict);
            if (!verdict.allowed()) {
                return ChainVerdict.reject(verdict.decision, verdict.reason, inspected);
            }
        }
        return ChainVerdict.allow(inspected);
    }

    public void assertSafeChain(List<Hop> hops) {
        ChainVerdict verdict = inspectChain(hops);
        if (!verdict.allowed()) {
            throw new SecurityException("Webhook SSRF 拒绝: " + verdict.reason);
        }
    }

    /**
     * 对齐 iOS {@code recoverOriginal}:解析 requestId 后只检查链,不占账、不跟真实跳。
     * 缺 requestId 仍检查链,不抛。
     */
    public ChainVerdict recoverOriginal(String originalRequestId, List<Hop> hops) {
        ImmutableReceiptProducer.parseRequestId(originalRequestId);
        return inspectChain(hops);
    }

    static List<InetAddress> resolveLocally(String host, List<InetAddress> provided) {
        List<InetAddress> out = new ArrayList<InetAddress>();
        InetAddress literal = parseIpv4Literal(host);
        if (literal != null) {
            out.add(literal);
            return out;
        }
        if (provided == null) {
            return out;
        }
        for (InetAddress address : provided) {
            if (address != null) {
                out.add(address);
            }
        }
        return out;
    }

    static InetAddress parseIpv4Literal(String host) {
        if (host == null || !host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            return null;
        }
        String[] parts = host.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int n;
            try {
                n = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > 255) {
                return null;
            }
            bytes[i] = (byte) n;
        }
        try {
            return InetAddress.getByAddress(host, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 与既有媒体闸同一口径：公网单播才放行。不调用 DNS。 */
    static boolean publicUnicast(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 254) == 252) {
            return false;
        }
        if (bytes.length == 4 && ((bytes[0] & 255) == 0
                || ((bytes[0] & 255) == 100 && (bytes[1] & 192) == 64))) {
            return false;
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 一跳：目标 URL + 夹具解析地址。resolved 对字面量 IPv4 可空。 */
    public static final class Hop {
        public final String url;
        public final List<InetAddress> resolved;

        public Hop(String url, List<InetAddress> resolved) {
            this.url = url;
            this.resolved = resolved == null
                    ? Collections.<InetAddress>emptyList()
                    : Collections.unmodifiableList(new ArrayList<InetAddress>(resolved));
        }

        public static Hop of(String url, InetAddress... addresses) {
            List<InetAddress> list = new ArrayList<InetAddress>();
            if (addresses != null) {
                for (InetAddress address : addresses) {
                    list.add(address);
                }
            }
            return new Hop(url, list);
        }
    }

    public static final class HopVerdict {
        public final String url;
        public final Decision decision;
        public final String reason;
        public final List<InetAddress> addresses;

        private HopVerdict(String url, Decision decision, String reason, List<InetAddress> addresses) {
            this.url = url;
            this.decision = decision;
            this.reason = reason;
            this.addresses = addresses == null
                    ? Collections.<InetAddress>emptyList()
                    : Collections.unmodifiableList(new ArrayList<InetAddress>(addresses));
        }

        static HopVerdict allow(String url, List<InetAddress> addresses) {
            return new HopVerdict(url, Decision.ALLOW, "allow", addresses);
        }

        static HopVerdict reject(String url, Decision decision, String reason) {
            return new HopVerdict(url, decision, reason, Collections.<InetAddress>emptyList());
        }

        public boolean allowed() {
            return decision == Decision.ALLOW;
        }
    }

    public static final class ChainVerdict {
        public final boolean allowed;
        public final Decision decision;
        public final String reason;
        public final List<HopVerdict> hops;

        private ChainVerdict(boolean allowed, Decision decision, String reason, List<HopVerdict> hops) {
            this.allowed = allowed;
            this.decision = decision;
            this.reason = reason;
            this.hops = hops == null
                    ? Collections.<HopVerdict>emptyList()
                    : Collections.unmodifiableList(new ArrayList<HopVerdict>(hops));
        }

        static ChainVerdict allow(List<HopVerdict> hops) {
            return new ChainVerdict(true, Decision.ALLOW, "allow", hops);
        }

        static ChainVerdict reject(Decision decision, String reason) {
            return new ChainVerdict(false, decision, reason, Collections.<HopVerdict>emptyList());
        }

        static ChainVerdict reject(Decision decision, String reason, List<HopVerdict> hops) {
            return new ChainVerdict(false, decision, reason, hops);
        }

        public boolean allowed() {
            return allowed;
        }
    }

    @Override
    public String toString() {
        return Objects.toString(getClass().getSimpleName());
    }
}
