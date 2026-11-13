package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Server-side mirror of the app's {@code ExternalGameValidator.validateArchive} (Core/ExternalGameImport.swift):
 * the same limits (10 MiB package, 5 MiB per file, 200 files, 10 domains), id / semver / rating patterns, extension
 * allowlist, forbidden permissions, payment-token scan and the same package SHA-256 construction. Where the app is
 * lenient the server is stricter (ASCII-only domains, unknown manifest keys rejected, byte-level token scan, monetization
 * must be NONE). The client {@code signature} is ignored: the server issues its own (see {@link ExternalGameSigner}).
 */
public final class ExternalGamePackageValidator {
    public static final int MAX_PACKAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_FILES = 200;
    public static final int MAX_DOMAINS = 10;
    /** Same outer bound as the app's {@code prepareImport(archiveData:)}: package limit plus 64 KiB of JSON overhead. */
    public static final int MAX_ARCHIVE_JSON_BYTES = MAX_PACKAGE_BYTES + 65_536;
    public static final int MAX_MANIFEST_JSON_BYTES = 65_536;
    public static final int MAX_AUTHOR = 80, MAX_SUMMARY = 500;

    static final Set<String> ALLOWED_EXTENSIONS = Set.of("html", "htm", "css", "js", "mjs", "json",
            "png", "jpg", "jpeg", "webp", "svg", "gif", "mp3", "wav", "ogg", "woff2", "txt");
    static final Set<String> PERMISSIONS = Set.of("storage", "haptics", "audio");
    static final Set<String> FORBIDDEN_PERMISSIONS = Set.of("payment", "iap", "in-app-purchase", "apple-pay", "wechat-pay",
            "alipay", "camera", "microphone", "location", "geolocation", "contacts", "photos", "bluetooth", "nfc",
            "native-bridge", "eval", "filesystem", "notifications");
    static final List<String> FORBIDDEN_TOKENS = List.of("PaymentRequest", "ApplePaySession", "itms-apps://",
            "weixin://wap/pay", "alipays://", "webkit.messageHandlers.nativePay", "StoreKit");
    static final Set<String> RATINGS = Set.of("4+", "9+", "12+", "17+");
    static final Set<String> MANIFEST_KEYS = Set.of("id", "name", "version", "entry", "permissions", "contentRating",
            "allowedDomains", "sha256", "signature", "author", "summary", "monetization", "monetizationDeclaration",
            "signatureAlgorithm", "signingKeyId");
    static final Set<String> MONEY_KEYS = Set.of("price", "pricing", "products", "iapProducts", "inAppPurchases",
            "currency", "paidContent", "payment");
    public static final String MONETIZATION_NONE = "NONE";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final Pattern LOOSE_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,63}");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ExternalGamePackageValidator() {}

    public record Manifest(String id, String name, String version, String entry, List<String> permissions,
                           String contentRating, List<String> allowedDomains, String sha256, String author, String summary) {}

    /** A package that passed every check; {@code files} is path-sorted. */
    public record Validated(Manifest manifest, TreeMap<String, byte[]> files, long totalBytes) {
        public String sha256() { return manifest.sha256(); }
    }

    // ---- input forms ----

    /** The app's {@code ExternalGamePackageArchive} JSON: {@code {"manifest": {...}, "files": {"path": "<base64>"}}}. */
    public static Validated fromArchiveJson(byte[] archiveJson, String declaredMonetization) {
        if (archiveJson == null || archiveJson.length == 0) throw fail(400, "MALFORMED_ARCHIVE", "游戏包为空", null);
        if (archiveJson.length > MAX_ARCHIVE_JSON_BYTES) {
            throw fail(413, "PACKAGE_OVERSIZED", "游戏包体积超限", Map.of("size", archiveJson.length, "maxAllowed", MAX_ARCHIVE_JSON_BYTES));
        }
        JsonNode root = parse(archiveJson);
        if (!root.isObject() || root.size() != 2 || !root.path("manifest").isObject() || !root.path("files").isObject()) {
            throw fail(400, "MALFORMED_ARCHIVE", "游戏包格式损坏或无法解析", null);
        }
        Map<String, byte[]> files = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.get("files").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) throw fail(400, "MALFORMED_ARCHIVE", "游戏包格式损坏或无法解析", Map.of("path", e.getKey()));
            try {
                files.put(e.getKey(), Base64.getDecoder().decode(e.getValue().asText()));
            } catch (IllegalArgumentException bad) {
                throw fail(400, "MALFORMED_ARCHIVE", "游戏包格式损坏或无法解析", Map.of("path", e.getKey()));
            }
        }
        return validate(root.get("manifest"), files, declaredMonetization);
    }

    /** The single-HTML form: a manifest document plus the bytes of its entry file. */
    public static Validated fromManifestAndEntry(byte[] manifestJson, byte[] entry, String declaredMonetization) {
        if (manifestJson == null || manifestJson.length == 0 || manifestJson.length > MAX_MANIFEST_JSON_BYTES) {
            throw fail(400, "MALFORMED_ARCHIVE", "清单为空或过大", null);
        }
        if (entry == null) throw fail(400, "MISSING_ENTRY_FILE", "缺少入口文件", null);
        JsonNode manifest = parse(manifestJson);
        if (!manifest.isObject()) throw fail(400, "MALFORMED_ARCHIVE", "清单格式无效", null);
        String path = manifest.path("entry").isTextual() ? manifest.get("entry").asText() : "";
        Map<String, byte[]> files = new TreeMap<>();
        files.put(path, entry);
        return validate(manifest, files, declaredMonetization);
    }

    // ---- validation (same order as the app) ----

    public static Validated validate(JsonNode node, Map<String, byte[]> rawFiles, String declaredMonetization) {
        if (node == null || !node.isObject()) throw fail(400, "MALFORMED_ARCHIVE", "清单格式无效", null);
        checkMonetization(node, declaredMonetization);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!MANIFEST_KEYS.contains(key)) throw fail(400, "MANIFEST_UNKNOWN_FIELD", "清单包含不支持的字段", Map.of("field", key));
        }
        String id = requiredText(node, "id", "INVALID_MANIFEST_ID");
        if (!ID.matcher(id).matches()) throw fail(400, "INVALID_MANIFEST_ID", "游戏标识无效", Map.of("value", id));
        String name = requiredText(node, "name", "INVALID_MANIFEST_NAME");
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 80) throw fail(400, "INVALID_MANIFEST_NAME", "游戏名称不能为空且不能超过 80 个字符", null);
        String version = requiredText(node, "version", "INVALID_MANIFEST_VERSION");
        if (!LOOSE_VERSION.matcher(version).matches()) throw fail(400, "INVALID_MANIFEST_VERSION", "版本号须为 x.y.z", Map.of("value", version));
        if (!VERSION.matcher(version).matches()) throw fail(400, "INVALID_MANIFEST_VERSION", "版本号每段须为不超过 9 位且无前导零的数字", Map.of("value", version));
        String rating = requiredText(node, "contentRating", "INVALID_CONTENT_RATING");
        if (!RATINGS.contains(rating)) throw fail(400, "INVALID_CONTENT_RATING", "内容分级仅支持 4+、9+、12+、17+", Map.of("value", rating));

        String entry = requiredText(node, "entry", "PATH_TRAVERSAL_DETECTED");
        if (!isSafeRelativePath(entry)) throw fail(400, "PATH_TRAVERSAL_DETECTED", "检测到非法路径", Map.of("path", entry));
        String entryExt = extension(entry);
        if (!entryExt.equals("html") && !entryExt.equals("htm")) throw fail(400, "DISALLOWED_FILE_EXTENSION", "入口须为 HTML 文件", Map.of("path", entry));

        List<String> permissions = stringList(node, "permissions");
        for (String perm : permissions) {
            String normalized = perm.strip().toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_PERMISSIONS.contains(normalized) || !PERMISSIONS.contains(normalized)) {
                throw fail(400, "FORBIDDEN_PERMISSION", "请求了未授权或禁止的权限", Map.of("permission", perm));
            }
        }
        List<String> domains = stringList(node, "allowedDomains");
        if (domains.size() > MAX_DOMAINS) throw fail(400, "TOO_MANY_ALLOWED_DOMAINS", "域名白名单最多 10 个", Map.of("count", domains.size()));
        for (String domain : domains) {
            if (!isValidDomain(domain)) throw fail(400, "INVALID_ALLOWED_DOMAIN", "包含不安全或非法的域名白名单", Map.of("domain", domain));
        }

        if (rawFiles == null || rawFiles.isEmpty() || rawFiles.size() > MAX_FILES) {
            throw fail(400, "TOO_MANY_FILES", "文件数量须为 1–200 个", Map.of("count", rawFiles == null ? 0 : rawFiles.size(), "maxAllowed", MAX_FILES));
        }
        TreeMap<String, byte[]> files = new TreeMap<>(rawFiles);
        long total = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey();
            byte[] data = e.getValue();
            if (!isSafeRelativePath(path)) throw fail(400, "PATH_TRAVERSAL_DETECTED", "检测到非法路径", Map.of("path", path));
            if (!ALLOWED_EXTENSIONS.contains(extension(path))) throw fail(400, "DISALLOWED_FILE_EXTENSION", "包含不允许的文件类型", Map.of("path", path));
            if (data.length > MAX_FILE_BYTES) {
                throw fail(413, "FILE_OVERSIZED", "单文件体积超限", Map.of("path", path, "size", data.length, "maxAllowed", MAX_FILE_BYTES));
            }
            total += data.length;
            if (total > MAX_PACKAGE_BYTES) throw fail(413, "PACKAGE_OVERSIZED", "游戏包体积超限", Map.of("size", total, "maxAllowed", MAX_PACKAGE_BYTES));
            String ext = extension(path);
            if (ext.equals("html") || ext.equals("htm") || ext.equals("js") || ext.equals("mjs")) {
                String token = paymentToken(data);
                if (token != null) throw fail(400, "PAYMENT_OR_IAP_DETECTED", "外部导入游戏禁止包含支付或内购调用", Map.of("path", path, "token", token));
            }
        }
        if (!files.containsKey(entry)) throw fail(400, "MISSING_ENTRY_FILE", "缺少入口文件", Map.of("entry", entry));

        String declared = node.path("sha256").isTextual() ? node.get("sha256").asText().strip().toLowerCase(java.util.Locale.ROOT) : "";
        if (!HASH.matcher(declared).matches()) throw fail(400, "UNSIGNED_MANIFEST", "清单缺少 SHA-256 摘要", null);
        String actual = packageSha256(files);
        if (!actual.equals(declared)) throw fail(400, "HASH_MISMATCH", "游戏包内容哈希（SHA-256）与清单不一致", Map.of("expected", declared, "actual", actual));

        String author = optionalText(node, "author", MAX_AUTHOR);
        String summary = optionalText(node, "summary", MAX_SUMMARY);
        return new Validated(new Manifest(id, name, version, entry, permissions, rating, domains, declared, author, summary), files, total);
    }

    static void checkMonetization(JsonNode node, String declared) {
        if (declared != null && !declared.isEmpty() && !MONETIZATION_NONE.equals(declared)) {
            throw fail(400, "MONETIZATION_NOT_ALLOWED", "外部导入游戏只能是 NONE（不收费、无内购、无广告分成）", Map.of("monetization", declared));
        }
        for (String key : List.of("monetization", "monetizationDeclaration")) {
            JsonNode v = node.get(key);
            if (v != null && !(v.isTextual() && MONETIZATION_NONE.equals(v.asText()))) {
                throw fail(400, "MONETIZATION_NOT_ALLOWED", "外部导入游戏只能是 NONE（不收费、无内购、无广告分成）", Map.of("field", key));
            }
        }
        for (String key : MONEY_KEYS) {
            if (node.has(key)) throw fail(400, "MONETIZATION_NOT_ALLOWED", "外部导入游戏不能带价格或商品字段", Map.of("field", key));
        }
    }

    // ---- shared helpers (same constructions as the app) ----

    /** {@code ExternalGameValidator.computePackageSHA256}: for each path in sorted order, path NUL size NUL bytes NUL. */
    public static String packageSha256(Map<String, byte[]> files) {
        MessageDigest digest = sha256();
        for (Map.Entry<String, byte[]> e : new TreeMap<>(files).entrySet()) {
            digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(e.getValue().length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(e.getValue());
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** {@code ExternalGameValidator.canonicalSigningPayload}: the bytes the server signs. */
    public static byte[] canonicalSigningPayload(Manifest m) {
        String domains = String.join(",", m.allowedDomains().stream().map(d -> d.toLowerCase(java.util.Locale.ROOT)).toList());
        String canonical = String.join("|", "v1", m.id(), m.name(), m.version(), m.entry(), String.join(",", m.permissions()),
                m.contentRating(), domains, m.sha256().toLowerCase(java.util.Locale.ROOT));
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.getBytes(StandardCharsets.UTF_8).length > 240) return false;
        if (path.startsWith("/") || path.startsWith("\\") || path.startsWith("~")) return false;
        if (path.contains("\\") || path.contains("\0") || path.contains(":") || path.contains("//")) return false;
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.startsWith(".")) return false;
            if (seg.codePoints().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    static String extension(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot <= 0 ? "" : last.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isValidDomain(String raw) {
        if (raw == null) return false;
        String domain = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (domain.isEmpty() || domain.length() > 253) return false;
        if (domain.equals("localhost") || domain.endsWith(".local") || domain.endsWith(".internal") || domain.endsWith(".lan")) return false;
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) return false;
        boolean allNumeric = true;
        for (String label : labels) {
            if (!LABEL.matcher(label).matches() || label.startsWith("-") || label.endsWith("-")) return false;
            if (!label.chars().allMatch(Character::isDigit)) allNumeric = false;
        }
        if (allNumeric) return false;
        String tld = labels[labels.length - 1];
        return tld.length() >= 2 && !tld.chars().allMatch(Character::isDigit);
    }

    static String paymentToken(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (String token : FORBIDDEN_TOKENS) if (text.contains(token)) return token;
        return null;
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (java.io.IOException e) {
            throw fail(400, "MALFORMED_ARCHIVE", "游戏包格式损坏或无法解析", null);
        }
    }

    private static String requiredText(JsonNode node, String key, String code) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) throw fail(400, code, key + " 缺失或不是字符串", Map.of("field", key));
        return v.asText();
    }

    private static String optionalText(JsonNode node, String key, int max) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw fail(400, "MALFORMED_ARCHIVE", key + " 不是字符串", Map.of("field", key));
        String s = v.asText().strip();
        if (s.codePointCount(0, s.length()) > max) throw fail(400, "MALFORMED_ARCHIVE", key + " 过长", Map.of("field", key, "maxAllowed", max));
        return s.isEmpty() ? null : s;
    }

    private static List<String> stringList(JsonNode node, String key) {
        JsonNode v = node.get(key);
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) return out;
        if (!v.isArray() || v.size() > 32) throw fail(400, "MALFORMED_ARCHIVE", key + " 须为字符串数组", Map.of("field", key));
        for (JsonNode item : v) {
            if (!item.isTextual() || item.asText().length() > 253) throw fail(400, "MALFORMED_ARCHIVE", key + " 须为字符串数组", Map.of("field", key));
            out.add(item.asText());
        }
        return out;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static AppGapException fail(int status, String code, String message, Object data) {
        return new AppGapException(status, code, message, data);
    }

    /** Semver order of two validated x.y.z strings. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            if (c != 0) return c;
        }
        return 0;
    }
}
