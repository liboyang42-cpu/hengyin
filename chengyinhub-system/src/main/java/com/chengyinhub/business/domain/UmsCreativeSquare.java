package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 创意广场-用户发布对象 ums_creative_square
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public class UmsCreativeSquare extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long memberId;

    /** 发布内容 */
    @Excel(name = "发布内容")
    private String contents;

    /** 发布的图片，英文分号隔开 */
    @Excel(name = "发布的图片，英文分号隔开")
    private String pics;

    /** 上传的文件下载地址，英文分号隔开 */
    @Excel(name = "上传的文件下载地址，英文分号隔开")
    private String fileUrl;

    /** 地址 */
    @Excel(name = "地址")
    private String address;

    /** 地址经度 */
    @Excel(name = "地址经度")
    private String longitude;

    /** 地址纬度 */
    @Excel(name = "地址纬度")
    private String latitude;

    /** 活动ID/主题ID */
    @Excel(name = "活动ID/主题ID")
    private Long dataId;

    /** 0直接发布   1、活动发布 2、主题发布 */
    @Excel(name = "0直接发布   1、活动发布 2、主题发布")
    private Long dataType;

    /** 点赞数量 */
    @Excel(name = "点赞数量")
    private Long likeNum;

    /** 踩一踩数量 */
    @Excel(name = "踩一踩数量")
    private Long nolikeNum;

    /** 状态 1显示 2隐藏 */
    @Excel(name = "状态 1显示 2隐藏")
    private Integer status;

    /** 是否参与众筹投票  1参与  2不参与 */
    @Excel(name = "是否参与众筹投票  1参与  2不参与")
    private Integer isCrowdfunding;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    private  Integer checkStatus;

    /** 运动feed路线静态预览图(发布时<map>快照上传OSS) */
    private String routePreviewImg;

    /** R10-03 客户端发布意图幂等键(账号内唯一) */
    private String requestId;

    /** 发布意图载荷 sha256;同键不同载荷=冲突 */
    private String requestSignature;

    public String getRoutePreviewImg() {
        return routePreviewImg;
    }

    public void setRoutePreviewImg(String routePreviewImg) {
        this.routePreviewImg = routePreviewImg;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestSignature() {
        return requestSignature;
    }

    public void setRequestSignature(String requestSignature) {
        this.requestSignature = requestSignature;
    }

    public Integer getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(Integer checkStatus) {
        this.checkStatus = checkStatus;
    }

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setContents(String contents) 
    {
        this.contents = contents;
    }

    public String getContents() 
    {
        return contents;
    }

    public void setPics(String pics) 
    {
        this.pics = pics;
    }

    public String getPics() 
    {
        return pics;
    }

    public void setFileUrl(String fileUrl) 
    {
        this.fileUrl = fileUrl;
    }

    public String getFileUrl() 
    {
        return fileUrl;
    }

    public void setAddress(String address) 
    {
        this.address = address;
    }

    public String getAddress() 
    {
        return address;
    }

    public void setLongitude(String longitude) 
    {
        this.longitude = longitude;
    }

    public String getLongitude() 
    {
        return longitude;
    }

    public void setLatitude(String latitude) 
    {
        this.latitude = latitude;
    }

    public String getLatitude() 
    {
        return latitude;
    }

    public void setDataId(Long dataId) 
    {
        this.dataId = dataId;
    }

    public Long getDataId() 
    {
        return dataId;
    }

    public void setDataType(Long dataType) 
    {
        this.dataType = dataType;
    }

    public Long getDataType() 
    {
        return dataType;
    }

    public void setLikeNum(Long likeNum) 
    {
        this.likeNum = likeNum;
    }

    public Long getLikeNum() 
    {
        return likeNum;
    }

    public void setNolikeNum(Long nolikeNum) 
    {
        this.nolikeNum = nolikeNum;
    }

    public Long getNolikeNum() 
    {
        return nolikeNum;
    }

    public void setStatus(Integer status) 
    {
        this.status = status;
    }

    public Integer getStatus() 
    {
        return status;
    }

    public void setIsCrowdfunding(Integer isCrowdfunding) 
    {
        this.isCrowdfunding = isCrowdfunding;
    }

    public Integer getIsCrowdfunding() 
    {
        return isCrowdfunding;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("contents", getContents())
            .append("pics", getPics())
            .append("fileUrl", getFileUrl())
            .append("address", getAddress())
            .append("longitude", getLongitude())
            .append("latitude", getLatitude())
            .append("dataId", getDataId())
            .append("dataType", getDataType())
            .append("likeNum", getLikeNum())
            .append("nolikeNum", getNolikeNum())
            .append("status", getStatus())
            .append("isCrowdfunding", getIsCrowdfunding())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
