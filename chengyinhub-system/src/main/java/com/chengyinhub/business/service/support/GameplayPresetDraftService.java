package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定玩法预设 → 作者自己的**个人模板草稿**。
 *
 * <p>这条链只做三件事：①按目录声明校验作者填的参数；②把参数写进结构模板；③调用**原**
 * {@link ITemplatePublishService#saveOrUpdateDraft} 落成作者私有草稿。它不新建表、不建第二个
 * 编辑器、不发明玩法语义，也不写任何一条"市场来源行"——预设不是市场上架内容。
 *
 * <p>为什么不复用 {@code adopt-draft}：那条路要求 {@code originalTemplateId} 指向一条
 * 已审核已上架的 {@code cms_template_library} 行。预设没有这样一行，也不应该有一条（那会
 * 把平台结构假冒成某位作者的市场版权）。所以这里把 {@code originalTemplateId} 留 0，用
 * {@code presetSource} 记来源。
 *
 * <p>未填不发布：所有声明的参数在**创建时**就必须齐全且非空；模板本身再过一遍原保存闸。
 * 不做"扫描'待填'三个字"这种字面检查——那既会误伤正常文案，也拦不住真缺内容。
 * 创建出来的是草稿（draft_status=0 / status=0 / publish_status=0），公开与可售仍归原来的
 * 审核 → 广场提交 → 上架流程，本类一步都不跳。
 */
@Service
public class GameplayPresetDraftService {

    private final ITemplatePublishService templatePublishService;
    private final GameplayPresetCatalog catalog;

    @Autowired
    public GameplayPresetDraftService(ITemplatePublishService templatePublishService) {
        this(templatePublishService, GameplayPresetCatalog.shared());
    }

    /** 测试专用：注入另一份目录实例（例如把版本号改成 2 验证"来源升级不覆盖旧草稿"）。 */
    static GameplayPresetDraftService withCatalog(ITemplatePublishService templatePublishService,
                                                  GameplayPresetCatalog catalog) {
        return new GameplayPresetDraftService(templatePublishService, catalog);
    }

    private GameplayPresetDraftService(ITemplatePublishService templatePublishService,
                                       GameplayPresetCatalog catalog) {
        this.templatePublishService = templatePublishService;
        this.catalog = catalog;
    }

    public GameplayPresetCatalog catalog() {
        return catalog;
    }

    /**
     * 用固定预设创建作者私有草稿。
     *
     * @param owner   归属人（个人或已校验的商家主账号）；不允许 0 或占位用户
     * @param username 写入 create_by 的操作人
     * @param fields  作者填的参数，键必须是目录声明过的
     * @return 创建回执：草稿 ID、预设来源标记、仍需作者自己做的事
     */
    public Map<String, Object> create(Long owner, String username, String presetKey, Map<String, Object> fields) {
        if (owner == null || owner <= 0) throw new IllegalArgumentException("请先登录");
        GameplayPresetCatalog.Preset preset = catalog.require(presetKey);
        Map<String, String> flattened = resolve(preset, fields);
        ObjectNode config = applyTemplate(preset, flattened);

        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle(requestTitle(preset, flattened));
        request.setValidationMethod(preset.validationMethod());
        request.setAdvancedConfigJson(config.toString());
        request.setOriginalTemplateId(0L);
        request.setIsSync(0);
        request.setPresetSource(preset.sourceMark());

        Long draftId = templatePublishService.saveOrUpdateDraft(request, owner, username);
        if (draftId == null || draftId <= 0) throw new IllegalStateException("预设草稿编号缺失");

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("presetKey", preset.presetKey());
        receipt.put("presetVersion", preset.version());
        receipt.put("presetSource", preset.sourceMark());
        receipt.put("draftId", String.valueOf(draftId));
        receipt.put("templateType", "MINI_GAME_DRAFT");
        receipt.put("draftStatus", 0);
        receipt.put("publishStatus", 0);
        receipt.put("cityBinding", preset.cityBinding().publicView());
        receipt.put("authorTodos", preset.authorTodos());
        return receipt;
    }

    // ------------------------------------------------------------------ 参数校验

    /**
     * 逐字段校验并把作者输入规范成"可直接写进模板"的字符串/数字。
     *
     * <p>缺一个就整体拒绝，并把**全部**缺失项一次列出来——逐个报错会让作者来回试五遍。
     */
    private Map<String, String> resolve(GameplayPresetCatalog.Preset preset, Map<String, Object> fields) {
        Map<String, Object> input = fields == null ? Map.of() : fields;
        Set<String> declared = new LinkedHashSet<>();
        for (GameplayPresetCatalog.Field field : preset.fields()) declared.add(field.key());
        for (String key : input.keySet()) {
            if (!declared.contains(key)) {
                throw new IllegalArgumentException("预设没有声明这个参数：" + key);
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        List<Map<String, String>> missing = new ArrayList<>();
        for (GameplayPresetCatalog.Field field : preset.fields()) {
            Object value = input.get(field.key());
            if (value == null || (value instanceof String text && text.trim().isEmpty())) {
                Map<String, String> required = new LinkedHashMap<>();
                required.put("key", field.key());
                required.put("label", field.label());
                missing.add(required);
                continue;
            }
            resolved.put(field.key(), switch (field.kind()) {
                case TEXT -> text(preset, field, value);
                case NUMBER -> number(preset, field, value);
                case CHOICE -> choice(preset, field, value);
                case ORDER -> order(preset, field, value);
                case BIN_MAP -> binMap(preset, field, value);
            });
        }
        if (!missing.isEmpty()) {
            // 清单走结构化 requiredFields，不拼进消息：长消息会被 UserSafeMessage 的 120 字上限压成「系统繁忙」。
            throw new MissingFieldsException(missing);
        }
        return resolved;
    }

    private static String text(GameplayPresetCatalog.Preset preset,
            GameplayPresetCatalog.Field field, Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(preset.presetKey() + " 的 " + field.label() + " 必须是文字");
        }
        String trimmed = text.trim();
        if (trimmed.length() > field.maxLength()) {
            throw new IllegalArgumentException(field.label() + " 不能超过 " + field.maxLength() + " 字");
        }
        // 作者文本里若自带了 ${...}，替换阶段会被当成另一个参数，最后变成"未解析 token"。
        // 与其让作者看到一句看不懂的错误，不如当场说清楚。
        if (trimmed.contains("${")) {
            throw new IllegalArgumentException(field.label() + " 不能包含 ${ 字符");
        }
        return trimmed;
    }

    private static String number(GameplayPresetCatalog.Preset preset,
            GameplayPresetCatalog.Field field, Object value) {
        int parsed;
        if (value instanceof Number numeric) {
            parsed = numeric.intValue();
        } else if (value instanceof String raw && raw.trim().matches("-?[0-9]{1,9}")) {
            parsed = Integer.parseInt(raw.trim());
        } else {
            throw new IllegalArgumentException(preset.presetKey() + " 的 " + field.label() + " 必须是整数");
        }
        if (parsed < field.min() || parsed > field.max()) {
            throw new IllegalArgumentException(field.label() + " 须在 " + field.min() + " 至 " + field.max() + " 之间");
        }
        return String.valueOf(parsed);
    }

    private static String choice(GameplayPresetCatalog.Preset preset,
            GameplayPresetCatalog.Field field, Object value) {
        if (!(value instanceof String chosen) || !field.options().contains(chosen.trim())) {
            throw new IllegalArgumentException(field.label() + " 只能从 " + String.join("、", field.options()) + " 里选");
        }
        return chosen.trim();
    }

    private static String order(GameplayPresetCatalog.Preset preset,
            GameplayPresetCatalog.Field field, Object value) {
        if (!(value instanceof List<?> rows) || rows.size() != field.options().size()) {
            throw new IllegalArgumentException(field.label() + " 必须把全部 " + field.options().size() + " 项各排一次");
        }
        List<String> values = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof String text)) {
                throw new IllegalArgumentException(field.label() + " 的每一项都必须是标识");
            }
            values.add(text.trim());
        }
        if (!new LinkedHashSet<>(values).equals(new LinkedHashSet<>(field.options()))) {
            throw new IllegalArgumentException(field.label() + " 的项与预设不一致");
        }
        return String.join(",", values);
    }

    private static String binMap(GameplayPresetCatalog.Preset preset,
            GameplayPresetCatalog.Field field, Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException(field.label() + " 必须给出每一项属于哪个分类");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            String item = String.valueOf(entry.getKey()).trim();
            String bin = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
            if (!field.items().contains(item)) {
                throw new IllegalArgumentException(field.label() + " 引用了预设里没有的待分类项：" + item);
            }
            if (!field.bins().contains(bin)) {
                throw new IllegalArgumentException(field.label() + " 给 " + item + " 指了预设里没有的分类：" + bin);
            }
            normalized.put(item, bin);
        }
        if (!normalized.keySet().containsAll(field.items())) {
            throw new IllegalArgumentException(field.label() + " 必须覆盖全部待分类项");
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            pairs.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join(",", pairs);
    }

    // ------------------------------------------------------------------ 模板装配

    private static String requestTitle(GameplayPresetCatalog.Preset preset, Map<String, String> values) {
        for (GameplayPresetCatalog.Field field : preset.fields()) {
            if ("request.title".equals(field.target())) return values.get(field.key());
        }
        return preset.title();
    }

    /**
     * 把作者参数写进结构模板。
     *
     * <p>变换本身住在 {@link GameplayPresetCatalog#materialize}：目录加载期用同一份实现验样例，
     * 这里用它对作者真实输入产出配置。分两处实现的话，"能加载"和"能创建"迟早走成两套规则。
     */
    private static ObjectNode applyTemplate(GameplayPresetCatalog.Preset preset, Map<String, String> values) {
        ObjectNode config = GameplayPresetCatalog.materialize(preset.template(), preset.fields(), values);
        // 保存前自查一遍：作者填的内容也必须过真实保存闸，不能等到下游才炸。
        new AdvancedGameConfigValidator().validateAndNormalize(config.toString());
        return config;
    }

    /** 预设必填项没填齐：短消息 + 结构化 {@code requiredFields}（[{key,label}]），按声明顺序一次列全。 */
    public static final class MissingFieldsException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public static final String CODE = "PRESET_FIELDS_REQUIRED";
        public static final String MESSAGE = "预设还有必填内容没填，补齐后才能建草稿";
        private final List<Map<String, String>> requiredFields;

        public MissingFieldsException(List<Map<String, String>> requiredFields) {
            super(MESSAGE);
            this.requiredFields = List.copyOf(requiredFields);
        }

        public List<Map<String, String>> getRequiredFields() { return requiredFields; }
    }
}
