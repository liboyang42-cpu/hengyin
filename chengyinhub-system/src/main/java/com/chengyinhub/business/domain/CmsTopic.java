package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 主题对象 cms_topic
 * 
 * @author webxh
 * @date 2025-09-12
 */
public class CmsTopic extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /**  */
    private Long id;

    /**
     * 发布者 memberId。
     *
     * ⚠️ 原注释写「商家不能发布」,**2026-08-07 核实已过时**,别再拿它当依据:
     * · 小程序链路**放行商家**(CmsTopicServiceImpl 内容槽对 MERCHANT 不区分,
     *   走同一条创作者链路),且生产 user_permission 里 merchant 的 can_create_theme=1、max_themes=3;
     * · 只有**后台** createTopicByPublisher 仍拒 MERCHANT,而它拒的理由正是引用这句注释 ——
     *   两处互相引用,谁也没重新核过。
     * 结论:这句话现在只对后台那一个入口成立。两边口径是否要对齐,属产品决策,未拍板前不擅改。
     */
    @Excel(name = "用户ID-发布者")
    private Long memberId;

    /**
     * 主题列表的来源范围输入。官方/广场必须由生效 CONTENT 槽分流，不能再用 topicType。
     * 未知值在 mapper 中 fail-closed 为无结果。
     */
    @JsonIgnore
    private String contentScope;

    /**
     * 列表只读投影的内容主体类型；PLATFORM / CLUB / MEMBER 为已识别主体，
     * null 表示历史待归属，其余值表示需要人工处置的异常 CONTENT 槽。
     * 不返回 entityId，避免把主体识别退化为裸 ID 展示。
     */
    private String contentPublisherType;

    /** 列表投影的内容主体公开显示名；无槽时为 null，不回退到收款账号。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String contentPublisherName;

    /** 俱乐部内容主体的主理人公开显示名；会员主体不额外暴露内部账号。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String contentPublisherLeaderName;

    /** 主题列表的只读运营摘要；其他主题接口不投影时不输出 null。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO operationSummary;

    /** 以下三个字段只作为后台列表筛选输入，绝不回显或持久化。 */
    @JsonIgnore
    private String clubFulfillmentState;
    @JsonIgnore
    private String merchantSupplyState;
    @JsonIgnore
    private String runReadiness;

    /** 以下字段为后台运营筛选输入，均不回显也不持久化。 */
    @JsonIgnore
    private Integer depositStatus;
    @JsonIgnore
    private String cityKeyword;
    @JsonIgnore
    private Date operationStartDate;
    @JsonIgnore
    private Date operationEndDate;
    @JsonIgnore
    private Boolean merchantSupplyGapOnly;
    @JsonIgnore
    private Boolean pendingClubApplicationOnly;
    @JsonIgnore
    private Boolean inProgressAnomalyOnly;
    /** C 端通用列表专用：隐藏父俱乐部已解散的主题；后台审计查询不设置。 */
    @JsonIgnore
    private Boolean activeClubOnly;

    /**
     * 非持久:只要「商家真的能承接」的那些主题 —— 商家侧合作中心那条池子专用。
     * 它比 merchant_status 多管两道闸:主题级 club_coop_open(稿 J4)与俱乐部级
     * merchant_undertake_open(稿 M)。两者都按 null=开 处理,与加这两列之前的行为一致。
     */
    private Boolean merchantPoolOnly;

    /** 所属俱乐部ID（经典定向团方案B）：仅经典定向团写入，其余主题为 NULL */
    private Long clubId;

    /** 非持久：该团已报名人数(/api/club/topics 富化) */
    private Integer signupCount;

    /** 非持久：服务端草稿(is_draft=1)。仅 /api/topic/list?is_my=1 富化,供「我的」项目预览与 /api/project/my 同口径标草稿 */
    private Boolean isDraft;

    /** 非持久化:我的主题列表带 invite_target 时回填 —— 此刻能否以该主题发起协作邀约(与写闸同一判据)。 */
    private Boolean inviteWindowOpen;

    /** 非持久：该团成团状态(/api/club/topics 富化,取经典定向票 team_status:0募集中 1已成团 2未成团已散) */
    private Integer teamStatus;

    /** 主题类型：1平台主题  2自定义主题 */
    @Excel(name = "主题类型：1平台主题  2自定义主题")
    private Long topicType;

    /**
     * P0-28 批1:锁价(lifecycle 2→3)那一刻冻结的费率快照。
     *
     * <p><b>为什么必须冻</b>:定价时固化的是**价格**(写进 oms_ticket.price / self_play_price),
     * 而结算时原本是**现取** PricingServiceImpl 的常量 ⇒ 把平台率从 0.10 调成 0.15,
     * **已售票的存量主题**结算时平台多抽 5%,直接从发起人毛利里挖 → margin<0 撞守恒断言 →
     * 拒结、款项悬空。**改一个数追溯改写所有已售未结订单。**
     *
     * <p>NULL = 未锁价 / 存量主题 ⇒ 读侧回落 settlement_rule 的 live 值(与快照落地前行为一致)。
     *
     * <p>⚠️ 保底率冻的是**公式参数**(base/step/max)不是算好的率 —— 保底率 =
     * base + step × min(已核销商家数, max),而已核销商家数只有结算时才知道,锁价时算不出。
     */
    private java.math.BigDecimal feePlatformRate;
    private java.math.BigDecimal feeGuaranteeBaseRate;
    private java.math.BigDecimal feeGuaranteeStepRate;
    private Integer feeGuaranteeMaxMerchant;

    /**
     * P0-02 产品真源:1经典定向(①②共存) 2自由定向(③)。取值见 {@link com.chengyinhub.common.constant.ProductType}。
     * NULL = 存量未回填/混票待人工 ⇒ 读侧须回落票 mode 推断,别当「不是产品」。
     * oms_ticket.mode 自本字段起降级为派生列。
     */
    @Excel(name = "产品类型：1经典定向 2自由定向")
    private Integer productType;

    /** 第二组圈层主题码；NULL 表示普通主题。 */
    private String circleThemeCode;

    /** 从主题模板复制出的谱系；模板本体与普通主题为 NULL。 */
    private Long sourceTemplateTopicId;

    /** 城市代理最后一次复核本实例全部有效供给的时间。 */
    private Date circleReviewedAt;

    /** 本期是否允许漫游首次发现卡导流：0关闭 1开启；默认关闭。 */
    private Integer roamReferralEnabled;

    /** 自由探索完成规则 JSON；NULL 保持存量「必选章全核销」语义。 */
    private String completeRuleJson;

    /** 主题地点路线模式：LINEAR / BRANCH_GRAPH；NULL 按 LINEAR。 */
    private String routeMode;

    /** 主题级地点路线草稿；节点玩法模板不得持有城市 nodeId。 */
    private String routeGraphJson;

    /** 路线草稿编辑版本；只用于作者保存 CAS。 */
    private Long configVersion;

    /** 最近一次成功发布的路线快照；NULL 表示当前按 LINEAR 运行或尚未发布分支路线。 */
    private String publishedRouteGraphJson;

    /** 最近一次成功发布的路线版本；玩家路线局按此版本冻结快照。 */
    private Long publishedConfigVersion;

    /** 三身份活动级游戏模组定义；开局准备时冻结到 game_session。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String gameModuleConfigJson;

    /** 主题数值规则 journeyRules（HP/幸运/难度 DC/恢复）；NULL = 未启用，老主题行为不变。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String journeyRulesJson;

    /** 主题级剧情配置 journeyStory（开场通话/阶段结算/结局）；NULL = 未配置，老主题行为不变。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String journeyStoryJson;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String themeRuntimeConfigJson;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String publishedThemeRuntimeConfigJson;

    /** 主题画面风格（风格 / 参数 / 转场 / 故事字体），见 ThemeLook；NULL 或空串 = 默认风格，老主题外观不变。 */
    private String lookJson;

    /** 客户端入口开关；服务端从已加载配置校验后赋值，绝不替代配置真源。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private transient Boolean gameModuleEnabled;

    /** 选择主题的ID */
    @Excel(name = "选择主题的ID")
    private Long topicId;

    /** 主题名称 */
    @Excel(name = "主题名称")
    private String name;


    /** 副标题 */
    @Excel(name = "副标题")
    private String subtitle;


    /** 主题描述 */
    @Excel(name = "主题描述")
    private String description;

    /** 总时长，单位秒 */
    @Excel(name = "总时长，单位秒")
    private Long totalTime;

    /** 总里程KM */
    @Excel(name = "总里程KM")
    private BigDecimal totalMileage;

    /** 地点数 */
    @Excel(name = "地点数")
    private Long locationCount;

    @Excel(name = "地点")
    private String addressName;


    /** 模版数 */
    @Excel(name = "模版数")
    private Long templateCount;

    /** 主题开始日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "主题开始日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startDate;

    /** 主题结束日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "主题结束日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endDate;

    /** 主题长图（单张） */
    @Excel(name = "主题长图", readConverterExp = "单=张")
    private String imgUrl;

    /** 主题轮播图（多张）分号隔开 */
    @Excel(name = "主题轮播图", readConverterExp = "多=张")
    private String imgArr;

    /** 主题类别IDS，逗号隔开 */
    @Excel(name = "主题类别IDS，逗号隔开")
    private String categoryIds;

    /** 后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败； */
    @Excel(name = "后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Integer status;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String reason;

    /** 用户端状态：0未上架  1已上架 */
    @Excel(name = "用户端状态：0未上架  1已上架")
    private Integer userStatus;

    /** 商家端状态：0未上架  1已上架  */
    @Excel(name = "商家端状态：0未上架  1已上架 ")
    private Integer merchantStatus;

    /** 开放范围·俱乐部承接(P0-4):0不开放 1开放给俱乐部申请承接(镜像 merchant_status 商家池) */
    private Integer openClubPool;

    /** 商家报名开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "商家报名开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date merchantSignUpStartDate;

    /** 商家报名结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "商家报名结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date merchantSignUpEndDate;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 是否同步到创意广场1 同步 */
    @Excel(name = "是否同步到创意广场1 同步")
    private Integer isSync;

    @Schema(description = "主题分类列表")
    private List<SysCategory> sysCategoryList;

    @Schema(description = "合作者列表")
    private List<ViewCollaborators> cmsCollaboratorsList;

    @Schema(description = "模板列表")
    private List<CmsMemberTemplate> cmsMemberTemplateList;

    @Schema(description = "商家列表")
    private List<CmsRegistrationMerchant> cmsRegistrationMerchantList;

    @Schema(description = "章节列表")
    private List<CmsTopicChapter> cmsTopicChapterList;


    private List<OmsTicket> omsTicketList;

    private Integer isLike;

    private Long isLikeMemberId;

    /**
     * 查询侧入参:当前看的人是谁。只喂 club_member_only 那道闸(见 CmsTopicMapper.xml
     * clubMemberOnlyGate),不落库、不出 JSON 语义。为空 = 未登录 = 看不到只对成员可见的主题。
     */
    private Long viewerMemberId;


    /** 自己是否关注该用户  0未关注  1已关注*/
    private Integer isFollowTheUser;



    /** 销售金额 */
    private BigDecimal salesAmout;



    /** 浏览次数 */
    private Long viewCount;

    private  Double rating;


    private  Integer ratingTotal;


    private  BigDecimal minAmout;


    private  Integer checkStatus;

    private  Integer isSettlement;

    private  Integer isRecommend;


    private List<TopicChapterVO> chaptersList;


    private  UmsMember umsMember;

    private Integer isNew;

    /** 非持久：该主题下报名通过的商家数(后台管理页展示) */
    private Integer merchantCount;

    /**
     * 非持久：主题级剩余商家名额,由各开放招商章节的剩余名额汇总而来
     * (见 {@code TopicMerchantCapacity})。
     *
     * <p>⚠️ {@code null} 表示<b>不招商或不限名额</b>,不是「已满」——
     * 消费方不得把 null 渲染成「已满」。{@code 0} 才是真的满了。
     */
    private Integer remainingMerchantCount;

    /** 发布来源：simple=建议版 pro=专业版(默认) */
    private String publishMode;

    /** 1=Beta试玩期(pro主题过审先进Beta,作者转正归0);simple免审直发不进Beta */
    private Integer betaFlag;

    /** 主题音频URL(OSS) */
    private String audioUrl;

    /** 主题音频时长(秒) */
    private Integer audioDuration;

    /** M2 是否开放自玩票 0否1是 */
    private Integer selfPlay;

    /** M2 自玩票价格(元) */
    private java.math.BigDecimal selfPlayPrice;

    /** M2 自玩票数/配额(NULL 或 <=0 = 不限;仅经典定向有意义) */
    private Integer selfPlayQuota;

    /** 组队入口：0关闭 1报名时组队 2报名后邀请。 */
    private Integer teamMode;

    /** 玩家可选队伍人数的主题级上限。 */
    private Integer teamMaxMembers;

    /** M7 主题探索值预算(Σ节点xp ≤ 预算) */
    private Integer xpBudget;

    /** 招募截止时间(到期触发 TopicRecruitDeadlineTask 判定) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date recruitDeadline;

    /** 招募期已延长次数(最多 2 次) */
    private Integer recruitExtendCount;

    /** 生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭 7立项待审；未知值拒绝。 */
    private Integer lifecycle;

    /** 俱乐部开关(Figma J4):1=本主题出现在商家可承接列表里。默认 1，与加这列之前的行为等价。 */
    private Integer clubCoopOpen;
    /** 俱乐部开关:1=在俱乐部主页「活动」tab 排最前。 */
    private Integer clubPinned;
    /** 俱乐部开关:1=只对俱乐部成员可见。 */
    private Integer clubMemberOnly;

    /** 本期所有可售 SKU 的整期硬售止（排他上界）。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date saleEndAt;

    /** 本期首次成功进入 SELLING 的服务端实时时刻。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date sellingStartedAt;

    /** 再开一期的来源 cms_topic.id；普通新建为空，禁止复用历史 topicId。 */
    private Long sourceTopicId;

    public Integer getXpBudget() { return xpBudget; }
    public void setXpBudget(Integer xpBudget) { this.xpBudget = xpBudget; }

    /** P0.5 通关勋章名(fabu 配置;空=默认"主题名·通关") */
    private String finishMedalName;
    /** P0.5 通关勋章图(可空) */
    private String finishMedalImg;
    /** 主题通关奖励券 sms_coupon.id(空=仅通关勋章/XP) */
    private Long completeRewardCouponId;

    public String getFinishMedalName() { return finishMedalName; }
    public void setFinishMedalName(String finishMedalName) { this.finishMedalName = finishMedalName; }
    public String getFinishMedalImg() { return finishMedalImg; }
    public void setFinishMedalImg(String finishMedalImg) { this.finishMedalImg = finishMedalImg; }
    public Long getCompleteRewardCouponId() { return completeRewardCouponId; }
    public void setCompleteRewardCouponId(Long completeRewardCouponId) { this.completeRewardCouponId = completeRewardCouponId; }

    public Date getRecruitDeadline() { return recruitDeadline; }
    public void setRecruitDeadline(Date recruitDeadline) { this.recruitDeadline = recruitDeadline; }
    public Integer getRecruitExtendCount() { return recruitExtendCount; }
    public void setRecruitExtendCount(Integer recruitExtendCount) { this.recruitExtendCount = recruitExtendCount; }
    public Integer getClubCoopOpen() { return clubCoopOpen; }
    public void setClubCoopOpen(Integer clubCoopOpen) { this.clubCoopOpen = clubCoopOpen; }
    public Integer getClubPinned() { return clubPinned; }
    public void setClubPinned(Integer clubPinned) { this.clubPinned = clubPinned; }
    public Integer getClubMemberOnly() { return clubMemberOnly; }
    public void setClubMemberOnly(Integer clubMemberOnly) { this.clubMemberOnly = clubMemberOnly; }
    public Integer getLifecycle() { return lifecycle; }
    public void setLifecycle(Integer lifecycle) { this.lifecycle = lifecycle; }
    public Date getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }
    public Date getSellingStartedAt() { return sellingStartedAt; }
    public void setSellingStartedAt(Date sellingStartedAt) { this.sellingStartedAt = sellingStartedAt; }
    public Long getSourceTopicId() { return sourceTopicId; }
    public void setSourceTopicId(Long sourceTopicId) { this.sourceTopicId = sourceTopicId; }

    public String getPublishMode() {
        return publishMode;
    }

    public void setPublishMode(String publishMode) {
        this.publishMode = publishMode;
    }

    public Integer getSelfPlay() {
        return selfPlay;
    }

    public void setSelfPlay(Integer selfPlay) {
        this.selfPlay = selfPlay;
    }

    public java.math.BigDecimal getSelfPlayPrice() {
        return selfPlayPrice;
    }

    public void setSelfPlayPrice(java.math.BigDecimal selfPlayPrice) {
        this.selfPlayPrice = selfPlayPrice;
    }

    public Integer getSelfPlayQuota() {
        return selfPlayQuota;
    }

    public void setSelfPlayQuota(Integer selfPlayQuota) {
        this.selfPlayQuota = selfPlayQuota;
    }

    public Integer getTeamMode() { return teamMode; }
    public void setTeamMode(Integer teamMode) { this.teamMode = teamMode; }
    public Integer getTeamMaxMembers() { return teamMaxMembers; }
    public void setTeamMaxMembers(Integer teamMaxMembers) { this.teamMaxMembers = teamMaxMembers; }
    public Integer getBetaFlag() { return betaFlag; }
    public void setBetaFlag(Integer betaFlag) { this.betaFlag = betaFlag; }

    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public Integer getAudioDuration() { return audioDuration; }
    public void setAudioDuration(Integer audioDuration) { this.audioDuration = audioDuration; }

    public Integer getIsNew() {
        return isNew;
    }

    public void setIsNew(Integer isNew) {
        this.isNew = isNew;
    }

    public Integer getMerchantCount() { return merchantCount; }
    public void setMerchantCount(Integer merchantCount) { this.merchantCount = merchantCount; }
    public Integer getRemainingMerchantCount() { return remainingMerchantCount; }
    public void setRemainingMerchantCount(Integer remainingMerchantCount) { this.remainingMerchantCount = remainingMerchantCount; }

    public List<TopicChapterVO> getChaptersList() {
        return chaptersList;
    }

    public void setChaptersList(List<TopicChapterVO> chaptersList) {
        this.chaptersList = chaptersList;
    }



    public Integer getIsRecommend() {
        return isRecommend;
    }

    public void setIsRecommend(Integer isRecommend) {
        this.isRecommend = isRecommend;
    }

    public UmsMember getUmsMember() {
        return umsMember;
    }

    public void setUmsMember(UmsMember umsMember) {
        this.umsMember = umsMember;
    }

    public Long getIsLikeMemberId() {
        return isLikeMemberId;
    }

    public void setIsLikeMemberId(Long isLikeMemberId) {
        this.isLikeMemberId = isLikeMemberId;
    }

    public Long getViewerMemberId() {
        return viewerMemberId;
    }

    public void setViewerMemberId(Long viewerMemberId) {
        this.viewerMemberId = viewerMemberId;
    }

    public Integer getIsSettlement() {
        return isSettlement;
    }

    public void setIsSettlement(Integer isSettlement) {
        this.isSettlement = isSettlement;
    }

    public Integer getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(Integer checkStatus) {
        this.checkStatus = checkStatus;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public BigDecimal getMinAmout() {
        return minAmout;
    }

    public void setMinAmout(BigDecimal minAmout) {
        this.minAmout = minAmout;
    }

    public String getAddressName() {
        return addressName;
    }

    public void setAddressName(String addressName) {
        this.addressName = addressName;
    }

    public Integer getRatingTotal() {
        return ratingTotal;
    }

    public void setRatingTotal(Integer ratingTotal) {
        this.ratingTotal = ratingTotal;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
    }

    public Integer getIsLike() {
        return isLike;
    }

    public void setIsLike(Integer isLike) {
        this.isLike = isLike;
    }

    public List<OmsTicket> getOmsTicketList() {
        return omsTicketList;
    }

    public void setOmsTicketList(List<OmsTicket> omsTicketList) {
        this.omsTicketList = omsTicketList;
    }

    public List<ViewCollaborators> getCmsCollaboratorsList() {
        return cmsCollaboratorsList;
    }

    public void setCmsCollaboratorsList(List<ViewCollaborators> cmsCollaboratorsList) {
        this.cmsCollaboratorsList = cmsCollaboratorsList;
    }

    public List<CmsMemberTemplate> getCmsMemberTemplateList() {
        return cmsMemberTemplateList;
    }

    public void setCmsMemberTemplateList(List<CmsMemberTemplate> cmsMemberTemplateList) {
        this.cmsMemberTemplateList = cmsMemberTemplateList;
    }

    public List<CmsRegistrationMerchant> getCmsRegistrationMerchantList() {
        return cmsRegistrationMerchantList;
    }

    public void setCmsRegistrationMerchantList(List<CmsRegistrationMerchant> cmsRegistrationMerchantList) {
        this.cmsRegistrationMerchantList = cmsRegistrationMerchantList;
    }

    public List<CmsTopicChapter> getCmsTopicChapterList() {
        return cmsTopicChapterList;
    }

    public void setCmsTopicChapterList(List<CmsTopicChapter> cmsTopicChapterList) {
        this.cmsTopicChapterList = cmsTopicChapterList;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId()
    {
        return memberId;
    }

    public String getContentScope() {
        return contentScope;
    }

    public void setContentScope(String contentScope) {
        this.contentScope = contentScope;
    }

    public String getContentPublisherType() {
        return contentPublisherType;
    }

    public void setContentPublisherType(String contentPublisherType) {
        this.contentPublisherType = contentPublisherType;
    }


    public String getContentPublisherName() {
        return contentPublisherName;
    }

    public void setContentPublisherName(String contentPublisherName) {
        this.contentPublisherName = contentPublisherName;
    }

    public String getContentPublisherLeaderName() {
        return contentPublisherLeaderName;
    }

    public void setContentPublisherLeaderName(String contentPublisherLeaderName) {
        this.contentPublisherLeaderName = contentPublisherLeaderName;
    }

    public com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO getOperationSummary() {
        return operationSummary;
    }

    public void setOperationSummary(com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO operationSummary) {
        this.operationSummary = operationSummary;
    }

    public String getClubFulfillmentState() {
        return clubFulfillmentState;
    }

    public void setClubFulfillmentState(String clubFulfillmentState) {
        this.clubFulfillmentState = clubFulfillmentState;
    }

    public String getMerchantSupplyState() {
        return merchantSupplyState;
    }

    public void setMerchantSupplyState(String merchantSupplyState) {
        this.merchantSupplyState = merchantSupplyState;
    }

    public String getRunReadiness() {
        return runReadiness;
    }

    public void setRunReadiness(String runReadiness) {
        this.runReadiness = runReadiness;
    }

    public Integer getDepositStatus() {
        return depositStatus;
    }

    public void setDepositStatus(Integer depositStatus) {
        this.depositStatus = depositStatus;
    }

    public String getCityKeyword() {
        return cityKeyword;
    }

    public void setCityKeyword(String cityKeyword) {
        this.cityKeyword = cityKeyword;
    }

    public Date getOperationStartDate() {
        return operationStartDate;
    }

    public void setOperationStartDate(Date operationStartDate) {
        this.operationStartDate = operationStartDate;
    }

    public Date getOperationEndDate() {
        return operationEndDate;
    }

    public void setOperationEndDate(Date operationEndDate) {
        this.operationEndDate = operationEndDate;
    }

    public Boolean getMerchantSupplyGapOnly() {
        return merchantSupplyGapOnly;
    }

    public void setMerchantSupplyGapOnly(Boolean merchantSupplyGapOnly) {
        this.merchantSupplyGapOnly = merchantSupplyGapOnly;
    }

    public Boolean getPendingClubApplicationOnly() {
        return pendingClubApplicationOnly;
    }

    public void setPendingClubApplicationOnly(Boolean pendingClubApplicationOnly) {
        this.pendingClubApplicationOnly = pendingClubApplicationOnly;
    }

    public Boolean getInProgressAnomalyOnly() {
        return inProgressAnomalyOnly;
    }

    public void setInProgressAnomalyOnly(Boolean inProgressAnomalyOnly) {
        this.inProgressAnomalyOnly = inProgressAnomalyOnly;
    }

    public Boolean getMerchantPoolOnly() { return merchantPoolOnly; }
    public void setMerchantPoolOnly(Boolean merchantPoolOnly) { this.merchantPoolOnly = merchantPoolOnly; }
    public Boolean getActiveClubOnly() { return activeClubOnly; }
    public void setActiveClubOnly(Boolean activeClubOnly) { this.activeClubOnly = activeClubOnly; }

    public void setClubId(Long clubId)
    {
        this.clubId = clubId;
    }

    public Long getClubId()
    {
        return clubId;
    }

    public Boolean getInviteWindowOpen() { return inviteWindowOpen; }
    public void setInviteWindowOpen(Boolean inviteWindowOpen) { this.inviteWindowOpen = inviteWindowOpen; }
    public Integer getSignupCount() { return signupCount; }
    public void setSignupCount(Integer signupCount) { this.signupCount = signupCount; }
    public Boolean getIsDraft() { return isDraft; }
    public void setIsDraft(Boolean isDraft) { this.isDraft = isDraft; }
    public Integer getTeamStatus() { return teamStatus; }
    public void setTeamStatus(Integer teamStatus) { this.teamStatus = teamStatus; }

    public void setTopicType(Long topicType)
    {
        this.topicType = topicType;
    }

    public Long getTopicType()
    {
        return topicType;
    }

    public void setProductType(Integer productType)
    {
        this.productType = productType;
    }

    public Integer getProductType()
    {
        return productType;
    }

    public String getCircleThemeCode() { return circleThemeCode; }
    public void setCircleThemeCode(String circleThemeCode) { this.circleThemeCode = circleThemeCode; }
    public Long getSourceTemplateTopicId() { return sourceTemplateTopicId; }
    public void setSourceTemplateTopicId(Long sourceTemplateTopicId) { this.sourceTemplateTopicId = sourceTemplateTopicId; }
    public Date getCircleReviewedAt() { return circleReviewedAt; }
    public void setCircleReviewedAt(Date circleReviewedAt) { this.circleReviewedAt = circleReviewedAt; }

    public Integer getRoamReferralEnabled() { return roamReferralEnabled; }
    public void setRoamReferralEnabled(Integer roamReferralEnabled) { this.roamReferralEnabled = roamReferralEnabled; }

    public String getCompleteRuleJson() { return completeRuleJson; }
    public void setCompleteRuleJson(String completeRuleJson) { this.completeRuleJson = completeRuleJson; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public String getRouteGraphJson() { return routeGraphJson; }
    public void setRouteGraphJson(String routeGraphJson) { this.routeGraphJson = routeGraphJson; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public String getPublishedRouteGraphJson() { return publishedRouteGraphJson; }
    public void setPublishedRouteGraphJson(String publishedRouteGraphJson) { this.publishedRouteGraphJson = publishedRouteGraphJson; }
    public Long getPublishedConfigVersion() { return publishedConfigVersion; }
    public void setPublishedConfigVersion(Long publishedConfigVersion) { this.publishedConfigVersion = publishedConfigVersion; }

    public String getGameModuleConfigJson() { return gameModuleConfigJson; }
    public void setGameModuleConfigJson(String gameModuleConfigJson) { this.gameModuleConfigJson = gameModuleConfigJson; }

    public String getThemeRuntimeConfigJson() { return themeRuntimeConfigJson; }
    public void setThemeRuntimeConfigJson(String v) { themeRuntimeConfigJson = v; }
    public String getLookJson() { return lookJson; }
    public void setLookJson(String v) { lookJson = v; }
    public String getPublishedThemeRuntimeConfigJson() { return publishedThemeRuntimeConfigJson; }
    public void setPublishedThemeRuntimeConfigJson(String v) { publishedThemeRuntimeConfigJson = v; }
    public String getJourneyRulesJson() { return journeyRulesJson; }
    public void setJourneyRulesJson(String journeyRulesJson) { this.journeyRulesJson = journeyRulesJson; }
    public String getJourneyStoryJson() { return journeyStoryJson; }
    public void setJourneyStoryJson(String journeyStoryJson) { this.journeyStoryJson = journeyStoryJson; }
    public Boolean getGameModuleEnabled() { return gameModuleEnabled; }
    public void setGameModuleEnabled(Boolean gameModuleEnabled) { this.gameModuleEnabled = gameModuleEnabled; }

    public java.math.BigDecimal getFeePlatformRate() { return feePlatformRate; }
    public void setFeePlatformRate(java.math.BigDecimal feePlatformRate) { this.feePlatformRate = feePlatformRate; }
    public java.math.BigDecimal getFeeGuaranteeBaseRate() { return feeGuaranteeBaseRate; }
    public void setFeeGuaranteeBaseRate(java.math.BigDecimal feeGuaranteeBaseRate) { this.feeGuaranteeBaseRate = feeGuaranteeBaseRate; }
    public java.math.BigDecimal getFeeGuaranteeStepRate() { return feeGuaranteeStepRate; }
    public void setFeeGuaranteeStepRate(java.math.BigDecimal feeGuaranteeStepRate) { this.feeGuaranteeStepRate = feeGuaranteeStepRate; }
    public Integer getFeeGuaranteeMaxMerchant() { return feeGuaranteeMaxMerchant; }
    public void setFeeGuaranteeMaxMerchant(Integer feeGuaranteeMaxMerchant) { this.feeGuaranteeMaxMerchant = feeGuaranteeMaxMerchant; }

    public void setTopicId(Long topicId) 
    {
        this.topicId = topicId;
    }

    public Long getTopicId() 
    {
        return topicId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setTotalTime(Long totalTime) 
    {
        this.totalTime = totalTime;
    }

    public Long getTotalTime() 
    {
        return totalTime;
    }

    public void setTotalMileage(BigDecimal totalMileage) 
    {
        this.totalMileage = totalMileage;
    }

    public BigDecimal getTotalMileage() 
    {
        return totalMileage;
    }

    public void setLocationCount(Long locationCount) 
    {
        this.locationCount = locationCount;
    }

    public Long getLocationCount() 
    {
        return locationCount;
    }

    public void setTemplateCount(Long templateCount) 
    {
        this.templateCount = templateCount;
    }

    public Long getTemplateCount() 
    {
        return templateCount;
    }

    public void setStartDate(Date startDate) 
    {
        this.startDate = startDate;
    }

    public Date getStartDate() 
    {
        return startDate;
    }

    public void setEndDate(Date endDate) 
    {
        this.endDate = endDate;
    }

    public Date getEndDate() 
    {
        return endDate;
    }

    public void setImgUrl(String imgUrl) 
    {
        this.imgUrl = imgUrl;
    }

    public String getImgUrl() 
    {
        return imgUrl;
    }

    public void setImgArr(String imgArr) 
    {
        this.imgArr = imgArr;
    }

    public String getImgArr() 
    {
        return imgArr;
    }

    public void setCategoryIds(String categoryIds) 
    {
        this.categoryIds = categoryIds;
    }

    public String getCategoryIds() 
    {
        return categoryIds;
    }

    public void setStatus(Integer status) 
    {
        this.status = status;
    }

    public Integer getStatus() 
    {
        return status;
    }

    public void setReason(String reason) 
    {
        this.reason = reason;
    }

    public String getReason() 
    {
        return reason;
    }

    public void setUserStatus(Integer userStatus) 
    {
        this.userStatus = userStatus;
    }

    public Integer getUserStatus() 
    {
        return userStatus;
    }

    public void setMerchantStatus(Integer merchantStatus) 
    {
        this.merchantStatus = merchantStatus;
    }

    public Integer getMerchantStatus()
    {
        return merchantStatus;
    }

    public void setOpenClubPool(Integer openClubPool)
    {
        this.openClubPool = openClubPool;
    }

    public Integer getOpenClubPool()
    {
        return openClubPool;
    }

    public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) 
    {
        this.merchantSignUpStartDate = merchantSignUpStartDate;
    }

    public Date getMerchantSignUpStartDate() 
    {
        return merchantSignUpStartDate;
    }

    public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) 
    {
        this.merchantSignUpEndDate = merchantSignUpEndDate;
    }

    public Date getMerchantSignUpEndDate() 
    {
        return merchantSignUpEndDate;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public void setIsSync(Integer isSync) 
    {
        this.isSync = isSync;
    }

    public Integer getIsSync() 
    {
        return isSync;
    }


    public List<SysCategory> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<SysCategory> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }

    public Integer getIsFollowTheUser() {
        return isFollowTheUser;
    }

    public void setIsFollowTheUser(Integer isFollowTheUser) {
        this.isFollowTheUser = isFollowTheUser;
    }

    public BigDecimal getSalesAmout() {
        return salesAmout;
    }

    public void setSalesAmout(BigDecimal salesAmout) {
        this.salesAmout = salesAmout;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("topicType", getTopicType())
            .append("productType", getProductType())
            .append("circleThemeCode", getCircleThemeCode())
            .append("sourceTemplateTopicId", getSourceTemplateTopicId())
            .append("roamReferralEnabled", getRoamReferralEnabled())
            .append("completeRuleJson", getCompleteRuleJson())
            .append("routeMode", getRouteMode())
            .append("routeGraphJson", getRouteGraphJson())
            .append("configVersion", getConfigVersion())
            .append("publishedRouteGraphJson", getPublishedRouteGraphJson())
            .append("publishedConfigVersion", getPublishedConfigVersion())
            .append("topicId", getTopicId())
            .append("name", getName())
            .append("description", getDescription())
            .append("totalTime", getTotalTime())
            .append("totalMileage", getTotalMileage())
            .append("locationCount", getLocationCount())
            .append("templateCount", getTemplateCount())
            .append("startDate", getStartDate())
            .append("endDate", getEndDate())
            .append("imgUrl", getImgUrl())
            .append("imgArr", getImgArr())
            .append("categoryIds", getCategoryIds())
            .append("status", getStatus())
            .append("reason", getReason())
            .append("userStatus", getUserStatus())
            .append("merchantStatus", getMerchantStatus())
            .append("merchantSignUpStartDate", getMerchantSignUpStartDate())
            .append("merchantSignUpEndDate", getMerchantSignUpEndDate())
            .append("delFlag", getDelFlag())
            .append("isSync", getIsSync())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("audioUrl", getAudioUrl())
            .append("audioDuration", getAudioDuration())
            .toString();
    }
}
