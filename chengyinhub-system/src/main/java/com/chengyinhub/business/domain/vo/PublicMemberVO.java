package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 公开会员资料白名单。
 *
 * <p>该类型只承载明确允许公开展示的资料与聚合统计，禁止改为继承或包装 UmsMember。</p>
 */
public class PublicMemberVO {
    /**
     * 受邀注册时间(邀请记录页分组/展示;2026-08-07 前端一直「时间待同步」= 此前从未下发)。
     *
     * <p>⚠️ NON_NULL 不是省字节:本 VO 被公开会员列表 / 跨会员详情 / 邀请记录三个面共用,
     * 但 mapper 只在 {@code selectInvitedPublicMemberList} 里 select create_time。
     * 序列化 null 会让另外两个公开面凭空多出一个「注册时间」字段 —— 那是白名单外的扩面。
     * 有值才出现,等于把「谁能看到注册时间」交回给 mapper 的 select 列表决定。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;


    private Long id;
    private String nickname;
    private String avatar;
    private Long levelId;

    /**
     * 身份标签，封顶枚举：只可能是 "BIZ"(商家) / "CLUB"(俱乐部) / null(玩家不挂)。
     *
     * ⚠️ 这里**刻意不下发账号级的 user_type**：那个字段与 password / phone / openId 一样在
     * ApiUserPublicInfoTest 的匿名响应禁止列表里，而本接口匿名可达。user_type 是账号类型枚举，
     * 将来可能新增内部账号 / 封禁态等值，整枚放出去等于把账号状态面一并暴露。
     * 改为在 SQL 里就收敛成这三种取值，信息量严格更小，标签效果完全一样。
     */
    private String identityKind;
    private String introduction;
    private String casePics;
    private String caseIntroducton;
    private Integer topicNum;
    private Integer activityNum;
    private Integer followNum;
    private Integer fansNum;
    private Integer likeNum;
    /**
     * 观看者与被看者的关注关系。
     *
     * <p>⚠️ 这里**不能**加 NON_NULL:该键是 /api/user/{list,invite_list,info} 三个既有面
     * 已冻结的键集成员({@code ApiUmsMemberControllerPrivacyTest} 锁着)。匿名读时的做法是
     * 不查关注关系、让它保持 null —— 键在但不含任何观看者信息,而不是把键抽掉改动旧合同。</p>
     */
    private Integer isFollow;
    private Integer joinUserNum;
    private List<PublicCategoryVO> sysCategoryList;

    @JsonIgnore
    private String categoryIds;

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Long getLevelId() {
        return levelId;
    }

    public void setLevelId(Long levelId) {
        this.levelId = levelId;
    }

    public String getIdentityKind() {
        return identityKind;
    }

    public void setIdentityKind(String identityKind) {
        this.identityKind = identityKind;
    }

    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction;
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

    public Integer getLikeNum() {
        return likeNum;
    }

    public void setLikeNum(Integer likeNum) {
        this.likeNum = likeNum;
    }

    public Integer getIsFollow() {
        return isFollow;
    }

    public void setIsFollow(Integer isFollow) {
        this.isFollow = isFollow;
    }

    public Integer getJoinUserNum() {
        return joinUserNum;
    }

    public void setJoinUserNum(Integer joinUserNum) {
        this.joinUserNum = joinUserNum;
    }

    public List<PublicCategoryVO> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<PublicCategoryVO> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }

    public String getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(String categoryIds) {
        this.categoryIds = categoryIds;
    }

    /**
     * 会员公开标签白名单，避免透出分类状态、删除标记与审计字段。
     */
    public static class PublicCategoryVO {
        private Long id;
        private String categoryName;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public void setCategoryName(String categoryName) {
            this.categoryName = categoryName;
        }
    }
}
