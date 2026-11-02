package com.chengyinhub.business.domain.vo;

/** 广播预览:只报服务端重算出的真实人数,不读客户端 memberId。 */
public class MerchantCrmBroadcastPreviewVO {
    /** 当前筛选(未收窄范围)命中总数,给商家对照「筛选共多少人」。 */
    private Long filterTotalCount;
    /** 本批冻结名单人数(已按 scope/groups 收窄,单次上限见 recipientLimit)。 */
    private Integer audienceCount;
    private Integer consentedCount;
    private Integer noConsentCount;
    private Integer frequencyLimitedCount;
    private Integer deliverableCount;
    private Integer recipientLimit;
    /** 同一商家每天最多发几条(常量)。 */
    private Integer merchantDailyLimit;
    private Integer merchantDailyUsed;
    private Integer merchantDailyRemaining;
    /** 回显收窄到的主题;小程序按它确认服务端确实按主题筛过,没回显就不让发。 */
    private Long topicId;
    /** 2026-11-01 业主:按主题发时给收件人名单,弹层顶部横滑头像选人;不按主题发不给。 */
    private java.util.List<Recipient> recipients;

    /** 只有头像、昵称和到店 / 可触达状态,不带手机号与金额。 */
    public static class Recipient {
        private Long memberId;
        private String name;
        private String avatar;
        private boolean arrived;
        /** OK 能收到;NO_CONSENT 没同意接收;TODAY_SENT 今天已收过一条。 */
        private String reach;
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long value) { memberId = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String value) { avatar = value; }
        public boolean isArrived() { return arrived; }
        public void setArrived(boolean value) { arrived = value; }
        public String getReach() { return reach; }
        public void setReach(String value) { reach = value; }
    }

    public Long getFilterTotalCount() { return filterTotalCount; }
    public void setFilterTotalCount(Long value) { filterTotalCount = value; }
    public Integer getAudienceCount() { return audienceCount; }
    public void setAudienceCount(Integer value) { audienceCount = value; }
    public Integer getConsentedCount() { return consentedCount; }
    public void setConsentedCount(Integer value) { consentedCount = value; }
    public Integer getNoConsentCount() { return noConsentCount; }
    public void setNoConsentCount(Integer value) { noConsentCount = value; }
    public Integer getFrequencyLimitedCount() { return frequencyLimitedCount; }
    public void setFrequencyLimitedCount(Integer value) { frequencyLimitedCount = value; }
    public Integer getDeliverableCount() { return deliverableCount; }
    public void setDeliverableCount(Integer value) { deliverableCount = value; }
    public Integer getRecipientLimit() { return recipientLimit; }
    public void setRecipientLimit(Integer value) { recipientLimit = value; }
    public Integer getMerchantDailyLimit() { return merchantDailyLimit; }
    public void setMerchantDailyLimit(Integer value) { merchantDailyLimit = value; }
    public Integer getMerchantDailyUsed() { return merchantDailyUsed; }
    public void setMerchantDailyUsed(Integer value) { merchantDailyUsed = value; }
    public Integer getMerchantDailyRemaining() { return merchantDailyRemaining; }
    public void setMerchantDailyRemaining(Integer value) { merchantDailyRemaining = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public java.util.List<Recipient> getRecipients() { return recipients; }
    public void setRecipients(java.util.List<Recipient> value) { recipients = value; }
}
