package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部主理人(达人)档案对象 club_leader
 *
 * D18/Option B：能力档案 + L1–L5 等级「挂主理人」(member_id 唯一,一人一档),
 * 名下多个俱乐部继承展示其等级。
 */
public class ClubLeader extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 主理人会员ID(唯一) */
    @Excel(name = "会员ID")
    private Long memberId;

    /** 真实/对外昵称 */
    private String leaderName;

    /** 联系电话 */
    private String phone;

    /** 身份说明 */
    private String identity;

    /** 联合创始人 */
    private String coFounders;

    /** 是否有过往组织经验 0否1是 */
    private Integer hasExperience;

    /** 过往活动经验(场次档) */
    private String experience;

    /** 最大活动人数 */
    private Integer maxEventSize;

    /** 平均活动人数 */
    private Integer avgEventSize;

    /** 能否设计路线 0否1是 */
    private Integer canDesignRoute;

    /** 能否设计任务 0否1是 */
    private Integer canDesignTask;

    /** 能否带NPC/角色 0否1是 */
    private Integer canNpc;

    /** 能否与商家联动 0否1是 */
    private Integer canMerchantCoop;

    /** 有无导游证/旅游资质 0否1是 */
    private Integer hasGuideCert;

    /** 资质/案例图(逗号分隔URL) */
    private String certImages;

    /** 等级 0未定级 1-5=L1-L5 */
    @Excel(name = "等级", readConverterExp = "0=未定级,1=L1,2=L2,3=L3,4=L4,5=L5")
    private Integer level;

    /** 标签(逗号分隔) */
    private String tags;

    /** 定级操作人 */
    private String auditBy;

    /** 定级时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date auditTime;

    /** 1正常 */
    private Integer status;

    /** del_flag */
    private Integer delFlag;

    /** 非持久:主理人昵称(列表 join 展示) */
    private String nickname;

    /** 非持久:主理人头像 */
    private String avatar;

    /** 非持久:名下俱乐部数 */
    private Integer clubCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }
    public String getCoFounders() { return coFounders; }
    public void setCoFounders(String coFounders) { this.coFounders = coFounders; }
    public Integer getHasExperience() { return hasExperience; }
    public void setHasExperience(Integer hasExperience) { this.hasExperience = hasExperience; }
    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }
    public Integer getMaxEventSize() { return maxEventSize; }
    public void setMaxEventSize(Integer maxEventSize) { this.maxEventSize = maxEventSize; }
    public Integer getAvgEventSize() { return avgEventSize; }
    public void setAvgEventSize(Integer avgEventSize) { this.avgEventSize = avgEventSize; }
    public Integer getCanDesignRoute() { return canDesignRoute; }
    public void setCanDesignRoute(Integer canDesignRoute) { this.canDesignRoute = canDesignRoute; }
    public Integer getCanDesignTask() { return canDesignTask; }
    public void setCanDesignTask(Integer canDesignTask) { this.canDesignTask = canDesignTask; }
    public Integer getCanNpc() { return canNpc; }
    public void setCanNpc(Integer canNpc) { this.canNpc = canNpc; }
    public Integer getCanMerchantCoop() { return canMerchantCoop; }
    public void setCanMerchantCoop(Integer canMerchantCoop) { this.canMerchantCoop = canMerchantCoop; }
    public Integer getHasGuideCert() { return hasGuideCert; }
    public void setHasGuideCert(Integer hasGuideCert) { this.hasGuideCert = hasGuideCert; }
    public String getCertImages() { return certImages; }
    public void setCertImages(String certImages) { this.certImages = certImages; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getAuditBy() { return auditBy; }
    public void setAuditBy(String auditBy) { this.auditBy = auditBy; }
    public Date getAuditTime() { return auditTime; }
    public void setAuditTime(Date auditTime) { this.auditTime = auditTime; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Integer getClubCount() { return clubCount; }
    public void setClubCount(Integer clubCount) { this.clubCount = clubCount; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("leaderName", getLeaderName())
            .append("level", getLevel())
            .append("status", getStatus())
            .toString();
    }
}
