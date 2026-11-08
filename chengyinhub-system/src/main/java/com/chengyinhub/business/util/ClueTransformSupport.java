package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 有限谜面变换(题面呈现层)。
 *
 * <p>资料依据:竞品模板枚举 {@code 谜面变换 : Standard | Cypher | Flip | Mirror | Reverse}
 * (施工文档 v1.3.2 §19.4 与 M.5)。本类只落地其中 <b>语义可由文字本身确定</b> 的三项:
 * {@code STANDARD}(原样)、{@code REVERSE}(字素整体倒序)、{@code MIRROR}(镜像呈现指令);
 * {@code CYPHER} 与 {@code FLIP} 没有经审核的算法/参数,一律在保存与发布期拒绝并标待定,
 * 不自行选 Caesar 等加密法,也不执行任何作者提供的参数或脚本。
 *
 * <p>边界:
 * <ul>
 *   <li>只作用于<b>已公开的题面文字</b>,不碰 {@code questionAnswer} / {@code correctAnswer} /
 *       {@code answerReveal} / {@code hint1} / {@code hint2};原 QA 判定链一个字都不改。</li>
 *   <li>不做 IO、不连库、不读环境,纯函数;配置节只接受一个 {@code mode} 键,多余键视为非法,
 *       从结构上封死“作者自定义参数/脚本”。</li>
 *   <li>{@code MIRROR} 不下发镜像字形串:没有经审核的字形映射表,服务端只发指令,由各端把同一个
 *       文字层水平翻转渲染;服务端绝不预生成镜像图片冒充动态配置。</li>
 * </ul>
 */
public final class ClueTransformSupport
{
    /** 节点 advancedConfigJson 根层的配置节名。 */
    public static final String SECTION = "clueTransform";

    /** 原枚举 Standard:不改题面。 */
    public static final String MODE_STANDARD = "STANDARD";
    /** 原枚举 Mirror:镜像呈现指令,题面文字不变。 */
    public static final String MODE_MIRROR = "MIRROR";
    /** 原枚举 Reverse:按字素簇整体倒序。 */
    public static final String MODE_REVERSE = "REVERSE";

    /** 原资料枚举里存在、但当前没有经审核算法与参数的两种;保存/发布期拒绝。 */
    private static final List<String> PENDING_MODES = Collections.unmodifiableList(
            Arrays.asList("CYPHER", "FLIP"));

    private static final String MODE_KEY = "mode";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 零宽连接符 U+200D:属于前一个字素,并让后一个字素并入同一簇。 */
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private ClueTransformSupport()
    {
    }

    /**
     * 校验配置节并返回规范化模式。缺省(null / 空节 / {@code mode} 缺失)一律视为 {@link #MODE_STANDARD},
     * 保证老配置与不配置的作者行为完全不变。
     *
     * @throws IllegalArgumentException 非对象、含 mode 以外的键、未知模式、或待定模式
     */
    public static String mode(JsonNode section)
    {
        if (section == null || section.isNull() || section.isMissingNode())
        {
            return MODE_STANDARD;
        }
        if (!section.isObject())
        {
            throw new IllegalArgumentException("谜面变换配置必须是对象");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = section.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!MODE_KEY.equals(field.getKey()))
            {
                throw new IllegalArgumentException("谜面变换只接受 mode,不接受作者自定义参数或脚本");
            }
        }
        JsonNode raw = section.get(MODE_KEY);
        if (raw == null || raw.isNull())
        {
            return MODE_STANDARD;
        }
        if (!raw.isTextual())
        {
            throw new IllegalArgumentException("谜面变换 mode 必须是文本");
        }
        String value = raw.asText().trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty())
        {
            return MODE_STANDARD;
        }
        if (PENDING_MODES.contains(value))
        {
            throw new IllegalArgumentException("谜面变换 " + value + " 尚无经审核的算法与参数,当前不可发布");
        }
        if (MODE_STANDARD.equals(value) || MODE_MIRROR.equals(value) || MODE_REVERSE.equals(value))
        {
            return value;
        }
        throw new IllegalArgumentException("谜面变换 mode 不支持:" + raw.asText());
    }

    /** 保存与发布期入口:只做结构校验,与 {@link #mode(JsonNode)} 同源。 */
    public static void validate(JsonNode section)
    {
        mode(section);
    }

    /**
     * 发布/保存期可用性校验:选了非 STANDARD 模式,题面必须真的有文字可呈现。
     *
     * @throws IllegalArgumentException 题面为空
     */
    public static void requireApplicable(JsonNode section, String publicPrompt)
    {
        if (!MODE_STANDARD.equals(mode(section))
                && (publicPrompt == null || publicPrompt.trim().isEmpty()))
        {
            throw new IllegalArgumentException("谜面变换需要节点题面文字,当前题面为空");
        }
    }

    /** 是否要求客户端把文字层水平翻转渲染。 */
    public static boolean requiresMirroredRendering(JsonNode section)
    {
        return MODE_MIRROR.equals(mode(section));
    }

    /**
     * 从整段节点玩法配置里取本配置节;坏 JSON / 缺失 / 非对象一律返回 null(当没配),
     * 结构问题由保存与发布期的 {@link #validate(JsonNode)} 负责报错,读取侧不抛。
     */
    public static JsonNode sectionOf(String advancedConfigJson)
    {
        if (advancedConfigJson == null || advancedConfigJson.trim().isEmpty())
        {
            return null;
        }
        try
        {
            JsonNode config = MAPPER.readTree(advancedConfigJson);
            return sectionOf(config);
        }
        catch (Exception unusableConfig)
        {
            return null;
        }
    }

    /** 从一个已解析的配置对象里取本配置节;缺失或 null 返回 null。 */
    public static JsonNode sectionOf(JsonNode advancedConfig)
    {
        if (advancedConfig == null || !advancedConfig.isObject())
        {
            return null;
        }
        JsonNode section = advancedConfig.get(SECTION);
        return section == null || section.isNull() ? null : section;
    }

    /**
     * 取玩家应看到的题面。{@code STANDARD} 原样返回;{@code MIRROR} 也原样返回文字(翻转在呈现层);
     * {@code REVERSE} 返回按字素簇整体倒序后的文字。
     *
     * <p>调用方只能传<b>已公开的题面</b>(模板 {@code questionName} / 高级玩法 prompt),
     * 绝不能传答案、揭示文案或未解锁提示。
     */
    public static String renderPrompt(String publicPrompt, JsonNode section)
    {
        String text = publicPrompt == null ? "" : publicPrompt;
        return MODE_REVERSE.equals(mode(section)) ? reverseGraphemes(text) : text;
    }

    /**
     * 玩家侧投影:{@code STANDARD} 返回 null(不下发,老客户端零感知),其余返回
     * {@code {"mode":"MIRROR"|"REVERSE"}}。只含模式名,天然不含答案与作者参数。
     */
    public static ObjectNode publicView(JsonNode section)
    {
        String value = mode(section);
        if (MODE_STANDARD.equals(value))
        {
            return null;
        }
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put(MODE_KEY, value);
        return view;
    }

    /**
     * 按字素簇整体倒序。
     *
     * <p>规则(写死,不给作者开关):
     * <ul>
     *   <li>单位是<b>字素簇</b>而不是 UTF-16 char,代理对、组合附加符、变体选择符、肤色修饰、
     *       ZWJ 连字、区域指示符旗标都整体搬运,倒序后不会拆坏字符。</li>
     *   <li>空格与标点和其它字素一样参与倒序,不做“标点原位保留”或“词序保留”。</li>
     *   <li>换行不是附加符,单独成簇,随整体一起翻转。</li>
     * </ul>
     */
    public static String reverseGraphemes(String text)
    {
        if (text == null || text.length() < 2)
        {
            return text;
        }
        List<String> clusters = clusters(text);
        Collections.reverse(clusters);
        StringBuilder out = new StringBuilder(text.length());
        for (String cluster : clusters)
        {
            out.append(cluster);
        }
        return out.toString();
    }

    /** 切分字素簇;顺序遍历一次,逐簇落字符串。 */
    private static List<String> clusters(String text)
    {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean joinedByZwj = false;
        int regionalIndicators = 0;
        int index = 0;
        while (index < text.length())
        {
            int codePoint = text.codePointAt(index);
            boolean attach;
            if (current.length() == 0)
            {
                attach = false;
            }
            else if (joinedByZwj)
            {
                attach = true;
            }
            else if (codePoint == ZERO_WIDTH_JOINER)
            {
                // 连接符本身属于前一个字素,不能单独成簇。
                attach = true;
            }
            else if (isExtender(codePoint))
            {
                attach = true;
            }
            else if (isRegionalIndicator(codePoint))
            {
                attach = regionalIndicators == 1;
            }
            else
            {
                attach = false;
            }
            if (!attach && current.length() > 0)
            {
                out.add(current.toString());
                current.setLength(0);
                regionalIndicators = 0;
            }
            current.appendCodePoint(codePoint);
            if (isRegionalIndicator(codePoint))
            {
                regionalIndicators++;
            }
            joinedByZwj = codePoint == ZERO_WIDTH_JOINER;
            index += Character.charCount(codePoint);
        }
        if (current.length() > 0)
        {
            out.add(current.toString());
        }
        return out;
    }

    /** 组合附加符 / 变体选择符 / 肤色修饰:都必须贴在前一个基字上。 */
    private static boolean isExtender(int codePoint)
    {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK)
        {
            return true;
        }
        if (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
        {
            return true;
        }
        if (codePoint >= 0xE0100 && codePoint <= 0xE01EF)
        {
            return true;
        }
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isRegionalIndicator(int codePoint)
    {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }
}
