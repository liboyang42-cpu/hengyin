package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

/**
 * 个人信息更新-请求参数
 */
public class UmsMemberUpdateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "头像", example = "")
    private String avatar;

    @Schema(description = "真实姓名", example = "")
    private String name;

    @Schema(description = "个人介绍", example = "")
    private String introduction;

    @Schema(description = "个人项目图片", example = "")
    private String casePics;


    @Schema(description = "微信二维码", example = "")
    private String wechat;

    @Schema(description = "个人项目", example = "")
    private String caseIntroducton;

    @Schema(description = "个人网址", example = "")
    private String website;

    @Schema(description = "工作地点", example = "")
    private String workAddress;

    @Schema(description = "擅长领域IDS，逗号隔开", example = "1,2")
    private String tagIds;


    public String getWechat() {
        return wechat;
    }

    public void setWechat(String wechat) {
        this.wechat = wechat;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction;
    }

    public String getCasePics() {
        return casePics;
    }

    public void setCasePics(String casePics) {
        this.casePics = casePics;
    }

    public String getCaseIntroducton() {
        return caseIntroducton;
    }

    public void setCaseIntroducton(String caseIntroducton) {
        this.caseIntroducton = caseIntroducton;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getWorkAddress() {
        return workAddress;
    }

    public void setWorkAddress(String workAddress) {
        this.workAddress = workAddress;
    }

    public String getTagIds() {
        return tagIds;
    }

    public void setTagIds(String tagIds) {
        this.tagIds = tagIds;
    }


}
