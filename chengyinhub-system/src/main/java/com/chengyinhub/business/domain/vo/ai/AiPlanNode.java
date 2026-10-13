package com.chengyinhub.business.domain.vo.ai;

/**
 * AI 方案节点:区分地点字段(来自候选)与玩法字段(AI 创作)。
 * 回填 fabu 时地点进 NodeDTO,玩法先建模板再挂 templateId。
 */
public class AiPlanNode
{
    // 地点(来自候选商家,后端校验 candidateId 必须映射自候选集)
    private Integer order;
    private Long candidateId;
    private String merchantName;
    private String address;
    private String longitude;
    private String latitude;
    private String businessTime;
    /** AI 给的角色文本(如"起点·暖场"),不入库 role 字段 */
    private String roleText;

    // 玩法(AI 创作,回填进 CmsTemplatePublishRequest)
    private String task;
    private Integer validationMethod;
    private String questionName;
    private String questionAnswer;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctAnswer;
    private String hint1;
    private String hint2;
    private String feedbackText;
    /** 奖励建议(仅文本,券/勋章需用户手动配) */
    private String rewardSuggest;

    public Integer getOrder() { return order; }
    public void setOrder(Integer order) { this.order = order; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getBusinessTime() { return businessTime; }
    public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
    public String getRoleText() { return roleText; }
    public void setRoleText(String roleText) { this.roleText = roleText; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public Integer getValidationMethod() { return validationMethod; }
    public void setValidationMethod(Integer validationMethod) { this.validationMethod = validationMethod; }
    public String getQuestionName() { return questionName; }
    public void setQuestionName(String questionName) { this.questionName = questionName; }
    public String getQuestionAnswer() { return questionAnswer; }
    public void setQuestionAnswer(String questionAnswer) { this.questionAnswer = questionAnswer; }
    public String getOptionA() { return optionA; }
    public void setOptionA(String optionA) { this.optionA = optionA; }
    public String getOptionB() { return optionB; }
    public void setOptionB(String optionB) { this.optionB = optionB; }
    public String getOptionC() { return optionC; }
    public void setOptionC(String optionC) { this.optionC = optionC; }
    public String getOptionD() { return optionD; }
    public void setOptionD(String optionD) { this.optionD = optionD; }
    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    public String getHint1() { return hint1; }
    public void setHint1(String hint1) { this.hint1 = hint1; }
    public String getHint2() { return hint2; }
    public void setHint2(String hint2) { this.hint2 = hint2; }
    public String getFeedbackText() { return feedbackText; }
    public void setFeedbackText(String feedbackText) { this.feedbackText = feedbackText; }
    public String getRewardSuggest() { return rewardSuggest; }
    public void setRewardSuggest(String rewardSuggest) { this.rewardSuggest = rewardSuggest; }
}
