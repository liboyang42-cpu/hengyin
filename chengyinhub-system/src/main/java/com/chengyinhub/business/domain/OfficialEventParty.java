package com.chengyinhub.business.domain;

import java.util.Date;

/** 活动级主办、协办及承接关系；一行即一项可审计责任范围。 */
public class OfficialEventParty {
    public static final String TYPE_OFFICIAL = "OFFICIAL";
    public static final String TYPE_MERCHANT = "MERCHANT";
    public static final String TYPE_CLUB = "CLUB";

    /** OFFICIAL.party_id 只能引用 ums_member.id；未知旧身份域一律不参与授权。 */
    public static final String IDENTITY_UMS_MEMBER = "UMS_MEMBER";
    public static final String IDENTITY_DOMAIN_ENTITY = "DOMAIN_ENTITY";
    public static final String IDENTITY_LEGACY_UNRESOLVED = "LEGACY_UNRESOLVED";

    public static final String ROLE_ORGANIZER = "ORGANIZER";
    public static final String ROLE_CO_ORGANIZER = "CO_ORGANIZER";
    public static final String ROLE_FULFILLMENT_MERCHANT = "FULFILLMENT_MERCHANT";
    public static final String ROLE_FULFILLMENT_CLUB = "FULFILLMENT_CLUB";
    public static final String ROLE_SPONSOR = "SPONSOR";
    public static final String ROLE_PROMOTION_PARTNER = "PROMOTION_PARTNER";

    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private Long id;
    private Long eventId;
    private String partyType;
    private Long partyId;
    private String identitySource;
    /** 仅 OFFICIAL/ORGANIZER：1=当前唯一有效主办，0=普通/待转交关系。 */
    private Integer organizerAuthority;
    private String role;
    private String targetType;
    private Long targetId;
    private Date windowStart;
    private Date windowEnd;
    private Integer capacity;
    private String responsibilitySummary;
    private String contactRole;
    private String status;
    private Long invitedBy;
    private Date invitedAt;
    private Long statusBy;
    private Date statusAt;
    private String statusReason;
    private String snapshotJson;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getPartyType() { return partyType; }
    public void setPartyType(String partyType) { this.partyType = partyType; }
    public Long getPartyId() { return partyId; }
    public void setPartyId(Long partyId) { this.partyId = partyId; }
    public String getIdentitySource() { return identitySource; }
    public void setIdentitySource(String identitySource) { this.identitySource = identitySource; }
    public Integer getOrganizerAuthority() { return organizerAuthority; }
    public void setOrganizerAuthority(Integer organizerAuthority) { this.organizerAuthority = organizerAuthority; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Date getWindowStart() { return windowStart; }
    public void setWindowStart(Date windowStart) { this.windowStart = windowStart; }
    public Date getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Date windowEnd) { this.windowEnd = windowEnd; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public String getResponsibilitySummary() { return responsibilitySummary; }
    public void setResponsibilitySummary(String responsibilitySummary) { this.responsibilitySummary = responsibilitySummary; }
    public String getContactRole() { return contactRole; }
    public void setContactRole(String contactRole) { this.contactRole = contactRole; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }
    public Date getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Date invitedAt) { this.invitedAt = invitedAt; }
    public Long getStatusBy() { return statusBy; }
    public void setStatusBy(Long statusBy) { this.statusBy = statusBy; }
    public Date getStatusAt() { return statusAt; }
    public void setStatusAt(Date statusAt) { this.statusAt = statusAt; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
