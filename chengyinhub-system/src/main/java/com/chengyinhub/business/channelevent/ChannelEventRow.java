package com.chengyinhub.business.channelevent;

import java.math.BigDecimal;
import java.util.Date;

/** One {@code channel_event} row (MyBatis maps the column aliases straight onto the fields). */
public class ChannelEventRow
{
    public static final int STATUS_WAITING_REVIEW = 0;
    public static final int STATUS_POSTED = 1;
    public static final int STATUS_CANCELLED = 2;

    private Long id;
    private Long clubId;
    private Long channelId;
    private Long activityId;
    private Long ticketId;
    private Long messageId;
    private Long posterMemberId;
    private String title;
    private Date startAt;
    private Date endAt;
    private String placeName;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer capacity;
    private Integer paid;
    private Integer status;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getPosterMemberId() { return posterMemberId; }
    public void setPosterMemberId(Long posterMemberId) { this.posterMemberId = posterMemberId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Date getStartAt() { return startAt; }
    public void setStartAt(Date startAt) { this.startAt = startAt; }
    public Date getEndAt() { return endAt; }
    public void setEndAt(Date endAt) { this.endAt = endAt; }
    public String getPlaceName() { return placeName; }
    public void setPlaceName(String placeName) { this.placeName = placeName; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getPaid() { return paid; }
    public void setPaid(Integer paid) { this.paid = paid; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public boolean isPaid() { return paid != null && paid == 1; }
}
