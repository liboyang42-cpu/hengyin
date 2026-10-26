package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * O-14 统一搜索结果行。type 取值 topic / activity / post,与发现搜索结果卡的类型键一致;
 * name 与 title 同值：name 兼容现有 /api/topic/list、/api/activity/list 行的标题字段。
 */
public class ContentSearchItemVO {
    private String type;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    private String name;
    private String title;
    private String imgUrl;
    private String cityCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date sourceTime;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Date getSourceTime() { return sourceTime; }
    public void setSourceTime(Date sourceTime) { this.sourceTime = sourceTime; }
}
