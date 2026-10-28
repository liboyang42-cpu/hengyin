package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 官方通知/广播。可绑活动(event_id)或单发纯公告(event_id=null)。
 * 定向:audience(player,club,merchant 多选)× city → 圈 member;渠道 inapp(站内信)+subscribe(订阅消息)。
 * 设计真源:vault《Questify官方活动系统_产品设计报告_2026-07-08》§8.2
 *
 * @author cy
 * @date 2026-07-08
 */
public class OfficialBroadcast
{
    private String clientRequestId;
    private String requestOwner;
    private String requestHash;
    public String getClientRequestId(){return clientRequestId;}
    public void setClientRequestId(String v){clientRequestId=v;}
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRequestOwner(){return requestOwner;}
    public void setRequestOwner(String v){requestOwner=v;}
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRequestHash(){return requestHash;}
    public void setRequestHash(String v){requestHash=v;}
    private Long id;
    private Long eventId;          // 可空=纯公告
    private String title;
    private String audience;       // player,club,merchant 逗号集
    private Integer copyMode;      // 1一起发 2分开发
    private String contentJson;    // 一起发={"title":..,"sub":..};分开发={"player":{..},..}
    private String channels;       // inapp,subscribe 逗号集
    private String city;           // 城市名关键词;null=全国
    private Date scheduledTime;    // null=立即
    private Integer status;        // 0草稿 1待发 2已发 3已撤
    private Integer reachedCount;
    private Long senderAccount;
    private Date createTime;

    public static final int MODE_UNIFIED = 1;
    public static final int MODE_PER_ROLE = 2;

    public static final int ST_DRAFT = 0;
    public static final int ST_PENDING = 1;
    public static final int ST_SENT = 2;
    public static final int ST_REVOKED = 3;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public Integer getCopyMode() { return copyMode; }
    public void setCopyMode(Integer copyMode) { this.copyMode = copyMode; }

    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }

    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public Date getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(Date scheduledTime) { this.scheduledTime = scheduledTime; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getReachedCount() { return reachedCount; }
    public void setReachedCount(Integer reachedCount) { this.reachedCount = reachedCount; }

    public Long getSenderAccount() { return senderAccount; }
    public void setSenderAccount(Long senderAccount) { this.senderAccount = senderAccount; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
