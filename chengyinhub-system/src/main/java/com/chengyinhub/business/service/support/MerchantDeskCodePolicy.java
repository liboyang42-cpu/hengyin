package com.chengyinhub.business.service.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doc2 F4 核销台:把「扫到 / 手输 / OCR 识别」的一串文字归类成玩家核销凭证,并给出服务端驱动的提交表单。
 *
 * <p><b>只归类、不核销、不验签</b>:真伪与归属仍由各既有核销端点在服务端校验(HMAC / 支付态 / 归属),
 * 这里的结果只决定「该去哪个端点、要填哪些字段」。路由表与小程序 {@code utils/verification-scan.js}
 * 逐项一致(pages/merchant/index 的扫码契约),新增的只有两件事:
 * <ul>
 *   <li>手输 / OCR 的旧静态码 {@code VER…}:端上没有 type,表单要求商家显式选「活动 / 主题」,
 *       服务端绝不替它查单子补 type —— scan_qr_code 的 type 闸就是核对「声称的 type」与真实 ownerType,
 *       替它填 = 把闸判成恒真。</li>
 *   <li>玩家节点任务码(据点张贴码 {@code poi1.*}):那是商家贴在店里给玩家扫的打卡码,不是玩家的核销凭证,
 *       明确拒绝并返回 {@link #NODE_TASK_CODE},永不进入任何核销端点。</li>
 * </ul>
 * 核销永远在线:本类只给出在线端点,不产出任何可离线落账的凭据。
 */
public final class MerchantDeskCodePolicy {

    public static final String MODE_SCAN = "SCAN";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_OCR = "OCR";

    public static final String KIND_GROUP = "GROUP_CODE";
    public static final String KIND_DYNAMIC_TICKET = "DYNAMIC_TICKET";
    public static final String KIND_COUPON = "COUPON";
    public static final String KIND_LEGACY_TICKET = "LEGACY_TICKET";
    public static final String KIND_CITY_NODE = "CITY_NODE_VOUCHER";
    public static final String KIND_NODE_TASK = "NODE_TASK_CODE";
    public static final String KIND_INVALID = "INVALID";

    /** 玩家节点任务码 / 据点张贴码被拿来核销 */
    public static final String NODE_TASK_CODE = "F4_NODE_TASK_CODE_NOT_VOUCHER";
    /** 码是好的,但核销入口在另一个页面(据点核销) */
    public static final String WRONG_DESK = "F4_WRONG_DESK";
    public static final String UNSUPPORTED = "F4_CODE_UNSUPPORTED";
    public static final String INVALID = "F4_CODE_INVALID";

    /** OCR 一次最多返回的候选数 */
    public static final int MAX_OCR_CANDIDATES = 5;
    /** OCR 文本长度上限(字符) */
    public static final int MAX_OCR_TEXT = 2000;
    static final int MAX_CODE_LENGTH = 512;

    private static final Pattern LEGACY = Pattern.compile("VER\\d{13}[A-Z0-9]{6}");
    private static final Pattern OCR_DYNAMIC = Pattern.compile("v1\\.\\d{1,19}\\.[A-Za-z0-9_]{1,40}\\.\\d{1,19}\\.-?\\d{1,20}\\.[A-Za-z0-9_-]{16,}");
    private static final Pattern OCR_COUPON = Pattern.compile("cq1\\.\\d{1,19}\\.\\d{1,12}\\.[A-Za-z0-9_-]{16,}");
    private static final Pattern OCR_POSTER = Pattern.compile("poi1\\.\\d{1,19}\\.[A-Za-z0-9_-]{8,}");
    /** OCR 常把 VER 码里的数字认成字母:只在「VER + 19 位」这个形状里做 O→0 / I,L→1 的纠正 */
    private static final Pattern OCR_LEGACY_LOOSE = Pattern.compile("(?i)V\\s?E\\s?R[0-9OIL]{13}[A-Z0-9]{6}");

    private MerchantDeskCodePolicy() {
    }

    /** 表单字段。widget:hidden(服务端已定值)/ select(必选,options 给候选)。 */
    public record Field(String name, String label, String widget, boolean required, String value,
                        List<Map<String, String>> options) {
    }

    /**
     * 归类结果。accepted=false 时 errorCode/message 说明原因,submitEndpoint 为空;
     * redirectPath 非空表示码是好的但要去另一个页面核销。
     */
    public record Resolution(String kind, boolean accepted, String errorCode, String message,
                             String submitEndpoint, List<Field> fields, String redirectPath) {
        public Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("kind", kind);
            view.put("accepted", accepted);
            view.put("errorCode", errorCode);
            view.put("message", message);
            view.put("redirectPath", redirectPath);
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("submitEndpoint", submitEndpoint);
            form.put("method", submitEndpoint == null ? null : "POST");
            form.put("offline", false);
            List<Map<String, Object>> fieldViews = new ArrayList<>();
            for (Field field : fields) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", field.name());
                f.put("label", field.label());
                f.put("widget", field.widget());
                f.put("required", field.required());
                f.put("value", field.value());
                f.put("options", field.options());
                fieldViews.add(f);
            }
            form.put("fields", fieldViews);
            view.put("form", form);
            return view;
        }
    }

    public static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        return MODE_MANUAL.equals(m) || MODE_OCR.equals(m) ? m : MODE_SCAN;
    }

    public static Resolution classify(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH) return invalid(INVALID, "未识别到核销码");

        if (code.startsWith("poi1.")) {
            return reject(KIND_NODE_TASK, NODE_TASK_CODE,
                    "这是据点张贴码(玩家打卡用的节点任务码),不是玩家的核销凭证,请扫玩家出示的核销码", null);
        }
        if (code.startsWith("v1.")) {
            String[] parts = code.split("\\.");
            String type = parts.length >= 3 ? parts[2] : "";
            if (type.startsWith("group_")) return route(KIND_GROUP, "/api/verify/groupcode/redeem", hidden("code", code));
            if ("activity".equals(type) || "topic".equals(type)) {
                return route(KIND_DYNAMIC_TICKET, "/api/registration/scan_dynamic_code", hidden("code", code));
            }
            if (type.startsWith("citynode_")) {
                return reject(KIND_CITY_NODE, WRONG_DESK, "这是据点核销码，请从「城市据点」页扫码核销",
                        "/pages/merchant/citynode/index");
            }
            return invalid(UNSUPPORTED, "暂不支持此动态码");
        }
        if (code.startsWith("cq1.") && code.split("\\.", -1).length == 4) {
            return route(KIND_COUPON, "/api/coupon/verification", hidden("code", code), hidden("scope", "MERCHANT"));
        }
        if (code.startsWith("{")) return classifyJson(code);
        if (LEGACY.matcher(code).matches()) {
            List<Map<String, String>> options = new ArrayList<>();
            options.add(option("activity", "活动票"));
            options.add(option("topic", "主题票"));
            return route(KIND_LEGACY_TICKET, "/api/registration/scan_qr_code", hidden("code", code),
                    new Field("type", "票的类型", "select", true, null, Collections.unmodifiableList(options)));
        }
        return invalid(INVALID, "二维码格式错误");
    }

    /**
     * 从 OCR 文本里抽候选核销码。返回 candidates(已归类、最多 {@link #MAX_OCR_CANDIDATES} 个)与被排除的节点任务码个数。
     * 节点任务码不进候选 —— 商家对着店里的张贴码拍照时,不能把它当玩家凭证提交出去。
     */
    public static Map<String, Object> ocrCandidates(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        String source = text == null ? "" : text;
        if (source.length() > MAX_OCR_TEXT) source = source.substring(0, MAX_OCR_TEXT);
        Set<String> tokens = new LinkedHashSet<>();
        collect(OCR_DYNAMIC, source, tokens);
        collect(OCR_COUPON, source, tokens);
        Matcher loose = OCR_LEGACY_LOOSE.matcher(source);
        while (loose.find()) tokens.add(repairLegacy(loose.group()));
        int excludedNodeCodes = 0;
        Matcher poster = OCR_POSTER.matcher(source);
        while (poster.find()) excludedNodeCodes++;
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String token : tokens) {
            if (candidates.size() >= MAX_OCR_CANDIDATES) break;
            Resolution resolution = classify(token);
            if (!resolution.accepted()) continue;
            Map<String, Object> view = resolution.toView();
            view.put("code", token);
            view.put("masked", mask(token));
            candidates.add(view);
        }
        result.put("candidates", candidates);
        result.put("excludedNodeTaskCodes", excludedNodeCodes);
        return result;
    }

    static String repairLegacy(String token) {
        String compact = token.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        String digits = compact.substring(3, 16).replace('O', '0').replace('I', '1').replace('L', '1');
        return "VER" + digits + compact.substring(16);
    }

    static String mask(String code) {
        if (code.length() <= 8) return "****";
        return code.substring(0, 4) + "****" + code.substring(code.length() - 4);
    }

    private static Resolution classifyJson(String code) {
        String type = jsonString(code, "type");
        String inner = jsonString(code, "code");
        if (type == null || inner == null || inner.isEmpty()) return invalid(INVALID, "二维码格式错误");
        if ("coupon".equals(type)) {
            return route(KIND_COUPON, "/api/coupon/verification", hidden("code", inner), hidden("scope", "MERCHANT"));
        }
        if ("activity".equals(type) || "topic".equals(type)) {
            return route(KIND_LEGACY_TICKET, "/api/registration/scan_qr_code", hidden("code", inner), hidden("type", type));
        }
        return invalid(INVALID, "二维码格式错误");
    }

    /** 只认扁平的 {"type":"…","code":"…"}:不引第三方 JSON 库,也不接受嵌套/转义花样。 */
    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([A-Za-z0-9_.\\-]{1,200})\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void collect(Pattern pattern, String source, Set<String> into) {
        Matcher m = pattern.matcher(source);
        while (m.find()) into.add(m.group());
    }

    private static Field hidden(String name, String value) {
        return new Field(name, null, "hidden", true, value, null);
    }

    private static Map<String, String> option(String value, String label) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        return o;
    }

    private static Resolution route(String kind, String endpoint, Field... fields) {
        List<Field> list = new ArrayList<>();
        Collections.addAll(list, fields);
        return new Resolution(kind, true, null, null, endpoint, Collections.unmodifiableList(list), null);
    }

    private static Resolution reject(String kind, String errorCode, String message, String redirectPath) {
        return new Resolution(kind, false, errorCode, message, null, Collections.emptyList(), redirectPath);
    }

    private static Resolution invalid(String errorCode, String message) {
        return reject(KIND_INVALID, errorCode, message, null);
    }
}
