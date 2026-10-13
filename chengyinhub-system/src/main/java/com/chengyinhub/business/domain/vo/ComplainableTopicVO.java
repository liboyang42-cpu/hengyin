package com.chengyinhub.business.domain.vo;

/**
 * 「我可以投诉哪个主题」的一行 —— 投诉页的选择项,同时也是受理口的准入凭据。
 *
 * <p>2026-08-18 建。此前投诉页的选择源是 {@code /api/registration/list?owner_type=3},
 * 受理口是 {@code ApiCoopController.complaintReport} 里另写的一段循环,两边判据分散在两个
 * controller 里,已经漂出两个可复现缺陷(结束 >7 天的主题选不到、③ 活动票玩家整条选不到)。
 * 现在两边都读 {@code ICmsRegistrationService.selectComplainableTopics},判据只有一份。
 */
public class ComplainableTopicVO {

    /** 主题 id。由 registration 反查而来,**不是**前端传进来的值。 */
    private Long topicId;

    /** 主题名,给下拉项显示用。 */
    private String topicName;

    /** 支撑这条可投诉资格的那张已支付报名单 —— 建工单时要落到 coop_complaint.registration_id。 */
    private Long registrationId;

    public ComplainableTopicVO() {
    }

    public ComplainableTopicVO(Long topicId, String topicName, Long registrationId) {
        this.topicId = topicId;
        this.topicName = topicName;
        this.registrationId = registrationId;
    }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
}
