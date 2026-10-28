package com.chengyinhub.business.service.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI 认物:一件拍过的物品 → 中文卡名 + 固定清单里的分类(施工文档 §2.4)。
 *
 * <p>与拍照审核**共用视觉通道、不共用那一次调用**:审核问的是「拍没拍到作者要的东西」,
 * 认物问的是「这是什么」。合成一次调用会让一个提示词兼两件事,而审核那条链路的提示词有测试与
 * 负控钉着判定,不该为卡名改动。
 *
 * <p>口径与抠图那条分支一样:拿不到就返回 null(= 没有结果),**不抛出去**。铸卡在并行分支上
 * 等这一件 4 秒,一次抛异常会把一张本来能出的卡一起带走。
 *
 * <p>不占任何额度池:一张卡只认一次、跟着铸卡走,没有「同一玩家一天反复刷」的入口;
 * 蹭拍照那条链的池子只会让玩家的照片审核次数被铸卡悄悄吃掉。
 *
 * <p>图片固定编成 base64 data URL 再发,不把 URL 交给模型 —— 原因见 {@link VisionImageEncoder} 类注释。
 *
 * @author chengyinhub
 */
@Component
public class ObjectNamer {

    private static final Logger log = LoggerFactory.getLogger(ObjectNamer.class);

    /** 卡名上限(施工文档 §2.4):卡面那一行要放得下,超长直接截,截断不许落在代理对中间。 */
    static final int NAME_MAX = 12;

    /** 分类真源。卡面按这一组做配色与筛选,所以清单外的值一律归「其他」,不让模型自由发挥。 */
    static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "电子产品", "服饰", "鞋包", "食物饮料", "书籍文具", "玩具摆件", "日用杂物", "其他"));

    static final String CATEGORY_OTHER = "其他";

    private static final String SYSTEM_PROMPT =
            "你是物品识别员。玩家拍了一张物品照片,请认出画面里的主体物品。\n"
            + "只输出一个 JSON 对象,不要输出任何其他文字,格式:\n"
            + "{\"name\": \"不超过 " + NAME_MAX + " 个汉字的日常叫法\", \"category\": \""
            + String.join("|", CATEGORIES) + "\"}\n"
            + "name 用日常叫法(如「搪瓷茶缸」),不要品牌、型号、形容词;"
            + "category 必须从竖线里选一个,认不准就选「其他」。";

    private static final String USER_PROMPT = "请识别这张照片里的主体物品,按格式输出 JSON。";

    /** 开关默认关:关着 = 卡名沿用商家配的模板名,与没接这个功能时完全一致(施工文档 §8 灰度)。 */
    @Value("${object-card.naming.enabled:false}")
    private boolean enabled;

    /** 与拍照审核同一个视觉客户端(配置见 ai.vision.*);没装配 = 拿不到结果,不影响出卡。 */
    @Autowired(required = false)
    @Qualifier("visionLlmClient")
    private LlmClient visionClient;

    private final VisionImageEncoder imageEncoder = new VisionImageEncoder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param imageUrl 玩家照片地址,已过铸卡的来源闸
     * @return 认出来的名与分类(各自可空);{@code null} = 没有结果
     */
    public Naming name(String imageUrl) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!enabled) {
            return null;
        }
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty()) {
            return null;
        }
        // 超限/格式不认在发请求前就拒,别发出去等模型报错(与拍照审核同一条实测口径)
        VisionImageEncoder.Encoded encoded = imageEncoder.encode(url);
        if (encoded.isRejected()) {
            log.warn("[认物] 图片没能送进模型: reason={}", encoded.getRejectReason());
            return null;
        }

        if (visionClient == null) {
            log.warn("[认物] 视觉通道未装配,这次不认物");
            return null;
        }
        String answer;
        try {
            answer = visionClient.chatVision(SYSTEM_PROMPT, encoded.getDataUrl(), USER_PROMPT);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            log.error("[认物] 视觉调用失败, exceptionType={}", e.getClass().getSimpleName());
            return null;
        }

        Naming naming = parse(answer);
        if (naming == null) {
            log.warn("[认物] 模型没有给出可用的名与分类");
        }
        return naming;
    }

    /** 模型常把 JSON 包在 ```json 围栏或前后话术里:取第一个 { 到最后一个 } 再解析。 */
    private Naming parse(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return null;
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(answer.substring(start, end + 1));
            String name = StringUtils.cutKeepingSurrogates(root.path("name").asText("").trim(), NAME_MAX);
            String category = category(root.path("category").asText("").trim());
            if (name.isEmpty() && category == null) {
                return null;
            }
            return new Naming(name.isEmpty() ? null : name, category);
        } catch (Exception notJson) {
            return null;
        }
    }

    /**
     * 清单外的值一律归「其他」。但空值是「没认出来」而不是「其他」:库里 {@code category} 留空,
     * 卡面不会凭空显示一个分类(与 T1 那列的注释同一口径)。
     */
    static String category(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return CATEGORIES.contains(value) ? value : CATEGORY_OTHER;
    }

    /** 认物结果:名与分类各自可空 —— 模型只认出一半时另一半留空,不硬编一个凑数。 */
    public static final class Naming {
        private final String name;
        private final String category;

        public Naming(String name, String category) {
            this.name = name;
            this.category = category;
        }

        public String getName() {
            return name;
        }

        public String getCategory() {
            return category;
        }
    }
}
