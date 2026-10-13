package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;
import com.chengyinhub.common.annotation.Excel;

/**
 * 商家目录/线索(含未注册商家)。
 * member_id 可空:为空=未注册商家(只能电话联系,不能线上邀请);非空=已绑定小程序账号。
 */
public class MerchantLead extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    @Excel(name = "绑定会员ID", sort = 9)
    private Long memberId;
    @Excel(name = "商家名称", sort = 1, prompt = "必填，最多 128 字")
    private String name;
    @Excel(name = "联系电话", sort = 2)
    private String phone;
    @Excel(name = "地址", sort = 3)
    private String address;
    @Excel(name = "经度", sort = 4, prompt = "经纬度须同时填写；经度范围 -180 到 180")
    private String longitude;
    @Excel(name = "纬度", sort = 5, prompt = "经纬度须同时填写；纬度范围 -90 到 90")
    private String latitude;
    @Excel(name = "类型", sort = 6)
    private String category;
    @Excel(name = "标签", sort = 7)
    private String tags;
    @Excel(name = "来源", sort = 8, prompt = "留空时按 bd_import 导入")
    private String source;
    @Excel(name = "开放联系", sort = 10, prompt = "0=关闭，1=开启", combo = {"0", "1"})
    private Integer contactOpen;
    @Excel(name = "开放协作", sort = 11, prompt = "0=关闭，1=开启", combo = {"0", "1"})
    private Integer coopOpen;
    @Excel(name = "展示状态", sort = 12, prompt = "0=隐藏，1=可展示", combo = {"0", "1"})
    private Integer status;
    private Integer delFlag;

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
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
