package com.chengyinhub.business.domain.vo.ai;

/**
 * NPC 出场信息 VO。
 *
 * @author chengyinhub
 */
public class NpcProfileVO
{
    private Long profileId;
    private String name;
    private String avatar;
    private Integer scopeType;
    private Integer sort;
    private String greeting;

    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
}
