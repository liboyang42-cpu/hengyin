package com.chengyinhub.business.domain.vo;

/**
 * 附近商家目录返回项。
 * canInvite = 已绑定小程序账号且开放协作(memberId!=null && coopOpen=1);
 * canCall   = 有电话且开放联系(phone!=null && contactOpen=1)。
 * 电话仅对商家/俱乐部身份返回;玩家端 phone 置空、canCall=false。
 */
public class NearbyMerchantVO {
    private Long id;
    private Long memberId;
    private String name;
    private String phone;
    private String address;
    private String longitude;
    private String latitude;
    private Double distance; // 米
    private String category;
    private String tags;
    private String source;
    // 原始开关(mapper 填,service 据此算 canInvite/canCall 后可不下发)
    private Integer contactOpen;
    private Integer coopOpen;
    private Boolean canInvite;
    private Boolean canCall;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getContactOpen() { return contactOpen; }
    public void setContactOpen(Integer contactOpen) { this.contactOpen = contactOpen; }
    public Integer getCoopOpen() { return coopOpen; }
    public void setCoopOpen(Integer coopOpen) { this.coopOpen = coopOpen; }
    public Boolean getCanInvite() { return canInvite; }
    public void setCanInvite(Boolean canInvite) { this.canInvite = canInvite; }
    public Boolean getCanCall() { return canCall; }
    public void setCanCall(Boolean canCall) { this.canCall = canCall; }
}
