package com.chengyinhub.business.domain.dto;

/** 后台商家线索 Excel 的一条物理行。字段先保留为文本，便于逐行报告格式错误。 */
public class MerchantLeadImportRow {
    private int rowNumber;
    private String name;
    private String phone;
    private String address;
    private String longitude;
    private String latitude;
    private String category;
    private String tags;
    private String source;
    private String memberId;
    private String contactOpen;
    private String coopOpen;
    private String status;
    private String parseError;

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
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
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getContactOpen() { return contactOpen; }
    public void setContactOpen(String contactOpen) { this.contactOpen = contactOpen; }
    public String getCoopOpen() { return coopOpen; }
    public void setCoopOpen(String coopOpen) { this.coopOpen = coopOpen; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
}
