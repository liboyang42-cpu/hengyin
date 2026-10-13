package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 章节 blocks 读模型：解析已物化内容，或为未物化章节生成兼容块。
 * 本类只负责读取与旧渲染等价性，不校验或写回块结构。
 */
public final class ChapterBlockReadSupport
{
    static final String KEY_TYPE = "type";
    static final String KEY_CONTENT = "content";
    static final String KEY_NODE_ID = "nodeId";
    static final String TYPE_TEXT = "text";
    static final String TYPE_NODE = "node";
    /**
     * 梦块(预制人生 §3.2):一组图 + 每张一句话。读侧不做解释、原样透传;
     * 它**不是** node 块,所以既不截断 description 投影,也不参与节点归组。
     */
    static final String TYPE_DREAM = "dream";

    private static final Logger LOG = LoggerFactory.getLogger(ChapterBlockReadSupport.class);
    private static final TypeReference<List<Object>> BLOCK_LIST_TYPE = new TypeReference<List<Object>>() { };
    private static final ObjectReader BLOCK_READER = new ObjectMapper()
            .readerFor(BLOCK_LIST_TYPE)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ChapterBlockReadSupport()
    {
    }

    /**
     * 已物化 blocks 原样解析；列为空或解析失败时，按 description + sortId 节点顺序现算。
     */
    public static List<Object> resolve(CmsTopicChapter chapter, List<CmsTopicNode> chapterNodes)
    {
        String stored = chapter.getBlocksJson();
        if (stored != null && !stored.trim().isEmpty())
        {
            try
            {
                List<Object> parsed = BLOCK_READER.readValue(stored);
                if (parsed != null)
                {
                    return parsed;
                }
            }
            catch (IOException | RuntimeException ignored)
            {
                warnInvalidBlocks(chapter.getId());
                return legacyFallback(chapter.getDescription(), chapterNodes);
            }
            warnInvalidBlocks(chapter.getId());
        }
        return legacyFallback(chapter.getDescription(), chapterNodes);
    }

    /** 从主题全量节点中筛出本章节点后解析，避免 Controller 重复承载归组规则。 */
    public static List<Object> resolveFromTopicNodes(CmsTopicChapter chapter, List<CmsTopicNode> topicNodes)
    {
        List<CmsTopicNode> chapterNodes = new ArrayList<>();
        if (topicNodes != null)
        {
            for (CmsTopicNode node : topicNodes)
            {
                if (chapter.getId() != null && chapter.getId().equals(node.getChapterId()))
                {
                    chapterNodes.add(node);
                }
            }
        }
        return resolve(chapter, chapterNodes);
    }

    /**
     * 新数据以 productType 为真源；存量 NULL 按最早一张有 mode 的票回落，无票则维持旧版线性默认值。
     */
    public static boolean shouldResolveBlocks(Integer productType, List<OmsTicket> tickets)
    {
        if (productType != null)
        {
            return productType == ProductType.ORIENTEERING
                    || productType == ProductType.FREE_EXPLORE;
        }
        if (tickets != null)
        {
            for (OmsTicket ticket : tickets)
            {
                if (ticket != null && ticket.getMode() != null)
                {
                    return ticket.getMode() == ProductType.ORIENTEERING
                            || ticket.getMode() == ProductType.FREE_EXPLORE;
                }
            }
        }
        return true;
    }

    /**
     * 玩家读模型只下发到首个未完成节点块（含该节点块），其后的正文与媒体不出服务端。
     *
     * <p>块类型在这里不做白名单 —— 已物化的块(含 dream)原样透传,截断点只由 node 块决定。
     * 写入侧的类型白名单在 {@code ChapterFlowCompiler};这里多拦一道只会让新块型静默消失。
     */
    public static List<Object> projectForPlayer(List<Object> blocks, Set<Long> completedNodeIds)
    {
        return projectForPlayer(blocks, completedNodeIds, java.util.Collections.<String>emptySet());
    }

    /**
     * 同上,并按玩家本局的标签过滤条件块(2026-09-24「选择有回响」):带 {@code when:{op:HAS_TAG,value}}
     * 的正文 / 内心声音,只有本局有这个标签才下发。没拿到本局(未开局 / 公开详情)= 空集合 = 条件块一律不下发 ——
     * 条件没满足的那句话本来就不该被看见,宁可少一句也不提前剧透。
     */
    public static List<Object> projectForPlayer(List<Object> blocks, Set<Long> completedNodeIds, Set<String> tags)
    {
        List<Object> projected = new ArrayList<>();
        if (blocks == null)
        {
            return projected;
        }
        for (Object item : blocks)
        {
            if (item instanceof Map && ((Map<?, ?>) item).get("beat") == null && ((Map<?, ?>) item).get("when") instanceof Map)
            {
                Object tag = ((Map<?, ?>) ((Map<?, ?>) item).get("when")).get("value");
                if (tags == null || tag == null || !tags.contains(String.valueOf(tag))) continue;
            }
            projected.add(item);
            if (!(item instanceof Map))
            {
                continue;
            }
            Map<?, ?> block = (Map<?, ?>) item;
            if (!TYPE_NODE.equals(block.get(KEY_TYPE)))
            {
                continue;
            }
            Long nodeId = toLong(block.get(KEY_NODE_ID));
            if (nodeId == null || completedNodeIds == null || !completedNodeIds.contains(nodeId))
            {
                break;
            }
        }
        return projected;
    }

    private static Long toLong(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        try
        {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    /** V1 故事流只覆盖 required=1 的章节；可选章继续按旧路径读取。 */
    public static boolean isRequiredChapter(CmsTopicChapter chapter)
    {
        return chapter != null && Integer.valueOf(1).equals(chapter.getRequired());
    }

    /** 文本块的段落语义必须与旧玩家端按连续换行拆段、trim、去空段一致。 */
    static List<String> textParagraphs(List<?> blocks)
    {
        List<String> paragraphs = new ArrayList<>();
        if (blocks == null)
        {
            return paragraphs;
        }
        for (Object item : blocks)
        {
            if (!(item instanceof Map))
            {
                continue;
            }
            Map<?, ?> block = (Map<?, ?>) item;
            if (!TYPE_TEXT.equals(block.get(KEY_TYPE)))
            {
                continue;
            }
            Object content = block.get(KEY_CONTENT);
            String text = content == null ? "" : String.valueOf(content);
            for (String part : text.split("\\n+"))
            {
                String value = part.trim();
                if (!value.isEmpty())
                {
                    paragraphs.add(value);
                }
            }
        }
        return paragraphs;
    }

    private static List<Object> legacyFallback(String description, List<CmsTopicNode> nodes)
    {
        List<Object> blocks = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put(KEY_TYPE, TYPE_TEXT);
        text.put(KEY_CONTENT, description);
        blocks.add(text);

        List<CmsTopicNode> ordered = new ArrayList<>();
        if (nodes != null)
        {
            ordered.addAll(nodes);
        }
        ordered.sort(ChapterBlockReadSupport::compareNodes);
        for (CmsTopicNode node : ordered)
        {
            Map<String, Object> nodeBlock = new LinkedHashMap<>();
            nodeBlock.put(KEY_TYPE, TYPE_NODE);
            nodeBlock.put(KEY_NODE_ID, node.getId());
            blocks.add(nodeBlock);
        }
        return blocks;
    }

    private static int compareNodes(CmsTopicNode left, CmsTopicNode right)
    {
        int bySort = compareNullable(left.getSortId(), right.getSortId());
        return bySort != 0 ? bySort : compareNullable(left.getId(), right.getId());
    }

    /** null sortId 没有可证明的顺序，固定排末尾；同值再按 id，避免数据库返回顺序摇摆。 */
    private static int compareNullable(Long left, Long right)
    {
        if (left == right)
        {
            return 0;
        }
        if (left == null)
        {
            return 1;
        }
        if (right == null)
        {
            return -1;
        }
        return left.compareTo(right);
    }

    private static void warnInvalidBlocks(Long chapterId)
    {
        LOG.warn("章节 blocks 解析失败，按未物化数据兜底: chapterId={}", chapterId);
    }
}
