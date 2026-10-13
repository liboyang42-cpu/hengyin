package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 俱乐部客户(按人聚合)对外白名单 VO。
 *
 * ★ 为什么是白名单:和 {@link ClubRegistrationRosterVO} 同一个理由 —— 底层聚合直接 join 了
 * ums_member,里面带手机号明文。任何"把查询结果原样序列化出去"的写法都会漏 PII。
 * 所以这里只保留前端真正要渲染的字段,而且手机号那一格叫 {@code phoneText}:
 * 它是**服务端已经渲染好的一行文字**(脱敏串,或"看不到"的替代说明),不是原始号码。
 * 前端拿不到明文,也就没有"下发了再用 wx:if 藏起来"这种假保护。
 */
public class ClubCustomerVO {

    /** 列表页(K1)。 */
    public static class ListPage {
        private Integer total;
        private Integer monthNew;
        private Boolean canEdit;
        private List<Row> items = new ArrayList<>();

        public Integer getTotal() { return total; }
        public void setTotal(Integer total) { this.total = total; }
        public Integer getMonthNew() { return monthNew; }
        public void setMonthNew(Integer monthNew) { this.monthNew = monthNew; }
        public Boolean getCanEdit() { return canEdit; }
        public void setCanEdit(Boolean canEdit) { this.canEdit = canEdit; }
        public List<Row> getItems() { return items; }
        public void setItems(List<Row> items) { this.items = items; }
    }

    /** 列表行。phoneText 由服务端按岗位渲染,原始号码不出现在本类里。 */
    public static class Row {
        private Long memberId;
        private String displayName;
        private String avatar;
        private Integer verifiedCount;
        private Integer pendingCount;
        private String lastVisitDate;
        private String lastTopicName;
        private String remark;
        private String phoneText;

        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public Integer getVerifiedCount() { return verifiedCount; }
        public void setVerifiedCount(Integer verifiedCount) { this.verifiedCount = verifiedCount; }
        public Integer getPendingCount() { return pendingCount; }
        public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
        public String getLastVisitDate() { return lastVisitDate; }
        public void setLastVisitDate(String lastVisitDate) { this.lastVisitDate = lastVisitDate; }
        public String getLastTopicName() { return lastTopicName; }
        public void setLastTopicName(String lastTopicName) { this.lastTopicName = lastTopicName; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public String getPhoneText() { return phoneText; }
        public void setPhoneText(String phoneText) { this.phoneText = phoneText; }
    }

    /** 详情页(K2)。 */
    public static class Detail {
        private Summary summary;
        private List<Record> records = new ArrayList<>();
        private List<String> tags = new ArrayList<>();
        private String remark;
        private Boolean canEdit;

        public Summary getSummary() { return summary; }
        public void setSummary(Summary summary) { this.summary = summary; }
        public List<Record> getRecords() { return records; }
        public void setRecords(List<Record> records) { this.records = records; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public Boolean getCanEdit() { return canEdit; }
        public void setCanEdit(Boolean canEdit) { this.canEdit = canEdit; }
    }

    /** 详情头。paidAmount 为 null 表示"本岗位没有金额查看权限"(K2-B),不是 0。 */
    public static class Summary {
        private Long memberId;
        private String displayName;
        private String avatar;
        private String phoneText;
        private Date lastInteractionTime;
        private Integer arrivedCount;
        private Integer pendingCount;
        private Integer refundedCount;
        private BigDecimal paidAmount;

        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getPhoneText() { return phoneText; }
        public void setPhoneText(String phoneText) { this.phoneText = phoneText; }
        public Date getLastInteractionTime() { return lastInteractionTime; }
        public void setLastInteractionTime(Date lastInteractionTime) { this.lastInteractionTime = lastInteractionTime; }
        public Integer getArrivedCount() { return arrivedCount; }
        public void setArrivedCount(Integer arrivedCount) { this.arrivedCount = arrivedCount; }
        public Integer getPendingCount() { return pendingCount; }
        public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
        public Integer getRefundedCount() { return refundedCount; }
        public void setRefundedCount(Integer refundedCount) { this.refundedCount = refundedCount; }
        public BigDecimal getPaidAmount() { return paidAmount; }
        public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    }

    /** 一条参与记录。statusCode ∈ VERIFIED / PENDING / REFUNDED。 */
    public static class Record {
        private String key;
        private Long topicId;
        private String title;
        private String occurredAt;
        private String statusCode;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getOccurredAt() { return occurredAt; }
        public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }
        public String getStatusCode() { return statusCode; }
        public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    }

    /** 主题内客户(J2 全屏弹窗)。 */
    public static class TopicPage {
        private Integer soldCount;
        private Integer pendingCount;
        private Integer contactedCount;
        private Integer verifiedCount;
        private List<Session> sessions = new ArrayList<>();

        public Integer getSoldCount() { return soldCount; }
        public void setSoldCount(Integer soldCount) { this.soldCount = soldCount; }
        public Integer getPendingCount() { return pendingCount; }
        public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
        public Integer getContactedCount() { return contactedCount; }
        public void setContactedCount(Integer contactedCount) { this.contactedCount = contactedCount; }
        public Integer getVerifiedCount() { return verifiedCount; }
        public void setVerifiedCount(Integer verifiedCount) { this.verifiedCount = verifiedCount; }
        public List<Session> getSessions() { return sessions; }
        public void setSessions(List<Session> sessions) { this.sessions = sessions; }
    }

    public static class Session {
        private String key;
        private String timeText;
        private List<TopicRow> rows = new ArrayList<>();

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getTimeText() { return timeText; }
        public void setTimeText(String timeText) { this.timeText = timeText; }
        public List<TopicRow> getRows() { return rows; }
        public void setRows(List<TopicRow> rows) { this.rows = rows; }
    }

    public static class TopicRow {
        private String key;
        private String displayName;
        private String avatar;
        private String orderNo;
        private String phoneText;
        private String statusCode;
        private String statusText;
        private String timeText;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getPhoneText() { return phoneText; }
        public void setPhoneText(String phoneText) { this.phoneText = phoneText; }
        public String getStatusCode() { return statusCode; }
        public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }
        public String getTimeText() { return timeText; }
        public void setTimeText(String timeText) { this.timeText = timeText; }
    }

    /**
     * Mapper 内部载体:聚合出来的原始行(带 ums_member.phone 明文)。
     * ⚠️ 绝不下发。Service 负责把它转成上面的 Row / Summary,并在转换时把 phone 换成 phoneText。
     */
    public static class RawCustomer {
        private Long memberId;
        private String displayName;
        private String avatar;
        private String phone;
        private Integer verifiedCount;
        private Integer pendingCount;
        private Integer refundedCount;
        private BigDecimal paidAmount;
        private Date lastInteractionTime;
        private String lastTopicName;

        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public Integer getVerifiedCount() { return verifiedCount; }
        public void setVerifiedCount(Integer verifiedCount) { this.verifiedCount = verifiedCount; }
        public Integer getPendingCount() { return pendingCount; }
        public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
        public Integer getRefundedCount() { return refundedCount; }
        public void setRefundedCount(Integer refundedCount) { this.refundedCount = refundedCount; }
        public BigDecimal getPaidAmount() { return paidAmount; }
        public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
        public Date getLastInteractionTime() { return lastInteractionTime; }
        public void setLastInteractionTime(Date lastInteractionTime) { this.lastInteractionTime = lastInteractionTime; }
        public String getLastTopicName() { return lastTopicName; }
        public void setLastTopicName(String lastTopicName) { this.lastTopicName = lastTopicName; }
    }

    /** Mapper 内部载体:主题内一行报名(带明文手机号,同样绝不下发)。 */
    public static class RawTopicRow {
        private Long registrationId;
        private String participantName;
        private Integer registrationStatus;
        private Long pointUsed;
        private java.math.BigDecimal payableAmount;

        private Long memberId;
        private String displayName;
        private String avatar;
        private String phone;
        private String orderNo;
        private Integer paymentStatus;
        private Integer verificationStatus;
        private Date verificationTime;
        private Date sessionTime;
        private Long sessionId;
        /** 这单有没有被评价过(merchant_review.create_time)。K3 稿的第四态「已评价」按它判。 */
        private Date reviewTime;

        public String getParticipantName() { return participantName; }
        public void setParticipantName(String value) { participantName = value; }
        public Integer getRegistrationStatus() { return registrationStatus; }
        public void setRegistrationStatus(Integer value) { registrationStatus = value; }
        public Long getPointUsed() { return pointUsed; }
        public void setPointUsed(Long value) { pointUsed = value; }
        public java.math.BigDecimal getPayableAmount() { return payableAmount; }
        public void setPayableAmount(java.math.BigDecimal value) { payableAmount = value; }
        public Long getRegistrationId() { return registrationId; }
        public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Integer getPaymentStatus() { return paymentStatus; }
        public void setPaymentStatus(Integer paymentStatus) { this.paymentStatus = paymentStatus; }
        public Integer getVerificationStatus() { return verificationStatus; }
        public void setVerificationStatus(Integer verificationStatus) { this.verificationStatus = verificationStatus; }
        public Date getVerificationTime() { return verificationTime; }
        public void setVerificationTime(Date verificationTime) { this.verificationTime = verificationTime; }
        public Date getSessionTime() { return sessionTime; }
        public void setSessionTime(Date sessionTime) { this.sessionTime = sessionTime; }
        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public Date getReviewTime() { return reviewTime; }
        public void setReviewTime(Date reviewTime) { this.reviewTime = reviewTime; }
    }

    /** 客户档案(标签与备注)。 */
    public static class Profile {
        private Long memberId;
        private String tagsJson;
        private String remark;

        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public String getTagsJson() { return tagsJson; }
        public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    /** Figma K3 核销详情 · 俱乐部端。一条报名单的凭证快照。 */
    public static class CheckinDetail {
        private String displayName;
        private String avatar;
        /** ★ 已按岗位权限裁过:没权限时这里是替代说明而不是号码,前端原样显示。 */
        private String phoneText;
        private boolean phoneVisible;
        private String sessionTimeText;
        private String statusCode;      // PENDING | CONTACTED | VERIFIED | REVIEWED | REFUNDED
        private String statusText;
        private String statusTimeText;
        private String topicName;
        private String topicCover;
        private String orderNo;
        private String ticketText;      // 「标准单人票 ×1」
        private String orderTimeText;
        private String paidAmountText;
        private String verifyTimeText;
        private String storeName;
        private String operatorName;
        /** 履约轨迹走到第几步(0 已报名 / 1 已接洽 / 2 已核销 / 3 已评价);-1 = 已退款不走轨迹 */
        private int railStep;
        /** 这条报名单的 id,前端清退退款要拿它去调 /api/registration/cancel-by-owner。 */
        private Long registrationId;
        /**
         * ★ 权限声明不是摆设:清退退款 owner-only,且只对「已付款未核销」的单成立。
         * 管理员或已核销/已退款的单回包写 true 就是给了一个必定被服务端拒的假按钮。
         * 口径与名册 ApiClubController 的 roster.setCanRefund 一致,别分叉。
         */
        private boolean canRefund;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String v) { this.avatar = v; }
        public String getPhoneText() { return phoneText; }
        public void setPhoneText(String v) { this.phoneText = v; }
        public boolean isPhoneVisible() { return phoneVisible; }
        public void setPhoneVisible(boolean v) { this.phoneVisible = v; }
        public String getSessionTimeText() { return sessionTimeText; }
        public void setSessionTimeText(String v) { this.sessionTimeText = v; }
        public String getStatusCode() { return statusCode; }
        public void setStatusCode(String v) { this.statusCode = v; }
        public String getStatusText() { return statusText; }
        public void setStatusText(String v) { this.statusText = v; }
        public String getStatusTimeText() { return statusTimeText; }
        public void setStatusTimeText(String v) { this.statusTimeText = v; }
        public String getTopicName() { return topicName; }
        public void setTopicName(String v) { this.topicName = v; }
        public String getTopicCover() { return topicCover; }
        public void setTopicCover(String v) { this.topicCover = v; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String v) { this.orderNo = v; }
        public String getTicketText() { return ticketText; }
        public void setTicketText(String v) { this.ticketText = v; }
        public String getOrderTimeText() { return orderTimeText; }
        public void setOrderTimeText(String v) { this.orderTimeText = v; }
        public String getPaidAmountText() { return paidAmountText; }
        public void setPaidAmountText(String v) { this.paidAmountText = v; }
        public String getVerifyTimeText() { return verifyTimeText; }
        public void setVerifyTimeText(String v) { this.verifyTimeText = v; }
        public String getStoreName() { return storeName; }
        public void setStoreName(String v) { this.storeName = v; }
        public String getOperatorName() { return operatorName; }
        public void setOperatorName(String v) { this.operatorName = v; }
        public Long getRegistrationId() { return registrationId; }
        public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
        public boolean isCanRefund() { return canRefund; }
        public void setCanRefund(boolean canRefund) { this.canRefund = canRefund; }
        public int getRailStep() { return railStep; }
        public void setRailStep(int v) { this.railStep = v; }
    }

    /** 核销详情的原始行,整形前的样子。 */
    public static class RawCheckinRow {
        private Long registrationId;
        private String participantName;
        private Integer registrationStatus;
        private Long pointUsed;

        private String displayName;
        private String avatar;
        private String phone;
        private String orderNo;
        private String ticketName;
        private Long orderNum;
        private java.math.BigDecimal payableAmount;
        private java.util.Date paymentTime;
        private Integer paymentStatus;
        private Integer verificationStatus;
        private java.util.Date verificationTime;
        private java.util.Date sessionTime;
        /** 这单有没有被评价过。K3 履约轨迹第四格按它判 —— 详情页与列表必须同一判据。 */
        private java.util.Date reviewTime;
        private String topicName;
        private String topicCover;
        private String operatorName;
        private String storeName;

        public String getParticipantName() { return participantName; }
        public void setParticipantName(String value) { participantName = value; }
        public Integer getRegistrationStatus() { return registrationStatus; }
        public void setRegistrationStatus(Integer value) { registrationStatus = value; }
        public Long getPointUsed() { return pointUsed; }
        public void setPointUsed(Long value) { pointUsed = value; }
        public Long getRegistrationId() { return registrationId; }
        public void setRegistrationId(Long v) { this.registrationId = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String v) { this.avatar = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String v) { this.orderNo = v; }
        public String getTicketName() { return ticketName; }
        public void setTicketName(String v) { this.ticketName = v; }
        public Long getOrderNum() { return orderNum; }
        public void setOrderNum(Long v) { this.orderNum = v; }
        public java.math.BigDecimal getPayableAmount() { return payableAmount; }
        public void setPayableAmount(java.math.BigDecimal v) { this.payableAmount = v; }
        public java.util.Date getPaymentTime() { return paymentTime; }
        public void setPaymentTime(java.util.Date v) { this.paymentTime = v; }
        public Integer getPaymentStatus() { return paymentStatus; }
        public void setPaymentStatus(Integer v) { this.paymentStatus = v; }
        public Integer getVerificationStatus() { return verificationStatus; }
        public void setVerificationStatus(Integer v) { this.verificationStatus = v; }
        public java.util.Date getVerificationTime() { return verificationTime; }
        public void setVerificationTime(java.util.Date v) { this.verificationTime = v; }
        public java.util.Date getSessionTime() { return sessionTime; }
        public void setSessionTime(java.util.Date v) { this.sessionTime = v; }
        public java.util.Date getReviewTime() { return reviewTime; }
        public void setReviewTime(java.util.Date v) { this.reviewTime = v; }
        public String getTopicName() { return topicName; }
        public void setTopicName(String v) { this.topicName = v; }
        public String getTopicCover() { return topicCover; }
        public void setTopicCover(String v) { this.topicCover = v; }
        public String getOperatorName() { return operatorName; }
        public void setOperatorName(String v) { this.operatorName = v; }
        public String getStoreName() { return storeName; }
        public void setStoreName(String v) { this.storeName = v; }
    }
}
