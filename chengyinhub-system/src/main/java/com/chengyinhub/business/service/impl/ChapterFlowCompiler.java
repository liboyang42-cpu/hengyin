package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.service.support.ChapterNarrativeBlocks;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import com.chengyinhub.business.util.ChapterAtmospherePreset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 章节故事流的纯编译阶段：校验写契约并派生章节顺序、节点全局顺序和 description 投影。
 * 数据库 id 只有插入后才存在，因此最终的 nodeIndex → nodeId 翻译由编译结果完成。
 */
final class ChapterFlowCompiler
{
    static final int SCHEMA_VERSION = 1;
    static final int MAX_BLOCKS_PER_CHAPTER = 200;
    static final int MAX_TEXT_LENGTH = 5000;
    static final int MAX_DESCRIPTION_LENGTH = 8000;
    static final int MAX_SERIALIZED_BYTES = 64 * 1024;
    static final int MAX_URL_LENGTH = 500;

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_NODE = "node";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_AUDIO = "audio";
    /** 梦:一组图 + 每张一句话(预制人生 §3.2)。它是章内块,不改变 description 的首节点截断。 */
    private static final String TYPE_DREAM = "dream";
    private static final String TYPE_MOOD = "mood";
    private static final String TYPE_THOUGHT = "thought";
    /** 内心声音(《预制人生》参照极乐迪斯科):一句话 + 说话的是哪个声音。表现块,不进 description。 */
    private static final String TYPE_VOICE = "voice";
    /** 越往后越不正常:从这一段起的异常档位。表现块,与 mood 同一条口径。 */
    private static final String TYPE_ODD = "odd";
    static final java.util.Set<String> VOICES = new java.util.HashSet<>(java.util.Arrays.asList("规矩", "窗外", "心", "精密", "咔哒"));
    static final int MAX_VOICE_LENGTH = 200;
    /** 条件正文 / 条件内心声音(2026-09-24「选择有回响」):when = 一条 HAS_TAG,只认选项标签与伏笔应验。 */
    private static final Pattern WHEN_TAG = Pattern.compile("^(tag\\.[a-z][a-z0-9_]{0,47}|thought\\.[a-z][a-z0-9_]{0,47}\\.done)$");
    /** 反转(终章):读到这里,整章正文翻成对话框里「我」发出去的消息;content 是对面回的话(按行一条),who 是对面的名字。 */
    private static final String TYPE_REVEAL = "reveal";
    static final int MAX_REVEAL_LENGTH = 500;
    static final int MAX_REVEAL_WHO = 20;
    /** 念头 key 的格式与 JourneyRulesValidator.THOUGHT_KEY 一致;存在性这层校验不了(见下)。 */
    private static final Pattern THOUGHT_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final int MIN_DREAM_IMAGES = 1;
    private static final int MAX_DREAM_IMAGES = 6;
    private static final int MAX_DREAM_LINE = 80;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Object>> STORED_BLOCKS_TYPE = new TypeReference<List<Object>>() { };

    private ChapterFlowCompiler()
    {
    }

    /** 正文 / 内心声音上的条件:缺省 = 人人都看得到;给了就只能是一条 HAS_TAG(选项标签或伏笔应验)。 */
    private static void validatePlainWhen(ChapterBlockDTO block, int chapterNumber)
    {
        com.fasterxml.jackson.databind.JsonNode when = block.getWhen();
        if (when == null || when.isNull()) return;
        if (!when.isObject() || !"HAS_TAG".equals(when.path("op").asText())
                || !WHEN_TAG.matcher(when.path("value").asText("")).matches())
        {
            throw invalid(chapterNumber, "条件只能是「选过某个选项」或「某个伏笔应验了」");
        }
    }

    static CompiledFlow compileChapterFlow(List<ChapterDTO> chapters)
    {
        if (chapters == null || chapters.isEmpty())
        {
            return new CompiledFlow(Collections.<CompiledChapter>emptyList());
        }

        List<CompiledChapter> compiled = new ArrayList<>(chapters.size());
        long nextNodeSortId = 1L;
        int endingChapters = 0;
        int endingFallbacks = 0;
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++)
        {
            ChapterDTO chapter = chapters.get(chapterIndex);
            int chapterNumber = chapterIndex + 1;
            if (chapter == null)
            {
                throw invalid(chapterNumber, "章节不能为空");
            }
            if (Boolean.TRUE.equals(chapter.getOpening())) {
                if (chapterIndex != 0) throw invalid(chapterNumber, "开局只能位于第一章之前，且只能有一个");
                if (Integer.valueOf(1).equals(chapter.getRecruitEnabled()))
                    throw invalid(chapterNumber, "开局不能开放商家承接");
            }
            // 结局章:玩家走到最后按条件挑一章播;它不是路线的一站,所以不许放站点、不许招商、不许兼开局
            com.fasterxml.jackson.databind.JsonNode ending = chapter.getEnding();
            if (ending != null && !ending.isNull()) {
                if (!ending.isObject()) throw invalid(chapterNumber, "结局设置格式不对");
                if (Boolean.TRUE.equals(chapter.getOpening())) throw invalid(chapterNumber, "开局不能同时是结局");
                if (Integer.valueOf(1).equals(chapter.getRecruitEnabled()))
                    throw invalid(chapterNumber, "结局章不能开放商家承接");
                if (chapter.getNodes() != null && !chapter.getNodes().isEmpty())
                    throw invalid(chapterNumber, "结局章里不能放站点或玩法");
                com.fasterxml.jackson.databind.JsonNode when = ending.get("when");
                if (ending.path("fallback").asBoolean(false)) endingFallbacks++;
                else if (when == null || !when.isArray() || when.size() == 0)
                    throw invalid(chapterNumber, "结局章要选一个触发条件,或者设为兜底");
                endingChapters++;
            }
            if (!Integer.valueOf(SCHEMA_VERSION).equals(chapter.getSchemaVersion()))
            {
                throw invalid(chapterNumber, "schemaVersion 必须为 1");
            }
            if (chapter.getBlocks() == null)
            {
                throw invalid(chapterNumber, "blocks 不能为空");
            }
            if (chapter.getBlocks().size() > MAX_BLOCKS_PER_CHAPTER)
            {
                throw invalid(chapterNumber, "块数超过 200");
            }
            if (chapter.getRequired() != null && !Integer.valueOf(1).equals(chapter.getRequired()))
            {
                throw invalid(chapterNumber, "故事流章节 required 必须为 1");
            }

            List<NodeDTO> nodes = chapter.getNodes() == null
                    ? Collections.<NodeDTO>emptyList() : chapter.getNodes();
            int[] references = new int[nodes.size()];
            Long[] nodeSortIds = new Long[nodes.size()];
            // 一章之内同一个念头只能领一次:领第二次没有意义,进度还会被重置。
            Set<String> thoughtKeys = new HashSet<String>();
            boolean beforeFirstNode = true;
            boolean hasProjectedText = false;
            StringBuilder description = new StringBuilder();
            Set<String> blockKeys = new HashSet<>();

            for (int blockIndex = 0; blockIndex < chapter.getBlocks().size(); blockIndex++)
            {
                ChapterBlockDTO block = chapter.getBlocks().get(blockIndex);
                if (block == null)
                {
                    throw invalid(chapterNumber, "第 " + (blockIndex + 1) + " 个块不能为空");
                }
                if (block.getKey() == null || block.getKey().trim().isEmpty()) {
                    block.setKey(UUID.randomUUID().toString());
                }
                if (!block.getKey().matches("[A-Za-z0-9_-]{1,64}") || !blockKeys.add(block.getKey())) {
                    throw invalid(chapterNumber, "内容块标识无效或重复");
                }
                if (block.getLocationRequired() != null && !TYPE_NODE.equals(block.getType()))
                {
                    throw invalid(chapterNumber, "只有 node 块可以配置是否需要到达地点");
                }
                if (TYPE_TEXT.equals(block.getType()))
                {
                    if (block.getWho() != null && block.getWho().trim().length() > 20)
                        throw invalid(chapterNumber, "角色名称不能超过 20 字");
                    if (block.getLevel() != null && (block.getLevel() < 0 || block.getLevel() > 3))
                        throw invalid(chapterNumber, "文字效果只允许 0 至 3");
                    boolean narrative = ChapterNarrativeBlocks.isNarrativeBlock(block);
                    if (block.getNodeIndex() != null && !narrative)
                    {
                        throw invalid(chapterNumber, "text 块不能携带 nodeIndex");
                    }
                    int length = block.getContent() == null ? 0 : block.getContent().length();
                    if (length > MAX_TEXT_LENGTH)
                    {
                        throw invalid(chapterNumber, "text 块正文超过 5000 字符");
                    }
                    if (!narrative) validatePlainWhen(block, chapterNumber);
                    // R18:叙事块是站点正文,挂在 node 块前后由玩家读;不进章节摘要投影。
                    // 条件正文也不进:摘要是给没开局的人看的,那时谁都没选过任何选项。
                    if (!narrative && beforeFirstNode && block.getWhen() == null)
                    {
                        if (hasProjectedText)
                        {
                            description.append('\n');
                        }
                        if (block.getContent() != null)
                        {
                            description.append(block.getContent());
                        }
                        hasProjectedText = true;
                    }
                }
                else if (TYPE_NODE.equals(block.getType()))
                {
                    beforeFirstNode = false;
                    if (block.getContent() != null)
                    {
                        throw invalid(chapterNumber, "node 块不能携带 content");
                    }
                    Integer nodeIndex = block.getNodeIndex();
                    if (nodeIndex == null || nodeIndex < 0 || nodeIndex >= nodes.size())
                    {
                        throw invalid(chapterNumber, "nodeIndex 越界: " + nodeIndex);
                    }
                    if (Boolean.TRUE.equals(chapter.getOpening()) && !Boolean.FALSE.equals(block.getLocationRequired()))
                        throw invalid(chapterNumber, "开局玩法不需要到达地点，请选择故事玩法");
                    if (Boolean.FALSE.equals(block.getLocationRequired()))
                    {
                        NodeDTO game = nodes.get(nodeIndex);
                        if (game == null || game.getTemplateId() == null || game.getTemplateId() <= 0L)
                        {
                            throw invalid(chapterNumber, "故事玩法必须绑定模板");
                        }
                        if ((game.getLongitude() != null && !game.getLongitude().trim().isEmpty())
                                || (game.getLatitude() != null && !game.getLatitude().trim().isEmpty()))
                        {
                            throw invalid(chapterNumber, "故事玩法不能携带地点坐标，请添加为地点");
                        }
                    }
                    references[nodeIndex]++;
                    if (references[nodeIndex] > 1)
                    {
                        throw invalid(chapterNumber, "nodeIndex 重复引用: " + nodeIndex);
                    }
                    nodeSortIds[nodeIndex] = nextNodeSortId++;
                }
                else if (TYPE_IMAGE.equals(block.getType()) || TYPE_AUDIO.equals(block.getType()))
                {
                    // 媒体块既不投影进 description，也不改变「首个节点之前」的判定：
                    // description 是纯文本投影，塞一张图的 url 进去只会污染列表页摘要。
                    if (block.getContent() != null)
                    {
                        throw invalid(chapterNumber, block.getType() + " 块不能携带 content");
                    }
                    if (block.getNodeIndex() != null)
                    {
                        throw invalid(chapterNumber, block.getType() + " 块不能携带 nodeIndex");
                    }
                    String url = block.getUrl();
                    if (url == null || url.trim().isEmpty())
                    {
                        throw invalid(chapterNumber, block.getType() + " 块缺少 url");
                    }
                    if (url.length() > MAX_URL_LENGTH)
                    {
                        throw invalid(chapterNumber, block.getType() + " 块 url 超过 " + MAX_URL_LENGTH + " 字符");
                    }
                }
                else if (TYPE_DREAM.equals(block.getType()))
                {
                    /* 梦块:一组图 + 每张一句话。它既不进 description,也不改变「首个节点之前」
                       的判定(和 image / audio 同一条口径)—— description 投影仍在首个 node 块处停。 */
                    if (block.getContent() != null)
                    {
                        throw invalid(chapterNumber, "dream 块不能携带 content");
                    }
                    if (block.getNodeIndex() != null)
                    {
                        throw invalid(chapterNumber, "dream 块不能携带 nodeIndex");
                    }
                    if (block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "dream 块不能携带 url");
                    }
                    if (block.getTitle() != null && block.getTitle().trim().length() > 20)
                    {
                        throw invalid(chapterNumber, "相册名称不能超过 20 字");
                    }
                    List<ChapterBlockDTO.DreamImageDTO> images = block.getImages();
                    if (images == null || images.size() < MIN_DREAM_IMAGES || images.size() > MAX_DREAM_IMAGES)
                    {
                        throw invalid(chapterNumber, "dream 块须配 " + MIN_DREAM_IMAGES + " 至 "
                                + MAX_DREAM_IMAGES + " 张图");
                    }
                    for (ChapterBlockDTO.DreamImageDTO image : images)
                    {
                        if (image == null || image.getUrl() == null || image.getUrl().trim().isEmpty())
                        {
                            throw invalid(chapterNumber, "dream 块的每张图都要有 url");
                        }
                        if (image.getUrl().length() > MAX_URL_LENGTH)
                        {
                            throw invalid(chapterNumber, "dream 块图片 url 超过 " + MAX_URL_LENGTH + " 字符");
                        }
                        if (image.getLine() != null && image.getLine().length() > MAX_DREAM_LINE)
                        {
                            throw invalid(chapterNumber, "dream 块每张图的一句话不能超过 " + MAX_DREAM_LINE + " 字");
                        }
                    }
                }
                else if (TYPE_MOOD.equals(block.getType()))
                {
                    /* 氛围块:正文流里的表现标记,滚到它就把整屏基调切过去。它不是状态、不参与
                       任何权威结算,所以既不投影进 description,也不改变「首个节点之前」的截断
                       —— 与 image / audio / dream 同一条口径。 */
                    if (block.getContent() != null)
                    {
                        throw invalid(chapterNumber, "mood 块不能携带 content");
                    }
                    if (block.getNodeIndex() != null)
                    {
                        throw invalid(chapterNumber, "mood 块不能携带 nodeIndex");
                    }
                    if (block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "mood 块不能携带 url");
                    }
                    // 章节级「没配 = 默认黑」是合理的,但块级不行:作者插了一个氛围块却不选颜色
                    // 就是个空壳,读到这儿什么也不会发生。
                    if (block.getMood() == null || block.getMood().trim().isEmpty())
                    {
                        throw invalid(chapterNumber, "氛围块必须选一个章节配色");
                    }
                    // 配色预设复用章节那套(2026-09-06 用户拍板的黑/蓝/红/黄/白纯色),
                    // 不另立一份名单 —— mood 块只是把「整章一个颜色」变成「段落级可切换」。
                    try
                    {
                        ChapterAtmospherePreset.requireValid(block.getMood());
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw invalid(chapterNumber, "氛围只能用章节配色预设: " + block.getMood());
                    }
                }
                else if (TYPE_THOUGHT.equals(block.getType()))
                {
                    /* 念头块:在这一段「得到一个念头」。念头的名字、一句话与步数阈值住在
                       journeyRules,这里只放引用 —— 同一条数据不要两个真源。
                       ⚠️ 只能校验格式:编译器拿得到的只有章节流,拿不到 journeyRules,
                          所以「引用了不存在的念头」由发布闸兜,不在这一层。 */
                    if (block.getContent() != null)
                    {
                        throw invalid(chapterNumber, "thought 块不能携带 content");
                    }
                    if (block.getNodeIndex() != null)
                    {
                        throw invalid(chapterNumber, "thought 块不能携带 nodeIndex");
                    }
                    if (block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "thought 块不能携带 url");
                    }
                    String thoughtKey = block.getThoughtKey() == null ? "" : block.getThoughtKey().trim();
                    if (!THOUGHT_KEY.matcher(thoughtKey).matches())
                    {
                        throw invalid(chapterNumber, "念头块要指明是哪个念头:" + block.getThoughtKey());
                    }
                    if (!thoughtKeys.add(thoughtKey))
                    {
                        throw invalid(chapterNumber, "同一个念头在本章出现了两次:" + thoughtKey
                                + ";领第二次没有意义,进度也会被重置");
                    }
                }
                else if (TYPE_VOICE.equals(block.getType()))
                {
                    if (block.getNodeIndex() != null || block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "内心声音块只能带说话的声音和一句话");
                    }
                    if (block.getWho() == null || !VOICES.contains(block.getWho()))
                    {
                        throw invalid(chapterNumber, "内心声音只能是 规矩 / 窗外 / 心 / 精密 / 咔哒");
                    }
                    validatePlainWhen(block, chapterNumber);
                    String line = block.getContent() == null ? "" : block.getContent().trim();
                    if (line.isEmpty() || line.length() > MAX_VOICE_LENGTH)
                    {
                        throw invalid(chapterNumber, "内心声音要写一句话,最多 " + MAX_VOICE_LENGTH + " 字");
                    }
                }
                else if (TYPE_ODD.equals(block.getType()))
                {
                    if (block.getContent() != null || block.getNodeIndex() != null || block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "异常块只能带档位");
                    }
                    if (block.getLevel() == null || block.getLevel() < 0 || block.getLevel() > 3)
                    {
                        throw invalid(chapterNumber, "异常档位只能是 0 至 3");
                    }
                }
                else if (TYPE_REVEAL.equals(block.getType()))
                {
                    if (block.getNodeIndex() != null || block.getUrl() != null)
                    {
                        throw invalid(chapterNumber, "反转块只能带对面的名字和回话");
                    }
                    String said = block.getContent() == null ? "" : block.getContent().trim();
                    if (said.isEmpty() || said.length() > MAX_REVEAL_LENGTH)
                    {
                        throw invalid(chapterNumber, "反转块要写对面回的话,最多 " + MAX_REVEAL_LENGTH + " 字");
                    }
                    if (block.getWho() != null && block.getWho().trim().length() > MAX_REVEAL_WHO)
                    {
                        throw invalid(chapterNumber, "对面的名字最多 " + MAX_REVEAL_WHO + " 字");
                    }
                }
                else
                {
                    throw invalid(chapterNumber, "块类型只允许 text / node / image / audio / dream / mood / thought / voice / odd / reveal");
                }
            }

            for (int nodeIndex = 0; nodeIndex < references.length; nodeIndex++)
            {
                if (references[nodeIndex] == 0)
                {
                    throw invalid(chapterNumber, "节点未被 blocks 引用: nodeIndex=" + nodeIndex);
                }
            }
            // R18:叙事块(beat)的形状、位唯一、条数、问答配对、npcId 一致与前后位置一并在这里打回。
            ChapterNarrativeBlocks.validateChapter(chapter.getBlocks(), chapterNumber, nodes.size());
            if (description.length() > MAX_DESCRIPTION_LENGTH)
            {
                throw invalid(chapterNumber, "剧情投影超过 8000 字符，请缩短首个节点前的剧情");
            }
            compiled.add(new CompiledChapter(chapter, chapterNumber, description.toString(), nodeSortIds));
        }
        if (endingChapters > 0 && endingFallbacks != 1)
            throw new ChapterFlowValidationException("结局章里必须恰好有一章是兜底(其它结局都不中时走它)");
        return new CompiledFlow(compiled);
    }

    /** 复制已物化章节时，物理 nodeId 必须绑定到副本刚插入的节点，不能跨主题沿用源 id。 */
    static String remapMaterializedNodeIds(String blocksJson, Map<Long, Long> nodeIdMap, Long sourceChapterId)
    {
        if (blocksJson == null || blocksJson.trim().isEmpty())
        {
            return blocksJson;
        }
        try
        {
            List<Object> stored = JSON.readValue(blocksJson, STORED_BLOCKS_TYPE);
            if (stored == null)
            {
                throw new IllegalStateException("复制主题失败: 源章节 blocks 不是数组, chapterId=" + sourceChapterId);
            }
            for (Object item : stored)
            {
                if (!(item instanceof Map))
                {
                    throw new IllegalStateException("复制主题失败: 源章节 blocks 含非法块, chapterId=" + sourceChapterId);
                }
                Map<?, ?> block = (Map<?, ?>) item;
                Object rawNodeId = block.get("nodeId");
                boolean nodeBlock = TYPE_NODE.equals(block.get("type"));
                if (rawNodeId == null)
                {
                    if (nodeBlock)
                    {
                        throw new IllegalStateException("复制主题失败: node 块缺少 nodeId, chapterId=" + sourceChapterId);
                    }
                    continue;
                }
                if (!(rawNodeId instanceof Number))
                {
                    throw new IllegalStateException("复制主题失败: 块的 nodeId 不是数字, chapterId="
                            + sourceChapterId + ", nodeId=" + rawNodeId);
                }
                Long copiedNodeId = nodeIdMap.get(((Number) rawNodeId).longValue());
                if (copiedNodeId == null || copiedNodeId <= 0L)
                {
                    throw new IllegalStateException("复制主题失败: nodeId 不属于源主题节点, chapterId="
                            + sourceChapterId + ", nodeId=" + rawNodeId);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> writable = (Map<String, Object>) block;
                writable.put("nodeId", copiedNodeId);
            }
            return JSON.writeValueAsString(stored);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("复制主题失败: 源章节 blocks 无法解析, chapterId=" + sourceChapterId, e);
        }
    }

    static boolean hasBlockPayload(List<ChapterDTO> chapters)
    {
        if (chapters == null)
        {
            return false;
        }
        for (ChapterDTO chapter : chapters)
        {
            if (chapter != null && (chapter.getSchemaVersion() != null || chapter.getBlocks() != null || Boolean.TRUE.equals(chapter.getOpening())))
            {
                return true;
            }
        }
        return false;
    }

    static void assertNoBlockPayload(List<ChapterDTO> chapters, String message)
    {
        if (hasBlockPayload(chapters))
        {
            throw new ChapterFlowValidationException(message);
        }
    }

    private static ChapterFlowValidationException invalid(int chapterNumber, String detail)
    {
        return new ChapterFlowValidationException("第 " + chapterNumber + " 章故事流不合法: " + detail);
    }

    static final class CompiledFlow
    {
        private final List<CompiledChapter> chapters;

        private CompiledFlow(List<CompiledChapter> chapters)
        {
            this.chapters = chapters;
        }

        List<CompiledChapter> getChapters()
        {
            return chapters;
        }

        List<NodeDTO> getNodesInFlowOrder()
        {
            List<NodeDTO> ordered = new ArrayList<>();
            for (CompiledChapter compiled : chapters)
            {
                ChapterDTO source = compiled.getSource();
                List<NodeDTO> nodes = source.getNodes() == null
                        ? Collections.<NodeDTO>emptyList() : source.getNodes();
                for (ChapterBlockDTO block : source.getBlocks())
                {
                    if (TYPE_NODE.equals(block.getType()))
                    {
                        ordered.add(nodes.get(block.getNodeIndex()));
                    }
                }
            }
            return ordered;
        }
    }

    static final class CompiledChapter
    {
        private final ChapterDTO source;
        private final long chapterSortId;
        private final String description;
        private final Long[] nodeSortIds;

        private CompiledChapter(ChapterDTO source, long chapterSortId, String description, Long[] nodeSortIds)
        {
            this.source = source;
            this.chapterSortId = chapterSortId;
            this.description = description;
            this.nodeSortIds = nodeSortIds;
        }

        ChapterDTO getSource()
        {
            return source;
        }

        long getChapterSortId()
        {
            return chapterSortId;
        }

        String getDescription()
        {
            return description;
        }

        Long getNodeSortId(int nodeIndex)
        {
            return nodeSortIds[nodeIndex];
        }

        String materializeBlocks(List<Long> nodeIds)
        {
            if (nodeIds == null || nodeIds.size() != nodeSortIds.length)
            {
                throw new IllegalStateException("节点 id 数量与编译结果不一致");
            }
            List<Map<String, Object>> stored = new ArrayList<>(source.getBlocks().size());
            for (ChapterBlockDTO block : source.getBlocks())
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", block.getType());
                row.put("key", block.getKey());
                if (TYPE_TEXT.equals(block.getType()))
                {
                    row.put("content", block.getContent());
                    if (block.getWho() != null && !block.getWho().trim().isEmpty()) row.put("who", block.getWho().trim());
                    if (block.getLevel() != null) row.put("level", block.getLevel());
                    if (!ChapterNarrativeBlocks.isNarrativeBlock(block) && block.getWhen() != null) row.put("when", block.getWhen());
                    if (ChapterNarrativeBlocks.isNarrativeBlock(block))
                    {
                        Long beatNodeId = nodeIds.get(block.getNodeIndex());
                        if (beatNodeId == null || beatNodeId <= 0L)
                        {
                            throw new IllegalStateException("节点落库后没有生成有效 id");
                        }
                        ChapterNarrativeBlocks.materializeRow(block, beatNodeId, row);
                    }
                }
                else if (TYPE_IMAGE.equals(block.getType()) || TYPE_AUDIO.equals(block.getType()))
                {
                    row.put("url", block.getUrl());
                }
                else if (TYPE_MOOD.equals(block.getType()))
                {
                    // 存规范值:存量别名(NIGHT/ARCHIVE/MOSS/NEON)在这里收敛成新档
                    row.put("mood", ChapterAtmospherePreset.requireValid(block.getMood()));
                }
                else if (TYPE_THOUGHT.equals(block.getType()))
                {
                    row.put("thoughtKey", block.getThoughtKey().trim());
                }
                else if (TYPE_VOICE.equals(block.getType()))
                {
                    row.put("who", block.getWho());
                    row.put("content", block.getContent().trim());
                    if (block.getWhen() != null) row.put("when", block.getWhen());
                }
                else if (TYPE_ODD.equals(block.getType()))
                {
                    row.put("level", block.getLevel());
                }
                else if (TYPE_REVEAL.equals(block.getType()))
                {
                    if (block.getWho() != null && !block.getWho().trim().isEmpty()) row.put("who", block.getWho().trim());
                    row.put("content", block.getContent().trim());
                }
                else if (TYPE_DREAM.equals(block.getType()))
                {
                    List<Map<String, Object>> images = new ArrayList<>();
                    for (ChapterBlockDTO.DreamImageDTO image : block.getImages())
                    {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("url", image.getUrl());
                        if (image.getLine() != null && !image.getLine().trim().isEmpty())
                        {
                            one.put("line", image.getLine());
                        }
                        images.add(one);
                    }
                    row.put("images", images);
                    if (block.getTitle() != null && !block.getTitle().trim().isEmpty())
                    {
                        row.put("title", block.getTitle().trim());
                    }
                }
                else
                {
                    Long nodeId = nodeIds.get(block.getNodeIndex());
                    if (nodeId == null || nodeId <= 0L)
                    {
                        throw new IllegalStateException("节点落库后没有生成有效 id");
                    }
                    row.put("nodeId", nodeId);
                    if (Boolean.FALSE.equals(block.getLocationRequired())) row.put("locationRequired", false);
                }
                stored.add(row);
            }
            try
            {
                String json = JSON.writeValueAsString(stored);
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES)
                {
                    throw new ChapterFlowValidationException(
                            "第 " + chapterSortId + " 章故事流不合法: blocks 序列化后超过 64 KB");
                }
                return json;
            }
            catch (JsonProcessingException e)
            {
                throw new IllegalStateException("章节 blocks 序列化失败", e);
            }
        }
    }
}
