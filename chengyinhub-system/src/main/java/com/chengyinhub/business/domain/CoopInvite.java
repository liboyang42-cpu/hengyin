package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 协作邀请对象 coop_invite(P1.5)。
 *
 * type0/type1 邀请的【自审核握手】:发起方发起(status=0)+ 受邀方同意(status=1)= 通过。
 * 历史 type2 仅保留读取兼容，不得再创建或变更状态。
 */
public class CoopInvite extends BaseEntity {
    private com.chengyinhub.business.domain.vo.TopicSaleReviewProgress saleReviewProgress;
    public com.chengyinhub.business.domain.vo.TopicSaleReviewProgress getSaleReviewProgress() { return saleReviewProgress; }
    public void setSaleReviewProgress(com.chengyinhub.business.domain.vo.TopicSaleReviewProgress value) { this.saleReviewProgress = value; }

    private static final long serialVersionUID = 1L;

    public static final int TYPE_OFFICIAL_MERCHANT = 3;
    public static final int TYPE_LEGACY_MERCHANT_NODE = 2;
    public static final String FROM_OFFICIAL = "official";
    public static final String TO_MERCHANT = "merchant";

    /** 主键 */
    private Long id;

    /** 邀请类型 0主题发布者→商家 1商家→俱乐部；2为历史商家节点邀约，仅只读兼容 */
    private Integer inviteType;

    /** 发起方类型 talent/merchant */
    private String fromType;

    /** 发起方ID(memberId) */
    private Long fromId;

    /** 受邀方类型 merchant/club */
    private String toType;

    /** 受邀方ID(merchant=memberId / club=clubId) */
    private Long toId;

    /** 关联经典定向主题(类型1/2必填) */
    private Long topicId;

    /** 开放报名溯源申请ID(type1:俱乐部先申请、商家再邀约时填写) */
    private Long originApplyId;

    /** 历史类型2关联节点；仅用于存量数据读取兼容 */
    private Long nodeId;

    /** 经典定向:目标游戏(游戏级邀约) */
    private Long gameId;

    /** 槽位粒度 0主题级/1游戏级 */
    private Integer targetLevel;

    /** 状态 0待确认 1已接受 2已拒绝 3已取消 4已顶替 5已过期 */
    private Integer status;

    /** 留言 */
    private String message;

    /** 处理时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date handleTime;

    /** 受邀方拒绝原因；只在拒绝官方邀约时写入。 */
    private String handleReason;

    /** 邀约自身过期时间(最迟=创建后48h，主题招募更早截止时取更早值) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expireTime;

    /** 历史列:旧版主题级接受时写的 72h 配置死线;阻断#8 起不再写、不再读(存量行保留) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date configDeadline;

    /** 报酬模式 0引流/1分成/2固定 */
    private Integer shareMode;

    /** 分成型比例(share_mode=1) */
    private BigDecimal shareRate;

    /** 固定型每核销人头费(share_mode=2) */
    private BigDecimal fixedFee;

    /** 保证金额(锁价后应缴,固定¥50) */
    private BigDecimal depositAmount;

    /** 保证金状态 0无需/1支付中/2已缴冻结/3已扣/4已退/5应缴未缴 */
    private Integer depositStatus;

    /** 保证金微信支付商户订单号(前缀DEP) */
    private String depositOutTradeNo;

    /** 保证金退款单号 */
    private String depositRefundNo;


    /** 保证金退款执行事实；NULL 为未建立执行记录，历史4不能据此认定到账。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String depositRefundState;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private BigDecimal depositRefundAmount;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String depositRefundLeaseToken;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Date depositRefundLeaseAt;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Date depositRefundRequestedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Date depositRefundConfirmedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String depositPaidTransactionId;
    public String getDepositRefundState() { return depositRefundState; }
    public void setDepositRefundState(String value) { depositRefundState = value; }
    public BigDecimal getDepositRefundAmount() { return depositRefundAmount; }
    public void setDepositRefundAmount(BigDecimal value) { depositRefundAmount = value; }
    public String getDepositRefundLeaseToken() { return depositRefundLeaseToken; }
    public void setDepositRefundLeaseToken(String value) { depositRefundLeaseToken = value; }
    public Date getDepositRefundLeaseAt() { return depositRefundLeaseAt; }
    public void setDepositRefundLeaseAt(Date value) { depositRefundLeaseAt = value; }
    public Date getDepositRefundRequestedAt() { return depositRefundRequestedAt; }
    public void setDepositRefundRequestedAt(Date value) { depositRefundRequestedAt = value; }
    public Date getDepositRefundConfirmedAt() { return depositRefundConfirmedAt; }
    public void setDepositRefundConfirmedAt(Date value) { depositRefundConfirmedAt = value; }
    public String getDepositPaidTransactionId() { return depositPaidTransactionId; }
    public void setDepositPaidTransactionId(String value) { depositPaidTransactionId = value; }

    /** 当前登录人是否为锁价后应缴方(仅列表展示,不落库) */
    private Boolean depositOwed;

    /** 主题已锁价(商业条款冻结):锁价后单方取消由后端拒绝转客服,前端据此置灰「取消合作」(仅列表展示,不落库) */
    private Boolean termsFrozen;

    /** 已取消或主题已结算但退款未完成(仅列表展示,不落库) */
    private Boolean depositRefundPending;

    /** 对方是谁(仅列表展示,不落库);联系方式受 §3.8 闸,见 CoopPartnerVO */
    private com.chengyinhub.business.domain.vo.CoopPartnerVO partner;

    /* 邀约卡上要显示的主题信息(稿 261:277 / 261:349)。
       ★ 不落库,只在列表接口里现填 —— coop_invite 表里只有 topic_id,
         而卡面上要的是「封面 + 档期 + 路线名 + 地点」。原来前端拿不到名字,
         只能显示「主题 #123」,把一个内部 id 摆给商家看。
       ⚠️ 一律 transient:这些是**展示投影**,不是这张表的事实。写回库会和 cms_topic 分叉。 */
    /** 当前收发箱所属俱乐部名称，只作展示，不参与权限判定。 */
    private transient String viewerPartyName;
    public String getViewerPartyName() { return viewerPartyName; }
    public void setViewerPartyName(String value) { viewerPartyName = value; }

    private transient String topicName;

    private transient String topicImgUrl;

    private transient java.util.Date topicStartDate;

    private transient String topicAddressName;

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public String getTopicImgUrl() { return topicImgUrl; }
    public void setTopicImgUrl(String topicImgUrl) { this.topicImgUrl = topicImgUrl; }

    public java.util.Date getTopicStartDate() { return topicStartDate; }
    public void setTopicStartDate(java.util.Date topicStartDate) { this.topicStartDate = topicStartDate; }

    public String getTopicAddressName() { return topicAddressName; }
    public void setTopicAddressName(String topicAddressName) { this.topicAddressName = topicAddressName; }

    /** 操作主体作用域；仅接收 API 请求，不落 coop_invite。 */
    private String scope;

    /** 原请求键，仅接收 API 请求，不落 coop_invite。 */
    private transient String requestId;
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    /** del_flag */
    private Integer delFlag;

    public Long getId() { return id; }

    /** 前端定位与操作使用的精确主键；保留 numeric id 兼容既有消费者，禁止请求别名改写主键。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getInviteId() { return id == null ? null : id.toString(); }
    public void setId(Long id) { this.id = id; }
    public Integer getInviteType() { return inviteType; }
    public void setInviteType(Integer inviteType) { this.inviteType = inviteType; }
    public String getFromType() { return fromType; }
    public void setFromType(String fromType) { this.fromType = fromType; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public String getToType() { return toType; }
    public void setToType(String toType) { this.toType = toType; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getOriginApplyId() { return originApplyId; }
    public void setOriginApplyId(Long originApplyId) { this.originApplyId = originApplyId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getGameId() { return gameId; }
    public void setGameId(Long gameId) { this.gameId = gameId; }
    public Integer getTargetLevel() { return targetLevel; }
    public void setTargetLevel(Integer targetLevel) { this.targetLevel = targetLevel; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Date getHandleTime() { return handleTime; }
    public void setHandleTime(Date handleTime) { this.handleTime = handleTime; }
    public String getHandleReason() { return handleReason; }
    public void setHandleReason(String handleReason) { this.handleReason = handleReason; }
    public Date getExpireTime() { return expireTime; }
    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }
    public Date getConfigDeadline() { return configDeadline; }
    public void setConfigDeadline(Date configDeadline) { this.configDeadline = configDeadline; }
    public Integer getShareMode() { return shareMode; }
    public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
    public BigDecimal getShareRate() { return shareRate; }
    public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public Integer getDepositStatus() { return depositStatus; }
    public void setDepositStatus(Integer depositStatus) { this.depositStatus = depositStatus; }
    public String getDepositOutTradeNo() { return depositOutTradeNo; }
    public void setDepositOutTradeNo(String depositOutTradeNo) { this.depositOutTradeNo = depositOutTradeNo; }
    public String getDepositRefundNo() { return depositRefundNo; }
    public void setDepositRefundNo(String depositRefundNo) { this.depositRefundNo = depositRefundNo; }
    public Boolean getTermsFrozen() { return termsFrozen; }
    public void setTermsFrozen(Boolean termsFrozen) { this.termsFrozen = termsFrozen; }
    public Boolean getDepositOwed() { return depositOwed; }
    public void setDepositOwed(Boolean depositOwed) { this.depositOwed = depositOwed; }
    public Boolean getDepositRefundPending() { return depositRefundPending; }
    public void setDepositRefundPending(Boolean depositRefundPending) { this.depositRefundPending = depositRefundPending; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public com.chengyinhub.business.domain.vo.CoopPartnerVO getPartner() { return partner; }
    public void setPartner(com.chengyinhub.business.domain.vo.CoopPartnerVO partner) { this.partner = partner; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
}
