package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 主题模板内含玩法 VO —— 按 templateId 去重，白名单字段。
 * 永不出：答案/提示/打卡码/storyJson 等敏感字段。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class TopicTemplateGameVO {

    @Schema(description = "模板ID")
    private Long id;

    @Schema(description = "玩法标题")
    private String title;

    @Schema(description = "封面图")
    private String imgUrl;

    @Schema(description = "游戏人数")
    private String players;

    @Schema(description = "时长（分钟）")
    private Long duration;

    @Schema(description = "难度")
    private String difficulty;

    @Schema(description = "玩法类型：hidden_menu/city_story_card/photo_spot/qr_checkin")
    private String interactionType;

    // ====== getters/setters ======

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getPlayers() { return players; }
    public void setPlayers(String players) { this.players = players; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }
}
