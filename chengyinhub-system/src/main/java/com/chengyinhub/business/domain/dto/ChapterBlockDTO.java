package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.List;

/**
 * 章节故事流写入块。
 *
 * <p>节点只按本章 {@link ChapterDTO#getNodes()} 的下标引用；节点落库后由后端把下标翻译成真实 id。
 *
 * <p>R18:带 {@code beat} 的 text 块是节点叙事段（brief / route / enter / outcome / deliver /
 * unlock / revisit），正文就是块本身，归属站点同样用 nodeIndex（落库后翻译成 nodeId）。
 * 形状与校验见 {@link com.chengyinhub.business.service.support.ChapterNarrativeBlocks}。
 */
@Schema(description = "章节故事流写入块")
public class ChapterBlockDTO implements Serializable
{
    private static final long serialVersionUID = 1L;

    @Schema(description = "块类型：text / node / image / audio / dream / mood / thought / voice / odd / reveal / divider / gallery / branch / cap", example = "text")
    private String type;

    @Schema(description = "文本块正文；仅 type=text 时有效")
    private String content;

    @Schema(description = "节点在本章 nodes 数组中的下标；仅 type=node 与叙事块时有效")
    private Integer nodeIndex;

    @Schema(description = "node 块是否需要到达地点；false 为直接开始的故事玩法，默认 true")
    private Boolean locationRequired;

    public Boolean getLocationRequired() { return locationRequired; }
    public void setLocationRequired(Boolean locationRequired) { this.locationRequired = locationRequired; }

    @Schema(description = "媒体地址；仅 type=image / audio 时有效")
    private String url;
    @Schema(description = "图片文字替代或音频转写；仅媒体块使用，最多2000字")
    private String altText;

    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }

    /** mood 块专用:平台氛围预设名。作者只选名字,渲染参数在客户端。 */
    private String mood;
    /** thought 块专用:在这一段获得哪个念头;key 引用 journeyRules.thoughts。 */
    private String thoughtKey;
    /** voice 块专用:说话的是哪个内心声音(规矩 / 窗外 / 心 / 精密 / 咔哒),正文在 content;reveal 块:对面的名字。 */
    private String who;
    /** odd 块专用:从这一段起「不正常」到第几档(0 正常 · 1 字偶尔闪 · 2 褪色错位 · 3 等宽字、声音改名)。 */
    private Integer level;

    public String getWho() { return who; }
    public void setWho(String who) { this.who = who; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }

    @Schema(description = "叙事段；仅 type=text 且是节点叙事时有效，如 enter / outcome")
    private String beat;

    @Schema(description = "叙事段内的正文位；仅带 beat 时有效，如 opener / line / fact")
    private String field;

    @Schema(description = "问题 id；仅 beat=enter 且 field=q / a 时有效")
    private String qid;

    @Schema(description = "说话人 NPC id；仅 beat=enter / outcome 时有效")
    private Long npcId;

    @Schema(description = "条件；仅 beat=brief 的 carry、outcome 的 fact、revisit 的 change 上有效")
    private JsonNode when;

    @Schema(description = "稍后领取；仅 beat=deliver 时有效")
    private Boolean claimLater;

    @Schema(description = "内容块稳定标识；编辑、排序和恢复进度时保持不变")
    private String key;

    @Schema(description = "梦块的图与每张一句话；仅 type=dream 时有效，1 至 6 张")
    private List<DreamImageDTO> images;

    @Schema(description = "相册名称；仅 type=dream 时有效，旧内容可缺省")
    private String title;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    @Schema(description = "正文段样式；仅普通 text 块：h1 标题 / h2 副标题 / quote 引用 / ul 项目列表 / ol 编号列表，缺省为正文", example = "h1")
    private String style;

    @Schema(description = "正文段内格式区间；仅普通 text 块。content 仍是纯文字，区间 [from,to) 按字符下标")
    private List<TextMarkDTO> marks;

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public List<TextMarkDTO> getMarks() { return marks; }
    public void setMarks(List<TextMarkDTO> marks) { this.marks = marks; }

    /* 2026-11-10 l-editor(业主批准的编辑器原型):图片组 / 分支任务 / 章节开始·结束卡。 */
    @Schema(description = "图片组版式；仅 type=gallery：1 单图 / 2 双图 / 3 三图 / 4 四图，images 张数不超过它", example = "2")
    private Integer layout;

    @Schema(description = "章节卡位；仅 type=cap：open 章节开始(开场白，必须是第一个块) / end 章节结束(结语，必须是最后一个块)", example = "open")
    private String cap;

    @Schema(description = "分支任务的 2 至 3 条分支；仅 type=branch，content 是问题")
    private List<BranchOptionDTO> options;

    public Integer getLayout() { return layout; }
    public void setLayout(Integer layout) { this.layout = layout; }
    public String getCap() { return cap; }
    public void setCap(String cap) { this.cap = cap; }
    public List<BranchOptionDTO> getOptions() { return options; }
    public void setOptions(List<BranchOptionDTO> options) { this.options = options; }

    /** 分支任务的一条分支:玩家看到的选项文字 + 去向(continue 继续本章 / end 结束本章 / chapter:N 跳到第 N 章)。 */
    public static class BranchOptionDTO implements Serializable
    {
        private static final long serialVersionUID = 1L;

        @Schema(description = "玩家看到的选项", example = "直接去邮局")
        private String text;

        @Schema(description = "去向：continue / end / chapter:N", example = "continue")
        private String to;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }

    /** 段内一段格式:bold / italic / strike,区间左闭右开。 */
    public static class TextMarkDTO implements Serializable
    {
        private static final long serialVersionUID = 1L;

        @Schema(description = "起点（含）")
        private Integer from;

        @Schema(description = "终点（不含）")
        private Integer to;

        @Schema(description = "bold / italic / strike")
        private String type;

        public Integer getFrom() { return from; }
        public void setFrom(Integer from) { this.from = from; }
        public Integer getTo() { return to; }
        public void setTo(Integer to) { this.to = to; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }


    /** 梦块里的一张图:url 必填,line 是配在它旁边的一句话。 */
    public static class DreamImageDTO implements Serializable
    {
        private static final long serialVersionUID = 1L;

        @Schema(description = "图片地址")
        private String url;

        @Schema(description = "这张图配的一句话")
        private String line;

        public String getUrl()
        {
            return url;
        }

        public void setUrl(String url)
        {
            this.url = url;
        }

        public String getLine()
        {
            return line;
        }

        public void setLine(String line)
        {
            this.line = line;
        }
    }


    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getContent()
    {
        return content;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public Integer getNodeIndex()
    {
        return nodeIndex;
    }

    public void setNodeIndex(Integer nodeIndex)
    {
        this.nodeIndex = nodeIndex;
    }

    public String getThoughtKey()
    {
        return thoughtKey;
    }

    public void setThoughtKey(String thoughtKey)
    {
        this.thoughtKey = thoughtKey;
    }

    public String getMood()
    {
        return mood;
    }

    public void setMood(String mood)
    {
        this.mood = mood;
    }

    public String getUrl()
    {
        return url;
    }

    public void setUrl(String url)
    {
        this.url = url;
    }

    public String getBeat()
    {
        return beat;
    }

    public void setBeat(String beat)
    {
        this.beat = beat;
    }

    public String getField()
    {
        return field;
    }

    public void setField(String field)
    {
        this.field = field;
    }

    public String getQid()
    {
        return qid;
    }

    public void setQid(String qid)
    {
        this.qid = qid;
    }

    public Long getNpcId()
    {
        return npcId;
    }

    public void setNpcId(Long npcId)
    {
        this.npcId = npcId;
    }

    public JsonNode getWhen()
    {
        return when;
    }

    public void setWhen(JsonNode when)
    {
        this.when = when;
    }

    public Boolean getClaimLater()
    {
        return claimLater;
    }

    public void setClaimLater(Boolean claimLater)
    {
        this.claimLater = claimLater;
    }

    public String getKey()
    {
        return key;
    }

    public void setKey(String key)
    {
        this.key = key;
    }

    public List<DreamImageDTO> getImages()
    {
        return images;
    }

    public void setImages(List<DreamImageDTO> images)
    {
        this.images = images;
    }

}
