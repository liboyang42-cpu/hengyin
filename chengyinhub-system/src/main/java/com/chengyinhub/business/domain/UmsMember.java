package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户对象 ums_member
 * 
 * @author ruoyi
 * @date 2025-08-13
 */
public class UmsMember extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @Excel(name = "ID")
    private Long id;

    /** 用户类型：1普通会员 2商户会员 */
    @Excel(name = "用户类型",readConverterExp = "1=普通会员,2=商户会员")
    private Long userType;

    /** 会员角色 player/club/merchant（RBAC，新逻辑以此为准；userType 保留兼容） */
    @Excel(name = "会员角色")
    private String role;

    /** 登录手机号码 */
    @Excel(name = "登录手机号码")
    private String phone;

    /** 账号密码 */
    private String password;

    /** 会员等级 */
    private Long levelId;

    /** 真实姓名 */
    @Excel(name = "真实姓名")
    private String name;

    private String wechat;



    /** 状态 1启用  2禁用 */
    @Excel(name = "状态",readConverterExp = "1=启用,2=禁用")
    private Long status;

    /** 邀请人ID */
    @Excel(name = "邀请人ID")
    private String inviteUserId;

    /** 最后登录IP */
    private String loginIp;

    /** 最后登录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
//    @Excel(name = "最后登录时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date loginTime;

    /** 昵称 */
    @Excel(name = "昵称")
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 性别：0未知 1男 2女 */
    private Integer sex;

    /** 微信小程序OpenID */
    private String wxOpenId;

    /**
     * 微信开放平台「移动应用」openid(App 端)。
     *
     * ★ 与 wxOpenId(小程序 openid)是**两个应用的两个 id**,不可互换:
     *   微信按 (appid, openid) 认人,转账/支付传错组合会被判不匹配。
     * ⚠️ 存量数据里 App openid 曾被写进 wxOpenId,无法回溯区分;
     *   本列由 App 登录时渐进写入(见 ApiLoginController /login/wechat/app)。
     */
    private String appOpenId;

    /** 微信开放平台 unionid（跨小程序/App 归一） */
    private String unionId;

    /** Apple Sign in 唯一标识（identityToken 的 sub） */
    private String appleId;

    /** 所在地 */
    //@Excel(name = "所在地")
    private Long areaId;

    /** 出生日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    //@Excel(name = "出生日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date birthday;

    /** 会员备注 */
    @Excel(name = "会员备注")
    private String mark;

    /** 擅长领域ID集合，以逗号分隔 */
    //@Excel(name = "擅长领域")
    private String tagIds;


    /** 作品图片，逗号隔开 */
    private String casePics;

    /** 作品介绍 */
    @Excel(name = "作品介绍")
    private String caseIntroducton;



    /** 个人网址 */
    @Excel(name = "个人网址")
    private String website;


    /** 工作地点 */
    @Excel(name = "工作地点")
    private String workAddress;

    /** 个人介绍 */
    @Excel(name = "个人介绍")
    private String introduction;

    /** 余额 */
    @Excel(name = "余额")
    private BigDecimal balance;

    /** 积分 */
    @Excel(name = "积分")
    private Long point;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 注销状态：NORMAL/PENDING/COMPLETED。 */
    private String deregisterStatus;

    /** 注销申请时间。 */
    private Date deregisterApplyTime;

    /** 冷静期结束时间。 */
    private Date deregisterExecuteAfter;


    /** 商家信息 */
    private MmsMerchant merchant;



    /** 发布主题数量 */
    private Integer topicNum;

    /** 发布活动数量 */
    private Integer activityNum;

    /** 关注用户数量 */
    private Integer followNum;

    /** 是否关注 */
    private Integer isFollow;

    /** 粉丝数量 */
    private Integer fansNum;

    /** 用户点赞数 */
    private Integer likeNum;

    private  Date startTime;

    private  Date endTime;


    /** 报名用户 */
    private Integer joinUserNum;


    @Schema(description = "分类列表")
    private List<SysCategory> sysCategoryList;

    public String getWechat() {
        return wechat;
    }

    public void setWechat(String wechat) {
        this.wechat = wechat;
    }

    public Integer getLikeNum() {
        return likeNum;
    }

    public void setLikeNum(Integer likeNum) {
        this.likeNum = likeNum;
    }

    public Integer getJoinUserNum() {
        return joinUserNum;
    }



    public void setJoinUserNum(Integer joinUserNum) {
        this.joinUserNum = joinUserNum;
    }



    public List<SysCategory> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<SysCategory> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }

    public Integer getIsFollow() {
        return isFollow;
    }

    public void setIsFollow(Integer isFollow) {
        this.isFollow = isFollow;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setUserType(Long userType) 
    {
        this.userType = userType;
    }

    public Long getUserType()
    {
        return userType;
    }

    public void setRole(String role)
    {
        this.role = role;
    }

    public String getRole()
    {
        return role;
    }

    public void setPhone(String phone)
    {
        this.phone = phone;
    }

    public String getPhone() 
    {
        return phone;
    }

    public void setPassword(String password) 
    {
        this.password = password;
    }

    public String getPassword() 
    {
        return password;
    }

    public void setLevelId(Long levelId) 
    {
        this.levelId = levelId;
    }

    public Long getLevelId() 
    {
        return levelId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setInviteUserId(String inviteUserId) 
    {
        this.inviteUserId = inviteUserId;
    }

    public String getInviteUserId() 
    {
        return inviteUserId;
    }

    public void setLoginIp(String loginIp) 
    {
        this.loginIp = loginIp;
    }

    public String getLoginIp() 
    {
        return loginIp;
    }

    public void setLoginTime(Date loginTime) 
    {
        this.loginTime = loginTime;
    }

    public Date getLoginTime() 
    {
        return loginTime;
    }

    public void setNickname(String nickname) 
    {
        this.nickname = nickname;
    }

    public String getNickname() 
    {
        return nickname;
    }

    public void setAvatar(String avatar) 
    {
        this.avatar = avatar;
    }

    public String getAvatar() 
    {
        return avatar;
    }

    public void setSex(Integer sex) 
    {
        this.sex = sex;
    }

    public Integer getSex() 
    {
        return sex;
    }

    public void setWxOpenId(String wxOpenId) 
    {
        this.wxOpenId = wxOpenId;
    }

    public String getAppOpenId()
    {
        return appOpenId;
    }

    public void setAppOpenId(String appOpenId)
    {
        this.appOpenId = appOpenId;
    }

    public String getWxOpenId()
    {
        return wxOpenId;
    }

    public void setUnionId(String unionId)
    {
        this.unionId = unionId;
    }

    public String getUnionId()
    {
        return unionId;
    }

    public void setAppleId(String appleId)
    {
        this.appleId = appleId;
    }

    public String getAppleId()
    {
        return appleId;
    }

    public void setAreaId(Long areaId) 
    {
        this.areaId = areaId;
    }

    public Long getAreaId() 
    {
        return areaId;
    }

    public void setBirthday(Date birthday) 
    {
        this.birthday = birthday;
    }

    public Date getBirthday() 
    {
        return birthday;
    }

    public void setMark(String mark) 
    {
        this.mark = mark;
    }

    public String getMark() 
    {
        return mark;
    }

    public void setTagIds(String tagIds) 
    {
        this.tagIds = tagIds;
    }

    public String getTagIds() 
    {
        return tagIds;
    }


    public String getCasePics() {
        return casePics;
    }

    public void setCasePics(String casePics) {
        this.casePics = casePics;
    }

    public String getCaseIntroducton() {
        return caseIntroducton;
    }

    public void setCaseIntroducton(String caseIntroducton) {
        this.caseIntroducton = caseIntroducton;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getWorkAddress() {
        return workAddress;
    }

    public void setWorkAddress(String workAddress) {
        this.workAddress = workAddress;
    }

    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public void setPoint(Long point) 
    {
        this.point = point;
    }

    public Long getPoint() 
    {
        return point;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public void setDeregisterStatus(String deregisterStatus)
    {
        this.deregisterStatus = deregisterStatus;
    }

    public String getDeregisterStatus()
    {
        return deregisterStatus;
    }

    public void setDeregisterApplyTime(Date deregisterApplyTime)
    {
        this.deregisterApplyTime = deregisterApplyTime;
    }

    public Date getDeregisterApplyTime()
    {
        return deregisterApplyTime;
    }

    public void setDeregisterExecuteAfter(Date deregisterExecuteAfter)
    {
        this.deregisterExecuteAfter = deregisterExecuteAfter;
    }

    public Date getDeregisterExecuteAfter()
    {
        return deregisterExecuteAfter;
    }

    public MmsMerchant getMerchant() {
        return merchant;
    }

    public void setMerchant(MmsMerchant merchant) {
        this.merchant = merchant;
    }


    public Integer getTopicNum() {
        return topicNum;
    }

    public void setTopicNum(Integer topicNum) {
        this.topicNum = topicNum;
    }

    public Integer getActivityNum() {
        return activityNum;
    }

    public void setActivityNum(Integer activityNum) {
        this.activityNum = activityNum;
    }

    public Integer getFollowNum() {
        return followNum;
    }

    public void setFollowNum(Integer followNum) {
        this.followNum = followNum;
    }

    public Integer getFansNum() {
        return fansNum;
    }

    public void setFansNum(Integer fansNum) {
        this.fansNum = fansNum;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userType", getUserType())
            .append("phone", getPhone())
            .append("password", getPassword())
            .append("levelId", getLevelId())
            .append("name", getName())
            .append("status", getStatus())
            .append("inviteUserId", getInviteUserId())
            .append("loginIp", getLoginIp())
            .append("loginTime", getLoginTime())
            .append("nickname", getNickname())
            .append("avatar", getAvatar())
            .append("sex", getSex())
            .append("wxOpenId", getWxOpenId())
            .append("unionId", getUnionId())
            .append("appleId", getAppleId())
            .append("areaId", getAreaId())
            .append("birthday", getBirthday())
            .append("mark", getMark())
            .append("tagIds", getTagIds())
                .append("casePics", getCasePics())
                .append("caseIntroducton", getCaseIntroducton())
                .append("workAddress", getWorkAddress())
                .append("introduction", getIntroduction())
                .append("balance", getBalance())
            .append("point", getPoint())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
