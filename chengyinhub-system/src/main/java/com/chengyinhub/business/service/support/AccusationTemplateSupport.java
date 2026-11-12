package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * CT-07「指认结案模板」的配置映射:保存/发布形状闸 + 安全投影(私有施工文档 v1.3.2,只读)。
 *
 * <p>原文逐字(施工文档 CT-07「待改行为」):
 * 「可选结局块 {@code accusation{suspects[],introScreens[],winScreen,loseScreen,corkboardImage,
 * earlyAccuse:{allowed,recordAs}}};最终一题复用 {@code ImageSingleChoice}(照片指认);
 * 允许随时指认的策略下,结束态记"提前结案"而非失败。」
 * 同卡「字段/API」:「提交走既有答案校验」;同卡「旧数据/迁移」:「无该块即普通结局」。
 * 0.5 补:「每人有稳定ID、头像、姓名、身份和已获证词」「图片下有可读的姓名」
 * 「错误指认有独立结局文案」「未获得的证词不提前展示」。
 *
 * <p>因此本类**只做配置与呈现**,不做判定:
 * <ul>
 *   <li>正确答案只有一个真源 —— 原 {@code qa.options[].correct};被选中的 {@code optionId} 就是嫌疑人 ID。
 *       作者若另写 {@code correctSuspectId} 会被当未知字段直接拒,不再兼容。</li>
 *   <li>对错、提前、是否终局、结束态留痕全部由服务端按原 QA 判定与
 *       {@code AccusationOutcomePolicy} 得出;本类不复制一份。</li>
 *   <li>确认指认之前的投影不含正确项、不含 win/lose 文案、不含未获证词。</li>
 * </ul>
 *
 * <p>量程:原文未给任何字数上限,本类不设专用阈值;整份配置受既有
 * {@code advanced_config_json} 字节预算约束,媒体地址沿用既有同类校验的协议/路径规则。
 */
public final class AccusationTemplateSupport {

    /** 高级玩法配置里的结案段,与 present/qa 同级。 */
    public static final String SECTION = "accusation";

    /** 与条件 DSL 同一条线索键规则(见 {@link ThemeRuntimeConfig} 的条件变量名)。 */
    private static final Pattern CLUE_KEY = Pattern.compile("clue\\.[a-z][a-z0-9_]{0,47}");

    /** 原文块列出的键;多写一个就拒,避免作者以为生效了却无人读取。 */
    private static final Set<String> BLOCK_FIELDS = Set.of("enabled", "suspects", "introScreens",
            "winScreen", "loseScreen", "corkboardImage", "earlyAccuse");
    /** 0.5 人物卡:稳定 ID、头像、姓名、身份、已获证词。 */
    private static final Set<String> SUSPECT_FIELDS = Set.of("id", "avatar", "name", "identity", "testimony");
    private static final Set<String> TESTIMONY_FIELDS = Set.of("key", "text");
    /** 原文只给 allowed / recordAs;不接受额外的证据门槛玩法规则。 */
    private static final Set<String> EARLY_FIELDS = Set.of("allowed", "recordAs");
    private static final Set<String> CORKBOARD_FIELDS = Set.of("img", "alt");

    private AccusationTemplateSupport() {
    }

    /** 没配该段 / 显式关闭 = 普通结局。 */
    public static boolean isEnabled(JsonNode config) {
        return block(config) != null;
    }

    /**
     * 保存/发布期形状校验(无注册表上下文)。
     *
     * @throws IllegalArgumentException 形状不合法、与选项不对应、头像缺失、结局文案缺失等
     */
    public static void validate(JsonNode config) {
        validate(config, null);
    }

    /**
     * 保存/发布期形状校验。
     *
     * @param config             节点高级玩法配置根(含 {@code qa} 与 {@code accusation})
     * @param registeredClueKeys 已登记的线索/状态键;传 {@code null} 只查键名形状,
     *                           传集合则要求每条证词的键都已登记
     * @throws IllegalArgumentException 同 {@link #validate(JsonNode)}
     */
    public static void validate(JsonNode config, Set<String> registeredClueKeys) {
        JsonNode accusation = block(config);
        if (accusation == null) {
            return;
        }
        ThemeRuntimeConfig.fields(accusation, BLOCK_FIELDS);
        JsonNode enabled = accusation.get("enabled");
        if (enabled != null && !enabled.isNull() && !enabled.isBoolean()) {
            throw new IllegalArgumentException("指认结案 enabled 须为开关");
        }

        // CT-07:最终一题复用 ImageSingleChoice(照片指认),提交走既有答案校验。
        JsonNode qa = config.get("qa");
        if (qa == null || !qa.isObject() || !qa.path("enabled").asBoolean(false)) {
            throw new IllegalArgumentException("指认结案必须复用已启用的选项问答(最终一题用原 QA)");
        }
        if (!"PICK".equals(qa.path("mode").asText(""))) {
            throw new IllegalArgumentException("指认结案复用图片单选:问答模式须为 PICK");
        }
        if (!qa.path("judge").asBoolean(true)) {
            throw new IllegalArgumentException("指认结案要求最终一题开启对错判定");
        }
        if (qa.path("multi").asBoolean(false)) {
            throw new IllegalArgumentException("指认结案只能是单选");
        }
        JsonNode options = qa.get("options");
        if (options == null || !options.isArray() || options.size() < 2 || options.size() > 4) {
            // 2 至 4 是原问答自己的选项条数范围,不是本段另立的阈值。
            throw new IllegalArgumentException("指认嫌疑人须与问答的 2 至 4 个选项一一对应");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (JsonNode option : options) {
            String id = option == null ? "" : option.path("id").asText("").trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("指认结案依赖的问答选项缺少 ID");
            }
            labels.put(id, option.path("label").asText(""));
        }
        // 正确答案只有一个真源:原 qa.options[].correct。作者不再另填一份。
        if (correctSuspectId(qa) == null) {
            throw new IllegalArgumentException("指认结案要求问答有且只有一个正确选项(沿用原 QA 判定)");
        }

        JsonNode suspects = accusation.get("suspects");
        if (suspects == null || !suspects.isArray() || suspects.size() == 0) {
            throw new IllegalArgumentException("指认模板至少要有人物档案");
        }
        if (suspects.size() != options.size()) {
            throw new IllegalArgumentException("指认人物必须与问答的 " + options.size() + " 个选项一一对应");
        }
        Set<String> mirrored = new LinkedHashSet<>();
        for (JsonNode suspect : suspects) {
            if (suspect == null || !suspect.isObject()) {
                throw new IllegalArgumentException("指认人物档案格式不正确");
            }
            ThemeRuntimeConfig.fields(suspect, SUSPECT_FIELDS);
            String id = requiredText(suspect, "id", "指认人物缺少稳定 ID");
            if (!labels.containsKey(id)) {
                throw new IllegalArgumentException("指认人物 " + id + " 不是问答的选项");
            }
            if (!mirrored.add(id)) {
                throw new IllegalArgumentException("指认人物 ID 重复:" + id);
            }
            String name = requiredText(suspect, "name", "指认人物 " + id + " 缺少姓名");
            if (!name.equals(labels.get(id).trim())) {
                throw new IllegalArgumentException("指认人物 " + id + " 的姓名必须与选项文案一致(同一人只有一份姓名)");
            }
            mediaRequired(suspect, "avatar", "指认人物 " + id + " 的头像");
            optionalText(suspect, "identity", "指认人物 " + id + " 的身份");
            testimony(suspect.get("testimony"), id, registeredClueKeys);
        }
        if (mirrored.size() != labels.size()) {
            throw new IllegalArgumentException("问答的每个选项都要有人物档案");
        }

        screens(accusation.get("introScreens"));
        requiredScreen(accusation, "winScreen", "正确指认的结局");
        requiredScreen(accusation, "loseScreen", "错误指认的结局");
        corkboard(accusation.get("corkboardImage"));
        earlyAccuse(accusation.get("earlyAccuse"));
    }

    /**
     * 确认指认**之前**的公开投影:人物卡 + 引导屏 + 线索板图 + 是否允许提前指认。
     *
     * <p>不下发:正确人物(由原 QA 派生,不进公开面)、{@code correct}、{@code winScreen} /
     * {@code loseScreen}(提交后才给)、{@code earlyAccuse.recordAs}(结束态留痕是服务端记录,
     * 客户端从提交回执的 {@code early} 得知,不从配置读)、未获得的证词。
     *
     * @param visible      媒体可见性解析;{@code null} = 原样保留引用(与 qa.imageUrl 同口径);
     *                     返回空串表示当前不可见,该图整条不下发
     * @param clueObtained 某条线索键在本局服务端事实里是否已获得;{@code null} = 一条证词都不下发
     */
    public static ObjectNode publicView(JsonNode config, Function<String, String> visible,
            Predicate<String> clueObtained) {
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        JsonNode accusation = block(config);
        view.put("enabled", accusation != null);
        if (accusation == null) {
            return view;
        }
        Map<String, String> labels = optionLabels(config.get("qa"));
        ArrayNode suspects = view.putArray("suspects");
        for (JsonNode suspect : accusation.path("suspects")) {
            if (suspect == null || !suspect.isObject()) {
                continue;
            }
            String id = suspect.path("id").asText("").trim();
            if (id.isEmpty()) {
                continue;
            }
            ObjectNode row = suspects.addObject();
            row.put("id", id);
            // 姓名只有一个真源:原问答的选项文案(照片指认 = 同一张选项)。
            row.put("name", labels.getOrDefault(id, suspect.path("name").asText("")));
            putIfText(row, "identity", suspect);
            putMedia(row, suspect, "avatar", visible);
            ArrayNode testimony = row.putArray("testimony");
            for (JsonNode item : suspect.path("testimony")) {
                String key = item == null ? "" : item.path("key").asText("").trim();
                if (key.isEmpty() || clueObtained == null || !clueObtained.test(key)) {
                    continue;
                }
                testimony.addObject().put("key", key).put("text", item.path("text").asText(""));
            }
            if (testimony.size() == 0) {
                row.remove("testimony");
            }
        }
        JsonNode introScreens = accusation.get("introScreens");
        if (introScreens != null && introScreens.isArray()) {
            view.set("introScreens", introScreens.deepCopy());
        }
        JsonNode corkboardImage = accusation.get("corkboardImage");
        if (corkboardImage != null && !corkboardImage.isNull()) {
            if (corkboardImage.isTextual()) {
                String shown = resolve(visible, corkboardImage.asText().trim());
                if (shown != null) {
                    view.put("corkboardImage", shown);
                }
            } else if (corkboardImage.isObject()) {
                ObjectNode board = view.putObject("corkboardImage");
                putMedia(board, corkboardImage, "img", visible);
                putIfText(board, "alt", corkboardImage);
                if (board.size() == 0) {
                    view.remove("corkboardImage");
                }
            }
        }
        JsonNode early = accusation.get("earlyAccuse");
        if (early != null && early.isObject()) {
            view.putObject("earlyAccuse").put("allowed", early.path("allowed").asBoolean(false));
        }
        return view;
    }

    /**
     * 提交之后该发的结局呈现:{@code correct} 取 {@code winScreen},否则 {@code loseScreen}。
     * 返回原段(文字或作者写的呈现对象),没配返回 {@code null}。
     *
     * <p>{@code correct} 必须是服务端判定结果 —— 客户端自报的 correct/early 不参与。
     * 提前结案的结束态与奖励口径由服务端结案判定决定,本方法只按结论选段。
     */
    public static JsonNode resultScreen(JsonNode config, boolean correct) {
        JsonNode accusation = block(config);
        if (accusation == null) {
            return null;
        }
        JsonNode screen = accusation.get(correct ? "winScreen" : "loseScreen");
        if (screen == null || screen.isNull()) {
            return null;
        }
        return screen.deepCopy();
    }

    /**
     * 正确嫌疑人 ID,只由原 QA 派生(单选正好一个)。
     *
     * <p>这是**唯一**的答案来源:入参是 {@code config.qa},不是 {@code accusation} 段;
     * 作者在 {@code accusation} 里写同名配置字段会被当未知字段直接拒,不存在第二份答案。
     *
     * @return 唯一 {@code correct=true} 的选项 ID;零个或多个则返回 {@code null}
     */
    public static String correctSuspectId(JsonNode qa) {
        if (qa == null || !qa.isObject() || !qa.path("options").isArray()) {
            return null;
        }
        String found = null;
        for (JsonNode option : qa.path("options")) {
            if (option == null || !option.path("correct").asBoolean(false)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            String id = option.path("id").asText("").trim();
            if (id.isEmpty()) {
                return null;
            }
            found = id;
        }
        return found;
    }

    // ---------------------------------------------------------------- internals

    /** 配置根里的启用段;缺段/显式关闭/非对象 → {@code null}。 */
    private static JsonNode block(JsonNode config) {
        if (config == null || !config.isObject()) {
            return null;
        }
        JsonNode accusation = config.get(SECTION);
        if (accusation == null || accusation.isNull() || !accusation.isObject()) {
            return null;
        }
        JsonNode enabled = accusation.get("enabled");
        if (enabled != null && enabled.isBoolean() && !enabled.asBoolean()) {
            return null;
        }
        return accusation;
    }

    private static Map<String, String> optionLabels(JsonNode qa) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (qa == null || !qa.isObject()) {
            return labels;
        }
        for (JsonNode option : qa.path("options")) {
            String id = option == null ? "" : option.path("id").asText("").trim();
            if (!id.isEmpty()) {
                labels.put(id, option.path("label").asText(""));
            }
        }
        return labels;
    }

    private static String requiredText(JsonNode node, String field, String message) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.asText().trim();
    }

    private static void optionalText(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(where + "须为文字");
        }
    }

    /** 必填媒体引用:只查协议/路径安全性,量程交给整份配置预算。 */
    private static void mediaRequired(JsonNode node, String field, String where) {
        JsonNode reference = node.get(field);
        if (reference == null || reference.isNull() || !reference.isTextual()
                || reference.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(where + "不能为空");
        }
        if (!QaOptionMediaSupport.isAllowedReference(reference.asText())) {
            throw new IllegalArgumentException(where + "必须是 https 链接或站内路径");
        }
    }

    private static void testimony(JsonNode rows, String suspectId, Set<String> registeredClueKeys) {
        if (rows == null || rows.isNull()) {
            return;
        }
        if (!rows.isArray()) {
            throw new IllegalArgumentException("指认人物 " + suspectId + " 的证词格式不正确");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                throw new IllegalArgumentException("指认人物 " + suspectId + " 的证词格式不正确");
            }
            ThemeRuntimeConfig.fields(row, TESTIMONY_FIELDS);
            String key = row.path("key").asText("").trim();
            if (!CLUE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("指认人物 " + suspectId + " 的证词键不受支持:" + key);
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("指认人物 " + suspectId + " 的证词键重复:" + key);
            }
            if (registeredClueKeys != null && !registeredClueKeys.contains(key)) {
                throw new IllegalArgumentException("指认人物 " + suspectId + " 的证词引用了未登记的线索:" + key);
            }
            requiredText(row, "text", "指认人物 " + suspectId + " 的证词内容不能为空");
        }
    }

    /** 原文写作 {@code introScreens[]}:只要求是数组,单屏形状原文未给,原样透传。 */
    private static void screens(JsonNode introScreens) {
        if (introScreens == null || introScreens.isNull()) {
            return;
        }
        if (!introScreens.isArray()) {
            throw new IllegalArgumentException("指认引导屏须为数组");
        }
    }

    private static void requiredScreen(JsonNode accusation, String field, String where) {
        JsonNode screen = accusation.get(field);
        if (screen == null || screen.isNull()) {
            throw new IllegalArgumentException("指认结案必须配置" + where);
        }
        if (screen.isTextual()) {
            if (screen.asText().trim().isEmpty()) {
                throw new IllegalArgumentException(where + "不能为空");
            }
            return;
        }
        if (!screen.isObject()) {
            throw new IllegalArgumentException(where + "须为文字或呈现对象");
        }
    }

    private static void corkboard(JsonNode corkboardImage) {
        if (corkboardImage == null || corkboardImage.isNull()) {
            return;
        }
        if (corkboardImage.isTextual()) {
            if (!QaOptionMediaSupport.isAllowedReference(corkboardImage.asText())) {
                throw new IllegalArgumentException("线索板图必须是 https 链接或站内路径");
            }
            return;
        }
        if (!corkboardImage.isObject()) {
            throw new IllegalArgumentException("线索板图格式不正确");
        }
        ThemeRuntimeConfig.fields(corkboardImage, CORKBOARD_FIELDS);
        mediaRequired(corkboardImage, "img", "线索板图");
        optionalText(corkboardImage, "alt", "线索板图替代文字");
    }

    /**
     * 原文只有 {@code earlyAccuse:{allowed,recordAs}} 两个键,且明确「结束态记"提前结案"」。
     *
     * <p>所以这里:不认额外的证据门槛键(如 {@code requires}),不自造留痕码枚举,
     * 也不给默认值 —— 允许提前指认就必须由作者写明记录什么;缺了就在保存期报错,
     * 而不是运行期悄悄落一个平台自定的终态。
     */
    private static void earlyAccuse(JsonNode early) {
        if (early == null || early.isNull()) {
            return;
        }
        if (!early.isObject()) {
            throw new IllegalArgumentException("提前指认配置格式不正确");
        }
        ThemeRuntimeConfig.fields(early, EARLY_FIELDS);
        JsonNode allowed = early.get("allowed");
        if (allowed == null || !allowed.isBoolean()) {
            throw new IllegalArgumentException("提前指认 allowed 须为开关");
        }
        if (allowed.asBoolean()) {
            requiredText(early, "recordAs", "允许提前指认时必须写明结束态记录(recordAs)");
        } else {
            optionalText(early, "recordAs", "提前指认结束态记录");
        }
    }

    private static void putIfText(ObjectNode target, String field, JsonNode source) {
        JsonNode value = source == null ? null : source.get(field);
        if (value != null && value.isTextual() && !value.asText().trim().isEmpty()) {
            target.put(field, value.asText().trim());
        }
    }

    private static void putMedia(ObjectNode target, JsonNode source, String field,
            Function<String, String> visible) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            return;
        }
        String shown = resolve(visible, value.asText().trim());
        if (shown != null) {
            target.put(field, shown);
        }
    }

    private static String resolve(Function<String, String> visible, String reference) {
        String shown = visible == null ? reference : visible.apply(reference);
        return shown == null || shown.trim().isEmpty() ? null : shown.trim();
    }
}
