package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 主题模板节点 VO —— 白名单字段。
 * 永不出：longitude/latitude/checkinCode/fragmentText。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class TopicTemplateNodeVO {

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "节点地址（仅 POI 文本，非坐标）")
    private String addressName;

    @Schema(description = "节点图片")
    private String imgUrl;

    @Schema(description = "玩法类型（取自模板 interactionType）")
    private String interactionType;

    @Schema(description = "一句钩子（hookText，空则回退 description 截 40 字）")
    private String hookTeaser;

    @Schema(description = "这一站的玩法名称（取自模板 title，与 games 同一来源）")
    private String gameTitle;

    @Schema(description = "这一站玩法的预计时长（取自模板 duration，分钟）")
    private Long gameDuration;

    // ====== getters/setters ======

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }
    public String getHookTeaser() { return hookTeaser; }
    public void setHookTeaser(String hookTeaser) { this.hookTeaser = hookTeaser; }
    public String getGameTitle() { return gameTitle; }
    public void setGameTitle(String gameTitle) { this.gameTitle = gameTitle; }
    public Long getGameDuration() { return gameDuration; }
    public void setGameDuration(Long gameDuration) { this.gameDuration = gameDuration; }
}
