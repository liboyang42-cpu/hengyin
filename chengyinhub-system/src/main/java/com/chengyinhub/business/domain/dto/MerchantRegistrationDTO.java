package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

public class MerchantRegistrationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;



    private Integer categoryId;

    /** 商家名称 */
    @Schema(description = "商家名称", example = "")
    //@NotBlank(message = "商家名称不能为空")
    private String name;

    /** 商家LOGO */
    @Schema(description = "商家LOGO", example = "")
    private String logo;

    /** 介绍 */
    @Schema(description = "介绍", example = "")
    private String description;

    /** 衍生品 */
    @Schema(description = "衍生品", example = "")
    private String derivatives;

    /** 品牌网址 */
    @Schema(description = "品牌网址", example = "")
    private String website;

    /** 品牌偏好 */
    @Schema(description = "品牌偏好ids集合逗号隔开", example = "1,2")
    private String preference;


    @Schema(name = "联系电话", example = "")
    private String phone;

    /** 商户地址 */
    @Schema(name = "商户地址", example = "")
    private String address;

    /** 选点纬度。前端 wx.chooseLocation 跟 address 一起拿到,可空(手打地址时没有)。 */
    @Schema(description = "选点纬度", example = "30.27411")
    private Double locationLat;

    /** 选点经度。可空;与纬度必须成对给,只给一个视同没给。 */
    @Schema(description = "选点经度", example = "120.15514")
    private Double locationLng;

    /** 营业时间 */
    @Schema(name = "营业时间", example = "")
    private String businessTime;

    /** 营业执照 */
    @Schema(name = "营业执照", example = "")
    private String businessLicense;


    @Schema(description = "微信二维码", example = "")
    private String wechat;

    public String getWechat() {
        return wechat;
    }

    public void setWechat(String wechat) {
        this.wechat = wechat;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Double getLocationLat() {
        return locationLat;
    }

    public void setLocationLat(Double locationLat) {
        this.locationLat = locationLat;
    }

    public Double getLocationLng() {
        return locationLng;
    }

    public void setLocationLng(Double locationLng) {
        this.locationLng = locationLng;
    }

    public String getBusinessTime() {
        return businessTime;
    }

    public void setBusinessTime(String businessTime) {
        this.businessTime = businessTime;
    }

    public String getBusinessLicense() {
        return businessLicense;
    }

    public void setBusinessLicense(String businessLicense) {
        this.businessLicense = businessLicense;
    }

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

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDerivatives() {
        return derivatives;
    }

    public void setDerivatives(String derivatives) {
        this.derivatives = derivatives;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getPreference() {
        return preference;
    }

    public void setPreference(String preference) {
        this.preference = preference;
    }
}
