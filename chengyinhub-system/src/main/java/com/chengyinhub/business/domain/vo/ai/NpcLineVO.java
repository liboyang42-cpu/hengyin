package com.chengyinhub.business.domain.vo.ai;

/**
 * NPC 事件冒泡话术 VO。
 *
 * @author chengyinhub
 */
public class NpcLineVO
{
    private Long profileId;
    private String name;
    private String avatar;
    private String line;

    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getLine() { return line; }
    public void setLine(String line) { this.line = line; }
}
