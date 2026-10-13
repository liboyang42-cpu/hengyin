package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 合作者视图对象 view_collaborators
 * 
 * @author webxh
 * @date 2025-09-04
 */
public class ViewCollaborators extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 类型1、主题  2、活动  */
    @Excel(name = "类型1、主题  2、活动 ")
    private Long dataType;

    /** 主题ID/活动ID */
    @Excel(name = "主题ID/活动ID")
    private Long dataId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 昵称 */
    @Excel(name = "昵称")
    private String memberNickname;

    /** 头像 */
    @Excel(name = "头像")
    private String memberAvatar;

    /** 登录手机号码 */
    @Excel(name = "登录手机号码")
    private String memberPhone;

    /** 真实姓名 */
    @Excel(name = "真实姓名")
    private String memberRealName;

    /** 性别：0未知 1男 2女 */
    @Excel(name = "性别：0未知 1男 2女")
    private Integer memberSex;

    /** 状态 1启用  2禁用 */
    @Excel(name = "状态 1启用  2禁用")
    private Long memberStatus;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setDataType(Long dataType) 
    {
        this.dataType = dataType;
    }

    public Long getDataType() 
    {
        return dataType;
    }

    public void setDataId(Long dataId) 
    {
        this.dataId = dataId;
    }

    public Long getDataId() 
    {
        return dataId;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
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

    public void setMemberPhone(String memberPhone) 
    {
        this.memberPhone = memberPhone;
    }

    public String getMemberPhone() 
    {
        return memberPhone;
    }

    public void setMemberRealName(String memberRealName) 
    {
        this.memberRealName = memberRealName;
    }

    public String getMemberRealName() 
    {
        return memberRealName;
    }

    public void setMemberSex(Integer memberSex) 
    {
        this.memberSex = memberSex;
    }

    public Integer getMemberSex() 
    {
        return memberSex;
    }

    public void setMemberStatus(Long memberStatus) 
    {
        this.memberStatus = memberStatus;
    }

    public Long getMemberStatus() 
    {
        return memberStatus;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("dataType", getDataType())
            .append("dataId", getDataId())
            .append("memberId", getMemberId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("memberNickname", getMemberNickname())
            .append("memberAvatar", getMemberAvatar())
            .append("memberPhone", getMemberPhone())
            .append("memberRealName", getMemberRealName())
            .append("memberSex", getMemberSex())
            .append("memberStatus", getMemberStatus())
            .toString();
    }
}
