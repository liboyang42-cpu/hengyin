package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * 俱乐部动态对象 club_post
 */
public class ClubPost extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 俱乐部ID */
    private Long clubId;

    /** 作者会员ID */
    private Long authorMemberId;

    /** 正文 */
    private String content;

    /** 图片(逗号分隔URL) */
    private String images;

    /** 引用类型(口径同广场帖 dataType):1活动完赛 2主题/模板 3自由漫游成绩；NULL=普通图文帖 */
    private Integer refType;

    /** 被引用对象ID(口径同广场帖 dataId)：refType=1 时是 activityId，2/3 时是 topicId */
    private Long refId;

    /** 非持久：被引用对象投影出来的展示字段。字段名照抄广场帖，前端那套 variant
     *  判定(utils/feed-play-card.js)一个字都不用改。引用失效时整个为 null，卡片降级成图文帖。 */
    private String sportName;
    private String sportCover;
    private Long sportTopicId;
    private Boolean isTopicTemplate;
    /** 非持久：帖子带引用、但被引对象当前已删/下架/转私密 ⇒ true。只给「引用已不可见」,不投影标题与封面。 */
    private Boolean refUnavailable;

    /** 类型 0图文 1战报 2公告 */
    private Integer type;

    /** 点赞数 */
    private Integer likeCount;

    /** 评论数 */
    private Integer commentCount;

    /** 转发数 */
    private Integer repostCount;

    /** del_flag 0存在 2删除 */
    private Integer delFlag;

    /** 是否在俱乐部帖子流置顶；仅公告可置顶 */
    private Integer isPinned;

    /** 仅查询入参(P0-82 分页):每页非置顶条数、翻页游标(上一页最后一条非置顶帖 id) */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private Integer limit;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private Long beforeId;

    /** 置顶时间 */
    private Date pinnedAt;

    /** 乐观锁版本；编辑/置顶每次递增 */
    private Integer version;

    /** 非持久：作者昵称(列表 join 展示) */
    private String nickname;

    /** 非持久：作者头像 */
    private String avatar;

    /** 非持久：当前用户是否已点赞 */
    private Boolean isLiked;

    /** 当前用户是否已转发(非表字段) */
    private Boolean viewerReposted;

    /** 非持久:当前查看者是否已关注作者(1/0),帖文卡头像「+」关注用 */
    private Integer isFollowTheUser;
    public Integer getIsFollowTheUser() { return isFollowTheUser; }
    public void setIsFollowTheUser(Integer isFollowTheUser) { this.isFollowTheUser = isFollowTheUser; }

    /** 非持久:聚合动态流用 —— 一条帖子在 feed 里必须说清是哪个俱乐部发的 */
    private String clubName;
    private String clubLogo;

    /** 非持久：是否存在正文/图片编辑快照；置顶也会递增 version，不能用 version 推断。 */
    private Boolean edited;

    /** 非持久：当前查看者在该俱乐部是否拥有内容管理权限。 */
    private Boolean viewerCanManage;

    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }
    public String getClubLogo() { return clubLogo; }
    public void setClubLogo(String clubLogo) { this.clubLogo = clubLogo; }
    public Boolean getEdited() { return edited; }
    public void setEdited(Boolean edited) { this.edited = edited; }
    public Boolean getViewerCanManage() { return viewerCanManage; }
    public void setViewerCanManage(Boolean viewerCanManage) { this.viewerCanManage = viewerCanManage; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getAuthorMemberId() { return authorMemberId; }
    public void setAuthorMemberId(Long authorMemberId) { this.authorMemberId = authorMemberId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public Integer getRefType() { return refType; }
    public void setRefType(Integer refType) { this.refType = refType; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public String getSportName() { return sportName; }
    public void setSportName(String sportName) { this.sportName = sportName; }
    public String getSportCover() { return sportCover; }
    public void setSportCover(String sportCover) { this.sportCover = sportCover; }
    public Long getSportTopicId() { return sportTopicId; }
    public void setSportTopicId(Long sportTopicId) { this.sportTopicId = sportTopicId; }
    public Boolean getIsTopicTemplate() { return isTopicTemplate; }
    public void setIsTopicTemplate(Boolean isTopicTemplate) { this.isTopicTemplate = isTopicTemplate; }
    public Boolean getRefUnavailable() { return refUnavailable; }
    public void setRefUnavailable(Boolean refUnavailable) { this.refUnavailable = refUnavailable; }
    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }
    public Integer getLikeCount() { return likeCount; }
    public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Integer getRepostCount() { return repostCount; }
    public void setRepostCount(Integer repostCount) { this.repostCount = repostCount; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Integer getIsPinned() { return isPinned; }
    public void setIsPinned(Integer isPinned) { this.isPinned = isPinned; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public Long getBeforeId() { return beforeId; }
    public void setBeforeId(Long beforeId) { this.beforeId = beforeId; }
    public Date getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(Date pinnedAt) { this.pinnedAt = pinnedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Boolean getIsLiked() { return isLiked; }
    public void setIsLiked(Boolean isLiked) { this.isLiked = isLiked; }
    public Boolean getViewerReposted() { return viewerReposted; }
    public void setViewerReposted(Boolean viewerReposted) { this.viewerReposted = viewerReposted; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("clubId", getClubId())
            .append("authorMemberId", getAuthorMemberId())
            .append("type", getType())
            .append("isPinned", getIsPinned())
            .append("version", getVersion())
            .toString();
    }
}
