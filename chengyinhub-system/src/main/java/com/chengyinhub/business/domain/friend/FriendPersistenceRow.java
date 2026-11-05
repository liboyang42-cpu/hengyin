package com.chengyinhub.business.domain.friend;

import java.time.LocalDateTime;

/** Internal durable pair row, never an HTTP response. Requester provenance is retained. */
public class FriendPersistenceRow
{
    private Long id;
    private Long lowMemberId;
    private Long highMemberId;
    private Long requesterId;
    private Integer status;
    private Long version;
    private LocalDateTime expireTime;
    private LocalDateTime friendSince;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLowMemberId() { return lowMemberId; }
    public void setLowMemberId(Long value) { lowMemberId = value; }
    public Long getHighMemberId() { return highMemberId; }
    public void setHighMemberId(Long value) { highMemberId = value; }
    public Long getRequesterId() { return requesterId; }
    public void setRequesterId(Long value) { requesterId = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime value) { expireTime = value; }
    public LocalDateTime getFriendSince() { return friendSince; }
    public void setFriendSince(LocalDateTime value) { friendSince = value; }
}
