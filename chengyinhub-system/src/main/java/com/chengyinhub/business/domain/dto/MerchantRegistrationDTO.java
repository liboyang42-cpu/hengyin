package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public class MerchantRegistrationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;



    private Integer categoryId;

    /** M-16:入驻时选的行业类型(逗号分隔 sys_category.id,第一个是主行业),与门店信息同口径 */
    private String categoryIds;

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

    /**
     * 经营者真实姓名(2-30 字)。新入驻必填(或此前已在 /api/publisher/identity 登记过)。
     *
     * <p>这里刻意<b>不</b>挂 Bean Validation 注解:校验失败时全局异常处理器会把
     * rejected value 原样打进日志,等于把身份证号写进日志文件。校验统一在
     * MemberIdentityService 里做,报错文案不带入参。
     */
    @Schema(description = "经营者真实姓名,2-30 字,新入驻必填", example = "")
    private String realName;

    /** 经营者身份证号(18 位,末位可为 X/x,GB 11643-1999 校验位)。只进加密列,任何响应只回脱敏值。 */
    @Schema(description = "经营者身份证号,18 位,新入驻必填", example = "")
    private String idCardNumber;

    /** 「同意向平台提供真实姓名与身份证号」单独勾选(PIPL 第29条),随 realName/idCardNumber 一起提交时必须为 true。 */
    @Schema(description = "实名信息单独同意,提交 realName/idCardNumber 时必须为 true", example = "true")
    private Boolean identityConsent;

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getIdCardNumber() {
        return idCardNumber;
    }

    public void setIdCardNumber(String idCardNumber) {
        this.idCardNumber = idCardNumber;
    }

    public Boolean getIdentityConsent() {
        return identityConsent;
    }

    public void setIdentityConsent(Boolean identityConsent) {
        this.identityConsent = identityConsent;
    }

    public String getWechat() {
        return wechat;
    }

    public void setWechat(String wechat) {
        this.wechat = wechat;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public String getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(String categoryIds) {
        this.categoryIds = categoryIds;
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
