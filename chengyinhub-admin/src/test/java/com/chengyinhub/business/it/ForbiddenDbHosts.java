package com.chengyinhub.business.it;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 测试拒连生产主机的黑名单:不在源码里写生产地址明文(本仓公开)。
 *
 * <p>两路任一命中即拒:
 * <ul>
 *   <li>环境变量 {@code CHENGYIN_FORBIDDEN_DB_HOSTS}(逗号分隔的主机名/IP);</li>
 *   <li>内置 SHA-256 黑名单:生产库主机与生产应用机地址的哈希,与配置主机的哈希比对。</li>
 * </ul>
 * 内置名单只能加不能删 —— 删掉一项就是对那台生产机失明。
 */
public final class ForbiddenDbHosts {
    public static final String ENV = "CHENGYIN_FORBIDDEN_DB_HOSTS";
    public static final Set<String> BUILT_IN_SHA256 = Set.of(
            "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9", // 生产库主机
            "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe"  // 生产应用机
    );

    private ForbiddenDbHosts() { }

    public static boolean isForbidden(String host, Map<String, String> env) {
        return isForbidden(host, env.get(ENV), BUILT_IN_SHA256);
    }

    public static boolean isForbidden(String host, String envDenylist, Set<String> sha256Denylist) {
        String normalized = normalize(host);
        if (normalized.isEmpty()) return false;
        if (envDenylist != null) {
            for (String entry : envDenylist.split(",")) {
                if (normalized.equals(normalize(entry))) return true;
            }
        }
        return sha256Denylist.contains(sha256Hex(normalized));
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalize(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value;
    }
}
