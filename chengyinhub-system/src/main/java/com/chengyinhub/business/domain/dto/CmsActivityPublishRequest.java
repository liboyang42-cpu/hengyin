package com.chengyinhub.business.domain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.Valid;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "活动发布请求参数") // 添加类级别的Schema注解
public class CmsActivityPublishRequest implements Serializable {

    @Schema(description = "活动ID（小程序更新时必填；发布时忽略）")
    private Long id;

    @Schema(description = "活动名称", example = "周末技术沙龙")
    @NotBlank(message = "活动名称不能为空")
    private String name;

    @Schema(description = "活动描述", example = "这是一个关于Spring Boot和云原生的技术分享活动")
    private String description;

    @Schema(description = "活动封面图URL", example = "https://example.com/cover.jpg")
    private String imgUrl;

    @Schema(description = "活动轮播图URL列表（多张，分号隔开）", example = "https://example.com/1.jpg;https://example.com/2.jpg")
    private String imgArr;

    @Schema(description = "活动类别IDS（逗号隔开）", example = "1,3,5")
    private String categoryIds;

    @Schema(description = "场馆名称", example = "国际会议中心")
    private String addressName;

    @Schema(description = "地址经度", example = "116.391275")
    private String longitude;

    @Schema(description = "地址纬度", example = "39.992357")
    private String latitude;

    @Schema(description = "详细地址", example = "北京市朝阳区国家体育场南路1号")
    private String address;

    @Schema(description = "活动开始时间", example = "2024-08-01 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "活动开始时间不能为空")
    private Date startDate;


    @Schema(description = "活动结束时间", example = "2024-09-01 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @NotNull(message = "活动结束时间不能为空")
    private Date endDate;

    @Schema(description = "模板ID", example = "5")
    private Long templateId;

    @Schema(description = "合作者用户ID列表", example = "[123, 456, 789]")
    private List<Long> collaborators;

    @Schema(description = "票务信息列表")
    @Valid
    private List<ActivityTicketRequest> tickets;

    // Getter 和 Setter 方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImgUrl() {
        return imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public String getImgArr() {
        return imgArr;
    }

    public void setImgArr(String imgArr) {
        this.imgArr = imgArr;
    }

    public String getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(String categoryIds) {
        this.categoryIds = categoryIds;
    }

    public String getAddressName() {
        return addressName;
    }

    public void setAddressName(String addressName) {
        this.addressName = addressName;
    }

    public String getLongitude() {
        return longitude;
    }

    public void setLongitude(String longitude) {
        this.longitude = longitude;
    }

    public String getLatitude() {
        return latitude;
    }

    public void setLatitude(String latitude) {
        this.latitude = latitude;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    /** M1-3 社群活动所属俱乐部ID(俱乐部主理人发社群活动时传;为空=公开活动) */
    private Long clubId;

    public Long getClubId() {
        return clubId;
    }

    public void setClubId(Long clubId) {
        this.clubId = clubId;
    }

    /**
     * 写链路身份作用域:MERCHANT=以商家主办身份发布/改(店主或持项目管理权的店员);
     * 空或 CLUB=俱乐部主理人。与 /api/activity/cancel 的 scope 同一套口径。
     */
    @Schema(description = "业务作用域，MERCHANT=商家主办，空=俱乐部主理人", example = "MERCHANT")
    private String scope;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public List<Long> getCollaborators() {
        return collaborators;
    }

    public void setCollaborators(List<Long> collaborators) {
        this.collaborators = collaborators;
    }

    public List<ActivityTicketRequest> getTickets() {
        return tickets;
    }

    public void setTickets(List<ActivityTicketRequest> tickets) {
        this.tickets = tickets;
    }

    // equals 方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CmsActivityPublishRequest that = (CmsActivityPublishRequest) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(imgUrl, that.imgUrl) &&
                Objects.equals(imgArr, that.imgArr) &&
                Objects.equals(categoryIds, that.categoryIds) &&
                Objects.equals(addressName, that.addressName) &&
                Objects.equals(longitude, that.longitude) &&
                Objects.equals(latitude, that.latitude) &&
                Objects.equals(address, that.address) &&
                Objects.equals(startDate, that.startDate) &&
                Objects.equals(endDate, that.endDate) &&
                Objects.equals(templateId, that.templateId) &&
                Objects.equals(collaborators, that.collaborators) &&
                Objects.equals(tickets, that.tickets);
    }

    // hashCode 方法
    @Override
    public int hashCode() {
        return Objects.hash(name, description, imgUrl, imgArr, categoryIds, addressName,
                longitude, latitude, address, startDate, endDate, templateId,
                collaborators, tickets);
    }

    // toString 方法
    @Override
    public String toString() {
        return "CmsActivityPublishRequest{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", imgUrl='" + imgUrl + '\'' +
                ", imgArr='" + imgArr + '\'' +
                ", categoryIds='" + categoryIds + '\'' +
                ", addressName='" + addressName + '\'' +
                ", longitude='" + longitude + '\'' +
                ", latitude='" + latitude + '\'' +
                ", address='" + address + '\'' +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", templateId=" + templateId +
                ", collaborators=" + collaborators +
                ", tickets=" + tickets +
                '}';
    }

    // 无参构造函数
    public CmsActivityPublishRequest() {
    }

    // 全参构造函数（可选）
    public CmsActivityPublishRequest(String name, String description, String imgUrl,
                                     String imgArr, String categoryIds, String addressName,
                                     String longitude, String latitude, String address,
                                     Date startDate, Date endDate, Long templateId,
                                     List<Long> collaborators, List<ActivityTicketRequest> tickets) {
        this.name = name;
        this.description = description;
        this.imgUrl = imgUrl;
        this.imgArr = imgArr;
        this.categoryIds = categoryIds;
        this.addressName = addressName;
        this.longitude = longitude;
        this.latitude = latitude;
        this.address = address;
        this.startDate = startDate;
        this.endDate = endDate;
        this.templateId = templateId;
        this.collaborators = collaborators;
        this.tickets = tickets;
    }
}
