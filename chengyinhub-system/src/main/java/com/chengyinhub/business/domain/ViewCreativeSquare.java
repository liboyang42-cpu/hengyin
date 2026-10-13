package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_creative_square
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public class ViewCreativeSquare extends BaseEntity
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

    /** 昵称 */
    @Excel(name = "昵称")
    private String memberNickname;

    /** 头像 */
    @Excel(name = "头像")
    private String memberAvatar;

    /** 用户类型：1普通会员 2商户会员 */
    @Excel(name = "用户类型：1普通会员 2商户会员")
    private Long memberUserType;

    /** 会员等级 */
    @Excel(name = "会员等级")
    private Long memberLevelId;

    /** 评论数量 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long commentCount;

    /** 踩一踩数量 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long noLikeCount;

    /** 点赞数量 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long likeCount;

    /** 自己是否点赞  0未操作  1已点赞  2已踩*/
    @Excel(name = "自己是否点赞  0未操作  1已点赞  2已踩")
    private Integer isLiked;

    /** 自己是否收藏 0未收藏 1已收藏 */
    private Integer isBookmarked;

    /** 自己是否关注该用户  0未关注  1已关注*/
    @Excel(name = "自己是否关注该用户  0未关注  1已关注")
    private Integer isFollowTheUser;

    /** 运动feed派生字段(非DB列,由controller按发帖人完成度填充) */
    /** 关联活动/主题名称 */
    private String sportName;
    /** 关联活动/主题封面图 */
    private String sportCover;
    /** 主题ID(用于详情页拉路线轨迹) */
    private Long sportTopicId;
    /** 节点总数 */
    private Integer nodeTotal;
    /** 发帖人已打卡节点数 */
    private Integer nodeDoneCount;
    /** 是否通关(doneCount>=total) */
    private Boolean completed;
    /** 本次漫游范围内作者夺得的金牌数(节点全平台第1个完成) */
    private Integer medalGold;
    /** 银牌数(第2个完成) */
    private Integer medalSilver;
    /** 铜牌数(第3个完成) */
    private Integer medalBronze;
    /** 路线静态预览图(发布快照,来自base表route_preview_img) */
    private String routePreviewImg;
    /** 关联主题是否为可复用模板(仅 dataType=2 有意义) */
    private Boolean isTopicTemplate;

    /** 查询用(非DB列):已登录调用者ID。设置后列表放行"本人 status=0/2 待审/驳回"行,他人仍仅见 PASS */
    private Long selfPendingMemberId;

    /** 查询用(非DB列):只返回该成员关注的作者。 */
    private Long followingMemberId;

    /** 查询用(非DB列):在分页前排除与该成员任一方向存在 Block 的作者。 */
    private Long viewerMemberId;

    /** 查询用(非DB列):只返回当前用户收藏的帖文。 */
    private Boolean favoriteOnly;

    public String getRoutePreviewImg() { return routePreviewImg; }
    public void setRoutePreviewImg(String routePreviewImg) { this.routePreviewImg = routePreviewImg; }
    public Long getSelfPendingMemberId() { return selfPendingMemberId; }
    public void setSelfPendingMemberId(Long selfPendingMemberId) { this.selfPendingMemberId = selfPendingMemberId; }
    public Long getFollowingMemberId() { return followingMemberId; }
    public void setFollowingMemberId(Long followingMemberId) { this.followingMemberId = followingMemberId; }
    public Long getViewerMemberId() { return viewerMemberId; }
    public void setViewerMemberId(Long viewerMemberId) { this.viewerMemberId = viewerMemberId; }
    public Boolean getFavoriteOnly() { return favoriteOnly; }
    public void setFavoriteOnly(Boolean favoriteOnly) { this.favoriteOnly = favoriteOnly; }
    public String getSportName() { return sportName; }
    public void setSportName(String sportName) { this.sportName = sportName; }
    public String getSportCover() { return sportCover; }
    public void setSportCover(String sportCover) { this.sportCover = sportCover; }
    public Long getSportTopicId() { return sportTopicId; }
    public void setSportTopicId(Long sportTopicId) { this.sportTopicId = sportTopicId; }
    public Integer getNodeTotal() { return nodeTotal; }
    public void setNodeTotal(Integer nodeTotal) { this.nodeTotal = nodeTotal; }
    public Integer getNodeDoneCount() { return nodeDoneCount; }
    public void setNodeDoneCount(Integer nodeDoneCount) { this.nodeDoneCount = nodeDoneCount; }
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }
    public Integer getMedalGold() { return medalGold; }
    public void setMedalGold(Integer medalGold) { this.medalGold = medalGold; }
    public Integer getMedalSilver() { return medalSilver; }
    public void setMedalSilver(Integer medalSilver) { this.medalSilver = medalSilver; }
    public Integer getMedalBronze() { return medalBronze; }
    public void setMedalBronze(Integer medalBronze) { this.medalBronze = medalBronze; }
    public Boolean getIsTopicTemplate() { return isTopicTemplate; }
    public void setIsTopicTemplate(Boolean isTopicTemplate) { this.isTopicTemplate = isTopicTemplate; }

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

    public void setMemberNickname(String memberNickname) 
    {
        this.memberNickname = memberNickname;
    }

    public String getMemberNickname() 
    {
        return memberNickname;
    }

    public void setMemberAvatar(String memberAvatar) 
    {
        this.memberAvatar = memberAvatar;
    }

    public String getMemberAvatar() 
    {
        return memberAvatar;
    }

    public void setMemberUserType(Long memberUserType) 
    {
        this.memberUserType = memberUserType;
    }

    public Long getMemberUserType() 
    {
        return memberUserType;
    }

    public void setMemberLevelId(Long memberLevelId) 
    {
        this.memberLevelId = memberLevelId;
    }

    public Long getMemberLevelId() 
    {
        return memberLevelId;
    }

    public void setCommentCount(Long commentCount) 
    {
        this.commentCount = commentCount;
    }

    public Long getCommentCount() 
    {
        return commentCount;
    }

    public void setNoLikeCount(Long noLikeCount) 
    {
        this.noLikeCount = noLikeCount;
    }

    public Long getNoLikeCount() 
    {
        return noLikeCount;
    }

    public void setLikeCount(Long likeCount) 
    {
        this.likeCount = likeCount;
    }

    public Long getLikeCount() 
    {
        return likeCount;
    }


    public void setIsLiked(Integer isLiked)
    {
        this.isLiked = isLiked;
    }

    public Integer getIsLiked()
    {
        return isLiked;
    }

    public void setIsBookmarked(Integer isBookmarked)
    {
        this.isBookmarked = isBookmarked;
    }

    public Integer getIsBookmarked()
    {
        return isBookmarked;
    }

    public void setIsFollowTheUser(Integer isFollowTheUser)
    {
        this.isFollowTheUser = isFollowTheUser;
    }

    public Integer getIsFollowTheUser()
    {
        return isFollowTheUser;
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
            .append("memberNickname", getMemberNickname())
            .append("memberAvatar", getMemberAvatar())
            .append("memberUserType", getMemberUserType())
            .append("memberLevelId", getMemberLevelId())
            .append("commentCount", getCommentCount())
            .append("noLikeCount", getNoLikeCount())
            .append("likeCount", getLikeCount())
                .append("isLiked", getIsLiked())
                .append("isFollowTheUser", getIsFollowTheUser())
            .toString();
    }
}
