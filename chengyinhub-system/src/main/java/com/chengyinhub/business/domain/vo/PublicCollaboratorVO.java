package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ViewCollaborators;

/** 公开详情中的阵容成员白名单，不包含手机号及账户状态。 */
public class PublicCollaboratorVO {
    private Long id;
    private Long memberId;
    private String memberNickname;
    private String memberAvatar;
    private String memberRealName;

    public static PublicCollaboratorVO from(ViewCollaborators source) {
        PublicCollaboratorVO out = new PublicCollaboratorVO();
        if (source != null) {
            out.setId(source.getId());
            out.setMemberId(source.getMemberId());
            out.setMemberNickname(source.getMemberNickname());
            out.setMemberAvatar(source.getMemberAvatar());
            out.setMemberRealName(source.getMemberRealName());
        }
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getMemberNickname() { return memberNickname; }
    public void setMemberNickname(String memberNickname) { this.memberNickname = memberNickname; }
    public String getMemberAvatar() { return memberAvatar; }
    public void setMemberAvatar(String memberAvatar) { this.memberAvatar = memberAvatar; }
    public String getMemberRealName() { return memberRealName; }
    public void setMemberRealName(String memberRealName) { this.memberRealName = memberRealName; }
}
