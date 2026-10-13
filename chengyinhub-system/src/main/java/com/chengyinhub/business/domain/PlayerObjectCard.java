package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 立体藏品卡:玩家拍到达标后铸出的那一张(见 migration_player_object_card_20260922.sql)。
 *
 * <p>一行 = 一张卡,归属人只从登录态取。{@code (memberId, originType, originId)} 在库里唯一,
 * 所以同一节点重拍是覆盖这一行,不是攒出第二张同款。
 */
public class PlayerObjectCard {
    private Long id;
    private Long memberId;
    private String sourceUrl;
    /** 抠图转存到我们 OSS 的透明 PNG。null = 没抠出来,卡面退回原照片。 */
    private String cutoutUrl;
    /** 物品在原照片里的位置「x,y,w,h」(比例),与 cutoutUrl 同生同灭。 */
    private String cutoutBox;
    private String place;
    private String title;
    /** AI 认物的分类(固定清单内)。null = 没认出来,藏品册里不归类。 */
    private String category;
    private String caption;
    private String cardStyle;
    private String originType;
    private String originId;
    private Date createTime;
    private Date updateTime;
    /** 3D 生成状态:NONE / QUEUED / GENERATING / READY / FAILED。铸卡时一律 NONE(照片卡)。 */
    private String genStatus;
    /** 帧目录;帧地址 = 前缀 + 00.webp … 23.webp。只有 READY 时有值。 */
    private String framesPrefix;
    /** READY 时为 24,其余为 0。 */
    private Integer frameCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getCutoutUrl() { return cutoutUrl; }
    public void setCutoutUrl(String cutoutUrl) { this.cutoutUrl = cutoutUrl; }
    public String getCutoutBox() { return cutoutBox; }
    /** 只读:列表查询时联出的「主题名 · 节点名」,不落库。 */
    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place; }
    public void setCutoutBox(String cutoutBox) { this.cutoutBox = cutoutBox; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public String getCardStyle() { return cardStyle; }
    public void setCardStyle(String cardStyle) { this.cardStyle = cardStyle; }
    public String getOriginType() { return originType; }
    public void setOriginType(String originType) { this.originType = originType; }
    public String getOriginId() { return originId; }
    public void setOriginId(String originId) { this.originId = originId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getGenStatus() { return genStatus; }
    public void setGenStatus(String genStatus) { this.genStatus = genStatus; }
    public String getFramesPrefix() { return framesPrefix; }
    public void setFramesPrefix(String framesPrefix) { this.framesPrefix = framesPrefix; }
    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer frameCount) { this.frameCount = frameCount; }
}
