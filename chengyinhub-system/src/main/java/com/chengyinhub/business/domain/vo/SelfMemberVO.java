package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 「查自己」的会员资料白名单投影(POST /api/user/info 的本人分支)。
 *
 * <p>为什么不再直接下发 {@link UmsMember} 整实体:它零 Jackson 保护,password 靠
 * controller 里一句 setPassword("") 的黑名单兜着,unionId/appOpenId/appleId/loginIp/
 * 真实姓名/内部备注/登录时间/注销态/商家实体(含 accountStatus)全都会跟着响应走出去;
 * 任何新接口复制一次 success(umsMember) 就多一个泄漏面。</p>
 *
 * <p>字段按「小程序实际读取」逐条白名单(2026-09-15 全仓 grep 小程序调用方,证据见
 * 修复交接材料/worktree收口_0915/安全审查修复批_报告.md §3):nickname/avatar/角色/余额/
 * 积分/等级/关注数/创作统计/资料编辑字段/注册时间等是页面在读的;unionId、appleId、
 * appOpenId、wxOpenId、loginIp、password、name 之外的账号字段一律不下发。</p>
 *
 * <p>⚠️ 往这里加字段的唯一理由 = 有调用方在读。不要把本类改回继承或包装 UmsMember
 * (那等于白名单失效,又回到整实体下发)。</p>
 */
public class SelfMemberVO {
    private Long id;
    private Long userType;
    private String role;
    private String phone;
    private String name;
    private String wechat;
    private Long levelId;
    private String nickname;
    private String avatar;
    private String casePics;
    private String caseIntroducton;
    private String website;
    private String introduction;
    private BigDecimal balance;
    private Long point;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private Integer followNum;
    private Integer fansNum;
    private Integer topicNum;
    private Integer activityNum;
    private Integer likeNum;
    private Integer isFollow;
    private List<SysCategory> sysCategoryList;

    public static SelfMemberVO from(UmsMember member) {
        SelfMemberVO out = new SelfMemberVO();
        out.setId(member.getId());
        out.setUserType(member.getUserType());
        out.setRole(member.getRole());
        out.setPhone(member.getPhone());
        out.setName(member.getName());
        out.setWechat(member.getWechat());
        out.setLevelId(member.getLevelId());
        out.setNickname(member.getNickname());
        out.setAvatar(member.getAvatar());
        out.setCasePics(member.getCasePics());
        out.setCaseIntroducton(member.getCaseIntroducton());
        out.setWebsite(member.getWebsite());
        out.setIntroduction(member.getIntroduction());
        out.setBalance(member.getBalance());
        out.setPoint(member.getPoint());
        out.setCreateTime(member.getCreateTime());
        out.setFollowNum(member.getFollowNum());
        out.setFansNum(member.getFansNum());
        out.setTopicNum(member.getTopicNum());
        out.setActivityNum(member.getActivityNum());
        out.setLikeNum(member.getLikeNum());
        out.setIsFollow(member.getIsFollow());
        out.setSysCategoryList(member.getSysCategoryList());
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }

    public Long getUserType() { return userType; }
    public void setUserType(Long value) { userType = value; }

    public String getRole() { return role; }
    public void setRole(String value) { role = value; }

    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }

    public String getName() { return name; }
    public void setName(String value) { name = value; }

    public String getWechat() { return wechat; }
    public void setWechat(String value) { wechat = value; }

    public Long getLevelId() { return levelId; }
    public void setLevelId(Long value) { levelId = value; }

    public String getNickname() { return nickname; }
    public void setNickname(String value) { nickname = value; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String value) { avatar = value; }

    public String getCasePics() { return casePics; }
    public void setCasePics(String value) { casePics = value; }

    public String getCaseIntroducton() { return caseIntroducton; }
    public void setCaseIntroducton(String value) { caseIntroducton = value; }

    public String getWebsite() { return website; }
    public void setWebsite(String value) { website = value; }

    public String getIntroduction() { return introduction; }
    public void setIntroduction(String value) { introduction = value; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal value) { balance = value; }

    public Long getPoint() { return point; }
    public void setPoint(Long value) { point = value; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }

    public Integer getFollowNum() { return followNum; }
    public void setFollowNum(Integer value) { followNum = value; }

    public Integer getFansNum() { return fansNum; }
    public void setFansNum(Integer value) { fansNum = value; }

    public Integer getTopicNum() { return topicNum; }
    public void setTopicNum(Integer value) { topicNum = value; }

    public Integer getActivityNum() { return activityNum; }
    public void setActivityNum(Integer value) { activityNum = value; }

    public Integer getLikeNum() { return likeNum; }
    public void setLikeNum(Integer value) { likeNum = value; }

    public Integer getIsFollow() { return isFollow; }
    public void setIsFollow(Integer value) { isFollow = value; }

    public List<SysCategory> getSysCategoryList() { return sysCategoryList; }
    public void setSysCategoryList(List<SysCategory> value) { sysCategoryList = value; }
}
