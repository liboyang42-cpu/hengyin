package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定玩法预设目录（T01–T08）：作者可选的**有界**起点，不是导入引擎、不是模板市场。
 *
 * <p>为什么不放在数据库里：这里没有一条"市场来源行"可挂。预设是平台自带的结构，不是某位作者
 * 已审核上架的内容，所以既不能写 {@code cms_template_library}（那会假冒市场版权/已售授权），
 * 也不能借 {@code original_template_id} 假冒某条市场来源。目录只从
 * {@code /gameplay-presets/catalog.json} 这一份受控资源读，随代码版本走。
 *
 * <p>为什么不是"通用导入引擎"：目录只提供两件事——①一段**已经能过真实保存闸**的节点配置结构；
 * ②作者必须自己填的参数声明。没有表达式、没有条件分支、没有插件、没有脚本；作者参数只有五种
 * 有界类型（见 {@link Field.Kind}），服务端做整 token 替换与定点赋值，不解析任何语法。
 *
 * <p>加载即校验（fail fast）：任一预设结构过不了 {@link AdvancedGameConfigValidator}、
 * 声明了不存在的玩法能力、或把呈现段当成题型，整个目录加载失败——不允许把坏预设挂给作者。
 */
public final class GameplayPresetCatalog {

    /** 与 {@code advancedConfigJson} 的 schemaVersion 同源；目录自身也有版本。 */
    public static final String CATALOG_RESOURCE = "/gameplay-presets/catalog.json";
    private static final String TEMPLATE_ROOT = "/gameplay-presets/";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 预设标识：题材编号 + 语义名，稳定、可读、可写进库。 */
    private static final Pattern PRESET_KEY = Pattern.compile("[A-Z][0-9]{2}-[a-z][a-z0-9-]{1,47}");
    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,31}");
    /** 作者字段引用：整 token 的 {@code ${key}}，没有函数调用与属性路径。 */
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([a-z][A-Za-z0-9]{0,31})}");
    /** 整个叶子就是一个参数：换成带类型的节点（数字/文本）。 */
    private static final Pattern WHOLE_TOKEN = Pattern.compile("^\\$\\{([a-z][A-Za-z0-9]{0,31})}$");
    /** 装配后仍残留的参数引用：一定是漏填，不能交给保存闸。 */
    private static final Pattern LEFTOVER_TOKEN = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> PRESET_FIELDS = Set.of("presetKey", "version", "title", "purpose",
            "category", "capabilities", "presentationSections", "validationMethod", "templateFile",
            "fields", "cityBinding", "authorTodos");
    private static final Set<String> FIELD_FIELDS = Set.of("key", "label", "kind", "maxLength",
            "min", "max", "options", "items", "bins", "target");
    private static final Set<String> CITY_BINDING_FIELDS = Set.of("appliesAt", "requiredRebinds");
    /** 只有这两个请求字段能由作者参数直接落到模板实体上；其余一律走玩法配置。 */
    private static final Set<String> REQUEST_TARGETS = Set.of("request.title", "request.description");

    private static volatile GameplayPresetCatalog shared;

    private final String catalogVersion;
    private final Map<String, Preset> presets;

    private GameplayPresetCatalog(String catalogVersion, Map<String, Preset> presets) {
        this.catalogVersion = catalogVersion;
        this.presets = presets;
    }

    /** 生产唯一实例：classpath 正式资源，加载一次。 */
    public static GameplayPresetCatalog shared() {
        GameplayPresetCatalog current = shared;
        if (current == null) {
            synchronized (GameplayPresetCatalog.class) {
                current = shared;
                if (current == null) {
                    current = loadFromClasspath();
                    shared = current;
                }
            }
        }
        return current;
    }

    /** 测试与工具用：从一段目录 JSON + 模板加载器解析（同样执行全部加载期校验）。 */
    public static GameplayPresetCatalog parse(String catalogJson,
            java.util.function.Function<String, String> templates) {
        final JsonNode root;
        try {
            root = JSON.readTree(catalogJson);
        } catch (Exception unreadable) {
            throw new IllegalStateException("玩法预设目录不是合法 JSON", unreadable);
        }
        if (!root.isObject()) throw new IllegalStateException("玩法预设目录必须是对象");
        // 目录根只允许 schemaVersion / catalogVersion / presets
        root.fieldNames().forEachRemaining(name -> {
            if (!Set.of("schemaVersion", "catalogVersion", "presets").contains(name)) {
                throw new IllegalStateException("玩法预设目录含未知字段：" + name);
            }
        });
        if (root.path("schemaVersion").asInt(-1) != 1) throw new IllegalStateException("玩法预设目录版本不支持");
        String catalogVersion = text(root, "catalogVersion", "玩法预设目录缺少版本标识", 32);
        JsonNode rows = root.get("presets");
        if (rows == null || !rows.isArray() || rows.size() == 0 || rows.size() > 32) {
            throw new IllegalStateException("玩法预设目录须含 1 至 32 个预设");
        }
        Map<String, Preset> presets = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            Preset preset = readPreset(row, templates);
            if (presets.put(preset.presetKey(), preset) != null) {
                throw new IllegalStateException("玩法预设标识重复：" + preset.presetKey());
            }
        }
        return new GameplayPresetCatalog(catalogVersion, Collections.unmodifiableMap(presets));
    }

    private static GameplayPresetCatalog loadFromClasspath() {
        try {
            return parse(readResource(CATALOG_RESOURCE), path -> {
                if (path == null || path.isEmpty() || path.contains("..") || path.startsWith("/")) {
                    throw new IllegalStateException("玩法预设模板路径不合法：" + path);
                }
                return readResource(TEMPLATE_ROOT + path);
            });
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception unreadable) {
            throw new IllegalStateException("玩法预设目录不可读取", unreadable);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = GameplayPresetCatalog.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("缺少玩法预设资源：" + path);
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("玩法预设资源不可读取：" + path, unreadable);
        }
    }

    private static Preset readPreset(JsonNode row,
            java.util.function.Function<String, String> templates) {
        if (row == null || !row.isObject()) throw new IllegalStateException("玩法预设条目必须是对象");
        row.fieldNames().forEachRemaining(name -> {
            if (!PRESET_FIELDS.contains(name)) throw new IllegalStateException("玩法预设含未知字段：" + name);
        });
        String presetKey = text(row, "presetKey", "玩法预设缺少标识", 48);
        if (!PRESET_KEY.matcher(presetKey).matches()) {
            throw new IllegalStateException("玩法预设标识不规范：" + presetKey);
        }
        int version = row.path("version").asInt(0);
        if (version < 1) throw new IllegalStateException(presetKey + " 的版本号必须为正整数");
        String title = text(row, "title", presetKey + " 缺少标题", 40);
        String purpose = text(row, "purpose", presetKey + " 缺少用途说明", 200);
        String category = text(row, "category", presetKey + " 缺少题材编号", 8);
        int validationMethod = row.path("validationMethod").asInt(0);
        Set<String> capabilities = strings(row.get("capabilities"), presetKey + " 必须声明用到玩法能力", 8);
        Set<String> presentation = strings(row.get("presentationSections"),
                presetKey + " 的呈现段声明不合法", 8);
        requireCapabilities(presetKey, capabilities);
        for (String section : presentation) {
            if (!GameplaySupportMatrix.presentation(section)) {
                // 呈现段不能声明成题型，也不能把未知段名当呈现段糊过去。
                throw new IllegalStateException(presetKey + " 把非呈现段声明成了呈现段：" + section);
            }
        }
        List<Field> fields = readFields(row.get("fields"), presetKey);
        String templateFile = text(row, "templateFile", presetKey + " 缺少模板文件", 120);
        JsonNode template = readTemplate(presetKey, templates.apply(templateFile));
        assertTemplateMatchesDeclarations(presetKey, template, fields, capabilities, presentation);
        CityBinding cityBinding = readCityBinding(row.get("cityBinding"), presetKey);
        List<String> authorTodos = todos(row.get("authorTodos"), presetKey);
        return new Preset(presetKey, version, title, purpose, category, validationMethod,
                capabilities, presentation, fields, (ObjectNode) template, cityBinding, authorTodos);
    }

    private static JsonNode readTemplate(String presetKey, String templateJson) {
        final JsonNode template;
        try {
            template = JSON.readTree(templateJson);
        } catch (Exception unreadable) {
            throw new IllegalStateException(presetKey + " 的模板不是合法 JSON", unreadable);
        }
        if (!(template instanceof ObjectNode)) {
            throw new IllegalStateException(presetKey + " 的模板必须是对象");
        }
        return template;
    }

    private static List<Field> readFields(JsonNode rows, String presetKey) {
        if (rows == null || !rows.isArray() || rows.size() == 0 || rows.size() > 24) {
            throw new IllegalStateException(presetKey + " 必须声明 1 至 24 个作者字段");
        }
        List<Field> fields = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) throw new IllegalStateException("作者字段声明必须是对象");
            row.fieldNames().forEachRemaining(name -> {
                if (!FIELD_FIELDS.contains(name)) throw new IllegalStateException("作者字段含未知属性：" + name);
            });
            String key = text(row, "key", "作者字段缺少 key", 32);
            if (!FIELD_KEY.matcher(key).matches()) throw new IllegalStateException("作者字段 key 不规范：" + key);
            if (!keys.add(key)) throw new IllegalStateException(presetKey + " 的作者字段重复：" + key);
            String label = text(row, "label", presetKey + " 的作者字段 " + key + " 缺少名称", 40);
            Field.Kind kind;
            try {
                kind = Field.Kind.valueOf(text(row, "kind", "作者字段缺少类型", 16));
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalStateException(presetKey + " 的作者字段 " + key + " 类型不支持");
            }
            String target = row.hasNonNull("target") ? text(row, "target", "作者字段目标不合法", 40) : null;
            if (target != null && !REQUEST_TARGETS.contains(target)) {
                throw new IllegalStateException(presetKey + " 的作者字段 " + key + " 目标不受支持：" + target);
            }
            if (target != null && kind != Field.Kind.TEXT) {
                throw new IllegalStateException(presetKey + " 的作者字段 " + key + " 只有文本能落到模板实体");
            }
            fields.add(switch (kind) {
                case TEXT -> new Field(key, label, kind, row.path("maxLength").asInt(0), 0, 0,
                        List.of(), List.of(), List.of(), target);
                case NUMBER -> {
                    int min = row.path("min").asInt(Integer.MIN_VALUE);
                    int max = row.path("max").asInt(Integer.MIN_VALUE);
                    if (min == Integer.MIN_VALUE || max == Integer.MIN_VALUE) {
                        throw new IllegalStateException(presetKey + " 的数值字段 " + key + " 必须给出上下限");
                    }
                    if (min > max) throw new IllegalStateException(presetKey + " 的数值字段 " + key + " 上下限颠倒");
                    yield new Field(key, label, kind, 0, min, max, List.of(), List.of(), List.of(), null);
                }
                case CHOICE -> new Field(key, label, kind, 0, 0, 0,
                        required(row.get("options"), presetKey + " 的 " + key + " 必须给出选项"), List.of(), List.of(), null);
                case ORDER -> new Field(key, label, kind, 0, 0, 0,
                        required(row.get("options"), presetKey + " 的 " + key + " 必须给出待排项"), List.of(), List.of(), null);
                case BIN_MAP -> new Field(key, label, kind, 0, 0, 0, List.of(),
                        required(row.get("items"), presetKey + " 的 " + key + " 必须给出待分类项"),
                        required(row.get("bins"), presetKey + " 的 " + key + " 必须给出分类"), null);
            });
        }
        // 文本字段必须有长度上限;否则等于把 64 KiB 预算交给一个输入框。
        for (Field field : fields) {
            if (field.kind() == Field.Kind.TEXT && (field.maxLength() < 1 || field.maxLength() > 200)) {
                throw new IllegalStateException(presetKey + " 的文本字段 " + field.key() + " maxLength 须为 1 至 200");
            }
        }
        return List.copyOf(fields);
    }

    private static List<String> required(JsonNode rows, String message) {
        if (rows == null || !rows.isArray() || rows.size() < 2 || rows.size() > 12) {
            throw new IllegalStateException(message);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode row : rows) {
            String value = row == null ? "" : row.asText("");
            if (value.isEmpty() || values.contains(value)) throw new IllegalStateException(message);
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static CityBinding readCityBinding(JsonNode node, String presetKey) {
        if (node == null || !node.isObject()) return CityBinding.NONE;
        node.fieldNames().forEachRemaining(name -> {
            if (!CITY_BINDING_FIELDS.contains(name)) throw new IllegalStateException("城市绑定含未知字段：" + name);
        });
        String appliesAt = text(node, "appliesAt", presetKey + " 的城市绑定缺少阶段", 32);
        Set<String> rebinds = strings(node.get("requiredRebinds"), presetKey + " 的城市绑定必须声明重绑项", 10);
        return new CityBinding(appliesAt, rebinds);
    }

    private static List<String> todos(JsonNode rows, String presetKey) {
        if (rows == null) return List.of();
        if (!rows.isArray() || rows.size() > 12) throw new IllegalStateException(presetKey + " 的作者待办声明不合法");
        List<String> todos = new ArrayList<>();
        for (JsonNode row : rows) {
            String value = row == null ? "" : row.asText("").trim();
            if (value.isEmpty() || value.length() > 120) throw new IllegalStateException(presetKey + " 的作者待办声明不合法");
            todos.add(value);
        }
        return List.copyOf(todos);
    }

    /**
     * 声明必须和结构对得上：声明的每个能力都是真实能力、模板里确实启用，呈现段确实启用，
     * 每个 {@code ${key}} 都有声明，每个声明都被模板引用（或落到 request 上），
     * 并且**按声明填满样例值之后**能过真实保存闸。
     *
     * <p>为什么校验的是"填满之后的样例"而不是模板原文：模板里的答案位（问答的 correct、
     * 排序的 answerOrder、分类的 answer）本来就是等作者填的，原文必然过不了答案类必填。
     * 拿填满的样例去校验，才能证明"作者按声明填完就能保存"，而不是证明"模板原文合法"。
     */
    private static void assertTemplateMatchesDeclarations(String presetKey, JsonNode template,
            List<Field> fields, Set<String> capabilities, Set<String> presentationSections) {
        try {
            new AdvancedGameConfigValidator().validateAndNormalize(
                    materialize(template, fields, sampleValues(fields)).toString());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(presetKey + " 的模板按声明填满后仍过不了保存闸：" + invalid.getMessage(), invalid);
        }
        Set<String> enabled = new LinkedHashSet<>();
        template.fields().forEachRemaining(entry -> {
            if (entry.getValue().path("enabled").asBoolean(false)
                    && !GameplaySupportMatrix.presentation(entry.getKey())) {
                enabled.add(capabilityKey(entry.getKey(), entry.getValue()));
            }
        });
        for (String key : enabled) {
            if (!capabilities.contains(key)) {
                throw new IllegalStateException(presetKey + " 的模板启用了未声明的能力：" + key);
            }
        }
        for (String key : capabilities) {
            if (!enabled.contains(key)) {
                throw new IllegalStateException(presetKey + " 声明了模板里没有启用的能力：" + key);
            }
        }
        // 呈现段：声明了就说明"这个题材要用它"，那模板里必须真的启用了，否则作者无从知道它在那儿。
        for (String section : presentationSections) {
            if (!template.path(section).path("enabled").asBoolean(false)) {
                throw new IllegalStateException(presetKey + " 声明了呈现段却未在模板中启用：" + section);
            }
        }
        Set<String> templateEnabled = new LinkedHashSet<>();
        template.fields().forEachRemaining(entry -> {
            if (entry.getValue().path("enabled").asBoolean(false)
                    && !GameplaySupportMatrix.presentation(entry.getKey())) {
                templateEnabled.add(capabilityKey(entry.getKey(), entry.getValue()));
            }
        });
        for (String key : templateEnabled) {
            if (!capabilities.contains(key)) {
                throw new IllegalStateException(presetKey + " 的模板启用了未声明的能力：" + key);
            }
        }
        for (String key : capabilities) {
            if (!templateEnabled.contains(key)) {
                throw new IllegalStateException(presetKey + " 声明了模板里没有启用的能力：" + key);
            }
        }
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> tokens = new LinkedHashSet<>();
        collectTokens(template, referenced, tokens);
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (Field field : fields) byKey.put(field.key(), field);
        for (String token : tokens) {
            Field field = byKey.get(token);
            if (field == null) throw new IllegalStateException(presetKey + " 的模板引用了未声明的作者字段：" + token);
            if (field.kind() == Field.Kind.CHOICE || field.kind() == Field.Kind.ORDER
                    || field.kind() == Field.Kind.BIN_MAP) {
                throw new IllegalStateException(presetKey + " 的作者字段 " + token + " 不能作为文本 token 使用");
            }
        }
        for (Field field : fields) {
            if (field.target() != null) continue;
            // 只有文本/数字是靠 ${key} 进模板的；CHOICE/ORDER/BIN_MAP 直接写答案位，
            // 它们在下面按"取值必须存在于模板结构里"单独校验。
            if (field.kind() == Field.Kind.CHOICE || field.kind() == Field.Kind.ORDER
                    || field.kind() == Field.Kind.BIN_MAP) continue;
            if (!referenced.contains(field.key())) {
                throw new IllegalStateException(presetKey + " 的作者字段 " + field.key() + " 没有被模板引用，作者填了也不会生效");
            }
        }
        // CHOICE / ORDER / BIN_MAP 的取值必须真的存在于模板结构里，否则作者选了也落不到答案上。
        for (Field field : fields) {
            switch (field.kind()) {
                case CHOICE -> {
                    Set<String> optionIds = new LinkedHashSet<>();
                    for (JsonNode option : template.path("qa").path("options")) optionIds.add(option.path("id").asText());
                    for (String id : field.options()) {
                        if (!optionIds.contains(id)) {
                            throw new IllegalStateException(presetKey + " 的 " + field.key()
                                    + " 引用了模板里没有的问答选项：" + id);
                        }
                    }
                }
                case ORDER -> {
                    Set<String> itemIds = new LinkedHashSet<>();
                    for (JsonNode item : template.path("sort").path("items")) itemIds.add(item.path("id").asText());
                    if (!itemIds.equals(new LinkedHashSet<>(field.options()))) {
                        throw new IllegalStateException(presetKey + " 的 " + field.key() + " 必须与排序项一一对应");
                    }
                }
                case BIN_MAP -> {
                    Set<String> itemIds = new LinkedHashSet<>();
                    for (JsonNode item : template.path("classify").path("items")) itemIds.add(item.path("id").asText());
                    Set<String> binIds = new LinkedHashSet<>();
                    for (JsonNode bin : template.path("classify").path("bins")) binIds.add(bin.path("id").asText());
                    if (!itemIds.equals(new LinkedHashSet<>(field.items())) || !binIds.equals(new LinkedHashSet<>(field.bins()))) {
                        throw new IllegalStateException(presetKey + " 的 " + field.key() + " 必须与分类项/分类一一对应");
                    }
                }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ 作者值 → 结构模板

    /**
     * 把作者值写进结构模板。**全仓只有这一处**做这件事：目录加载期用它验样例，
     * 创建草稿用它产出真实配置，所以"能加载"与"能创建"不会走成两套规则。
     *
     * <p>三种动作，没有第四种：整 token 替换、单选正确项、顺序/分类答案。
     * 替换后还残留 {@code ${...}} 直接失败——不允许把半成品交给保存闸。
     */
    public static ObjectNode materialize(JsonNode template, List<Field> fields, Map<String, String> values) {
        ObjectNode config = ((ObjectNode) template).deepCopy();
        substitute(config, values);
        for (Field field : fields) {
            String value = values.get(field.key());
            if (value == null) throw new IllegalStateException("预设参数未解析：" + field.key());
            switch (field.kind()) {
                case CHOICE -> {
                    JsonNode options = config.path("qa").path("options");
                    if (!options.isArray()) throw new IllegalStateException("预设缺少问答选项：" + field.key());
                    for (JsonNode option : options) {
                        ((ObjectNode) option).put("correct", value.equals(option.path("id").asText()));
                    }
                }
                case ORDER -> {
                    ArrayNode order = JSON.createArrayNode();
                    for (String id : value.split(",")) order.add(id);
                    ((ObjectNode) config.path("sort")).set("answerOrder", order);
                }
                case BIN_MAP -> {
                    ObjectNode answer = JSON.createObjectNode();
                    for (String pair : value.split(",")) {
                        String[] parts = pair.split(":", 2);
                        answer.put(parts[0], parts[1]);
                    }
                    ((ObjectNode) config.path("classify")).set("answer", answer);
                }
                default -> { }
            }
        }
        Matcher leftover = LEFTOVER_TOKEN.matcher(config.toString());
        if (leftover.find()) throw new IllegalStateException("预设模板还有未解析参数：" + leftover.group());
        return config;
    }

    /** 加载期样例值：只为证明"按声明填满就能过闸"，不进任何持久化路径。 */
    static Map<String, String> sampleValues(List<Field> fields) {
        Map<String, String> sample = new LinkedHashMap<>();
        for (Field field : fields) {
            sample.put(field.key(), switch (field.kind()) {
                // 样例文案要能过各段最短要求（题干、选项名都要求非空），又不夹带任何剧情内容。
                case TEXT -> "预设示例文本";
                case NUMBER -> String.valueOf(field.min());
                case CHOICE -> field.options().get(0);
                case ORDER -> String.join(",", field.options());
                case BIN_MAP -> {
                    List<String> pairs = new ArrayList<>();
                    for (String item : field.items()) pairs.add(item + ":" + field.bins().get(0));
                    yield String.join(",", pairs);
                }
            });
        }
        return sample;
    }

    private static void substitute(JsonNode node, Map<String, String> values) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (!child.isTextual()) {
                    substitute(child, values);
                    continue;
                }
                String raw = child.asText();
                Matcher whole = WHOLE_TOKEN.matcher(raw);
                if (whole.matches()) {
                    String value = values.get(whole.group(1));
                    if (value == null) throw new IllegalStateException("预设参数未解析：" + whole.group(1));
                    // 数字参数写成数字节点：写字符串过不了保存闸，计时/次数也读不到值。
                    if (value.matches("-?[0-9]{1,9}")) object.put(name, Integer.parseInt(value));
                    else object.put(name, value);
                    continue;
                }
                Matcher matcher = TOKEN.matcher(raw);
                StringBuilder out = new StringBuilder();
                while (matcher.find()) {
                    String value = values.get(matcher.group(1));
                    if (value == null) throw new IllegalStateException("预设参数未解析：" + matcher.group(1));
                    matcher.appendReplacement(out, Matcher.quoteReplacement(value));
                }
                matcher.appendTail(out);
                object.put(name, out.toString());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) substitute(child, values);
        }
    }

    /** 配置段名 → 能力 key：qa 按 mode 区分打字/选项，其余同名。 */
    static String capabilityKey(String section, JsonNode config) {
        if (!"qa".equals(section)) return section;
        String mode = config.path("mode").asText("");
        return "TYPE".equals(mode) ? "qaText" : "PICK".equals(mode) ? "qaPick" : "qaPick";
    }

    private static void requireCapabilities(String presetKey, Set<String> capabilities) {
        for (String key : capabilities) {
            JsonNode row = matrixRow(key);
            if (row == null) {
                throw new IllegalStateException(presetKey + " 声明了支持矩阵里没有的能力：" + key);
            }
            boolean usable = row.path("completionProof").asBoolean(false)
                    || GameplaySupportMatrix.addon(key)
                    || "PRESENTATION".equals(row.path("category").asText());
            if (!usable) {
                throw new IllegalStateException(presetKey + " 声明的能力不能作为玩法使用：" + key);
            }
        }
    }

    private static JsonNode matrixRow(String key) {
        JsonNode matrix = GameplaySupportMatrix.view();
        for (String bucket : List.of("types", "addons")) {
            for (JsonNode row : matrix.path(bucket)) if (key.equals(row.path("key").asText())) return row;
        }
        return null;
    }

    private static void collectTokens(JsonNode node, Set<String> referenced, Set<String> tokens) {
        if (node.isTextual()) {
            Matcher matcher = TOKEN.matcher(node.asText());
            while (matcher.find()) {
                referenced.add(matcher.group(1));
                tokens.add(matcher.group(1));
            }
            return;
        }
        for (JsonNode child : node) collectTokens(child, referenced, tokens);
    }

    private static Set<String> strings(JsonNode rows, String message, int max) {
        if (rows == null || !rows.isArray() || rows.size() > max) throw new IllegalStateException(message);
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String value = row == null ? "" : row.asText("").trim();
            if (value.isEmpty()) throw new IllegalStateException(message);
            values.add(value);
        }
        return Collections.unmodifiableSet(values);
    }

    private static String text(JsonNode node, String field, String message, int max) {
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? "" : value.asText("").trim();
        if (text.isEmpty()) throw new IllegalStateException(message);
        if (text.length() > max) throw new IllegalStateException(message + "（超长）");
        return text;
    }


    // ------------------------------------------------------------------ 对外视图

    public String catalogVersion() {
        return catalogVersion;
    }

    public List<Preset> presets() {
        return List.copyOf(presets.values());
    }

    /** 未知预设一律 400：不猜近似名、不回落到第一个。 */
    public Preset require(String presetKey) {
        Preset preset = presetKey == null ? null : presets.get(presetKey.trim());
        if (preset == null) throw new IllegalArgumentException("玩法预设不存在：" + presetKey);
        return preset;
    }

    /**
     * @param capabilities      模板实际启用的真实玩法能力（与支持矩阵同 key）
     * @param presentationSections 只做呈现、不产生完成证明的段名
     * @param fields            作者必须自己填的参数
     * @param template          服务端结构模板（含答案，**永不下发客户端**）
     */
    public record Preset(String presetKey, int version, String title, String purpose, String category,
                         int validationMethod, Set<String> capabilities, Set<String> presentationSections,
                         List<Field> fields, ObjectNode template, CityBinding cityBinding,
                         List<String> authorTodos) {
        /** 落库的来源标记：市场采用用 {@code originalTemplateId}，预设用这一个，两者互不冒充。 */
        public String sourceMark() {
            return presetKey + "@" + version;
        }

        /** 下发给作者看的部分：只有声明，没有答案、没有模板。 */
        public Map<String, Object> publicView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("presetKey", presetKey);
            view.put("version", version);
            view.put("sourceMark", sourceMark());
            view.put("title", title);
            view.put("purpose", purpose);
            view.put("category", category);
            view.put("capabilities", capabilities);
            view.put("presentationSections", presentationSections);
            List<Map<String, Object>> declared = new ArrayList<>();
            for (Field field : fields) declared.add(field.publicView());
            view.put("requiredFields", declared);
            view.put("cityBinding", cityBinding.publicView());
            view.put("authorTodos", authorTodos);
            return view;
        }
    }

    public record CityBinding(String appliesAt, Set<String> requiredRebinds) {
        static final CityBinding NONE = new CityBinding("THEME_NODE_BINDING", Set.of());

        Map<String, Object> publicView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("appliesAt", appliesAt);
            view.put("requiredRebinds", requiredRebinds);
            return view;
        }
    }

    /** 作者参数的五种有界类型：没有表达式、没有嵌套、没有默认值推断。 */
    public record Field(String key, String label, Kind kind, int maxLength, int min, int max,
                        List<String> options, List<String> items, List<String> bins, String target) {
        public enum Kind { TEXT, NUMBER, CHOICE, ORDER, BIN_MAP }

        Map<String, Object> publicView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", key);
            view.put("label", label);
            view.put("kind", kind.name().toLowerCase(Locale.ROOT));
            switch (kind) {
                case TEXT -> view.put("maxLength", maxLength);
                case NUMBER -> {
                    view.put("min", min);
                    view.put("max", max);
                }
                case CHOICE, ORDER -> view.put("options", options);
                case BIN_MAP -> {
                    view.put("items", items);
                    view.put("bins", bins);
                }
            }
            if (target != null) view.put("target", target);
            return view;
        }
    }

    /** 供测试/排错：目录原始 JSON（与 classpath 资源一致）。 */
    public static String rawCatalogJson() {
        return readResource(CATALOG_RESOURCE);
    }


}
