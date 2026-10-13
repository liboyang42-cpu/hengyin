package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.UmsMember;

/** 公开发布者资料白名单。 */
public class PublicMemberSummaryVO {
    private Long id;
    private String nickname;
    private String avatar;

    public static PublicMemberSummaryVO from(UmsMember source) {
        PublicMemberSummaryVO out = new PublicMemberSummaryVO();
        if (source != null) {
            out.setId(source.getId());
            out.setNickname(source.getNickname());
            out.setAvatar(source.getAvatar());
        }
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}
