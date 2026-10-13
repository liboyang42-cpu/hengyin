package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 单个俱乐部的付费会员开关；缺行等同关闭。 */
public class ClubMembershipSetting extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long clubId;
    private Boolean enabled;
    private Integer version;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
