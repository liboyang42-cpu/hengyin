package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 图片选项的写入校验与公开投影(主题稿 0.3「图片选项」;CT-07「最终一题复用图片单选」)。
 *
 * <p>原文依据(私有施工文档 v1.3.2,只读):
 * <ul>
 *   <li>0.3:作者给单/多选项配图,玩家**点图提交原 optionId**;复用原 QA 的单/多选判定、
 *       反馈、条件、题干媒体,只补选项媒体的配置/冻结/投影/渲染链。</li>
 *   <li>CT-07:最终一题复用 {@code ImageSingleChoice};「提交走既有答案校验」;结论由服务端判定。</li>
 * </ul>
 *
 * <p>本类只加两个**呈现**字段,判定一个字都不改:
 * <ul>
 *   <li>{@code img} —— 选项图引用。名字已在 {@link AppMediaSchema#MEDIA_FIELDS} 里,
 *       由 {@link AppMediaPublicationGuard} 按原归属 + 审核事实改写成 {@code media-asset:{id}},
 *       不新开媒体通道。</li>
 *   <li>{@code alt} —— 替代文字。没有配图的选项配不了,配了图必须有,否则读屏用户只拿到一张无名图。</li>
 * </ul>
 *
 * <p>量程不在这里定:整体受 {@code advanced_config_json} 的既有字节预算约束,单字段长度不另设阈值。
 */
public final class QaOptionMediaSupport {

    /** 选项图字段。刻意复用 {@link AppMediaSchema} 已登记的名字。 */
    public static final String FIELD_IMAGE = "img";
    /** 替代文字字段。 */
    public static final String FIELD_ALT = "alt";

    private static final String MEDIA_ASSET = "media-asset:[1-9][0-9]{0,18}";

    private QaOptionMediaSupport() {
    }

    /**
     * 保存/发布期校验:{@code qa.options[]} 的图片与替代文字。
     *
     * <p>只在启用的选项题上校验。选项条数、id 重复、label 必填、correct 唯一性仍由原校验器负责,
     * 本方法不重复、不改动那些规则。缺 {@code qa}、未启用、打字题一律放行。
     *
     * @throws IllegalArgumentException 图片地址不是 https / 站内路径 / media-asset,或配图无替代文字
     */
    public static void validate(JsonNode qa) {
        if (qa == null || !qa.isObject() || !qa.path("enabled").asBoolean(false)) {
            return;
        }
        if (!"PICK".equals(qa.path("mode").asText(""))) {
            return;
        }
        JsonNode options = qa.get("options");
        if (options == null || !options.isArray()) {
            return;
        }
        for (JsonNode option : options) {
            if (option != null && option.isObject()) {
                validateOption(option, option.path("id").asText(""));
            }
        }
    }

    /** 单个选项的图片校验。{@code optionId} 只用于报错定位。 */
    public static void validateOption(JsonNode option, String optionId) {
        if (option == null || !option.isObject()) {
            return;
        }
        String where = optionId == null || optionId.trim().isEmpty() ? "选项" : "图片选项 " + optionId;
        JsonNode image = option.get(FIELD_IMAGE);
        JsonNode alt = option.get(FIELD_ALT);
        boolean hasImage = image != null && !image.isNull();
        boolean hasAlt = alt != null && !alt.isNull();
        if (!hasImage) {
            if (hasAlt && alt.isTextual() && !alt.asText().trim().isEmpty()) {
                throw new IllegalArgumentException(where + " 写了替代文字却没有配图");
            }
            return;
        }
        if (!image.isTextual()) {
            throw new IllegalArgumentException(where + " 的图片地址格式不正确");
        }
        String reference = image.asText().trim();
        if (reference.isEmpty()) {
            // 空串等于没配图;配了 alt 就是悬空文案,同上面一档处理。
            if (hasAlt && alt.isTextual() && !alt.asText().trim().isEmpty()) {
                throw new IllegalArgumentException(where + " 写了替代文字却没有配图");
            }
            return;
        }
        if (!isAllowedReference(reference)) {
            throw new IllegalArgumentException(where + " 的图片地址必须是 https 链接或站内路径");
        }
        if (!hasAlt || !alt.isTextual() || alt.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(where + " 配了图就必须写替代文字");
        }
    }

    /** 选项的呈现媒体 {@code {img, alt}};没配图返回空表(不造空键)。 */
    public static Map<String, String> media(JsonNode option) {
        Map<String, String> media = new LinkedHashMap<>();
        if (option == null || !option.isObject()) {
            return media;
        }
        JsonNode image = option.get(FIELD_IMAGE);
        if (image == null || !image.isTextual() || image.asText().trim().isEmpty()) {
            return media;
        }
        media.put(FIELD_IMAGE, image.asText().trim());
        JsonNode alt = option.get(FIELD_ALT);
        if (alt != null && alt.isTextual() && !alt.asText().trim().isEmpty()) {
            media.put(FIELD_ALT, alt.asText().trim());
        }
        return media;
    }

    /**
     * 公开投影:沿用原投影的 id/label,再补 img/alt。
     *
     * <p>{@code visible} 是媒体可见性解析({@code null} = 原样保留引用,与 {@code qa.imageUrl}
     * 现在的下发口径一致)。解析结果为空串表示这张图当前不可见 —— 整条媒体都不下发,
     * 不把不可见引用或空图交给客户端。
     *
     * <p>{@code correct} / {@code fb} / {@code effects} 不在此列,一个字段都不带。
     */
    public static ArrayNode project(JsonNode qa, Function<String, String> visible) {
        ArrayNode safeOptions = JsonNodeFactory.instance.arrayNode();
        if (qa == null || !qa.isObject() || !qa.path("enabled").asBoolean(false)) {
            return safeOptions;
        }
        for (JsonNode option : qa.path("options")) {
            if (option == null || !option.isObject()) {
                continue;
            }
            ObjectNode safe = safeOptions.addObject();
            safe.put("id", option.path("id").asText(""));
            safe.put("label", option.path("label").asText(""));
            Map<String, String> media = media(option);
            if (media.isEmpty()) {
                continue;
            }
            String image = media.get(FIELD_IMAGE);
            String shown = visible == null ? image : visible.apply(image);
            if (shown == null || shown.trim().isEmpty()) {
                continue;
            }
            safe.put(FIELD_IMAGE, shown.trim());
            safe.put(FIELD_ALT, media.get(FIELD_ALT));
        }
        return safeOptions;
    }

    /**
     * 媒体引用语法闸,与题面图/选项媒体/反馈图同一条规则
     * (见 {@code AdvancedGameConfigValidator} 的 {@code optionalMediaUrl}):
     * 站内路径(单个前导 {@code /}、无 {@code \})、https 地址、或已落库的 {@code media-asset:{id}}。
     * {@code data:}、协议相对 {@code //host}、{@code http:} 一律不合法。
     *
     * <p>长度不在这里判:整份高级玩法配置有既有字节预算,单字段不另设阈值。
     */
    public static boolean isAllowedReference(String reference) {
        if (reference == null) {
            return false;
        }
        String value = reference.trim();
        if (value.isEmpty()) {
            return false;
        }
        boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
        return localPath || value.startsWith("https://") || value.matches(MEDIA_ASSET);
    }
}
