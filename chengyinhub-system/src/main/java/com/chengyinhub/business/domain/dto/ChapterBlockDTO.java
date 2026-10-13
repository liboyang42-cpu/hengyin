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

    @Schema(description = "块类型：text / node / image / audio / dream", example = "text")
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
