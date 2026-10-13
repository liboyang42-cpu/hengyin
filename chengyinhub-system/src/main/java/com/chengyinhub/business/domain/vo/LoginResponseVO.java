package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.UmsMember;

/** 登录成功后返回给 App/小程序的最小公开会员信息。 */
public class LoginResponseVO
{
    private final Long id;
    private final Long userType;
    private final String avatar;
    private final String nickname;
    private final String role;

    private LoginResponseVO(UmsMember member)
    {
        this.id = member.getId();
        this.userType = member.getUserType();
        this.avatar = member.getAvatar();
        this.nickname = member.getNickname();
        this.role = member.getRole();
    }

    public static LoginResponseVO from(UmsMember member)
    {
        return new LoginResponseVO(member);
    }

    public Long getId()
    {
        return id;
    }

    public Long getUserType()
    {
        return userType;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public String getNickname()
    {
        return nickname;
    }

    public String getRole()
    {
        return role;
    }
}
