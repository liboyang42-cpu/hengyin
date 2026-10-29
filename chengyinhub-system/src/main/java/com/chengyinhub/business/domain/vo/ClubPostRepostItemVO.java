package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubPost;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * 动态流里的「转发」条目(dw29 L8)。与普通帖文同列在一个数组里,靠 itemType 区分;
 * 原帖整条嵌在 post 里,点赞/评论/转发都作用在原帖上。
 */
public class ClubPostRepostItemVO {
    public static final String ITEM_TYPE = "repost";

    private Long repostId;
    private Long reposterMemberId;
    private String reposterNick;
    private String reposterAvatar;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date repostTime;
    private ClubPost post;

    public String getItemType() { return ITEM_TYPE; }
    public Long getRepostId() { return repostId; }
    public void setRepostId(Long repostId) { this.repostId = repostId; }
    public Long getReposterMemberId() { return reposterMemberId; }
    public void setReposterMemberId(Long reposterMemberId) { this.reposterMemberId = reposterMemberId; }
    public String getReposterNick() { return reposterNick; }
    public void setReposterNick(String reposterNick) { this.reposterNick = reposterNick; }
    public String getReposterAvatar() { return reposterAvatar; }
    public void setReposterAvatar(String reposterAvatar) { this.reposterAvatar = reposterAvatar; }
    public Date getRepostTime() { return repostTime; }
    public void setRepostTime(Date repostTime) { this.repostTime = repostTime; }
    public ClubPost getPost() { return post; }
    public void setPost(ClubPost post) { this.post = post; }
}
