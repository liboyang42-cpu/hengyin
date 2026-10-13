package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ViewRegistration;

/** 公开活动/主题详情中的参与者社交展示字段。 */
public class PublicRegistrationSummaryVO {
    private Long id;
    private Long memberId;
    private String avatar;
    private String nickname;

    public static PublicRegistrationSummaryVO from(ViewRegistration source) {
        PublicRegistrationSummaryVO out = new PublicRegistrationSummaryVO();
        if (source != null) {
            out.setId(source.getId());
            out.setMemberId(source.getMemberId());
            out.setAvatar(source.getAvatar());
            out.setNickname(source.getNickname());
        }
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}
