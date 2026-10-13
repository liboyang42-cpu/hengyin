package com.chengyinhub.business.domain.vo.ai;

/**
 * M2 节点玩法核心内容草稿；不是完整 CmsTemplatePublishRequest。
 * optionA~D 是 AI 输出字段，调用方映射发布表单时仍需转换为 questionA~D。
 */
public class AiNodeContent
{
    private Integer validationMethod;
    private String ruleInstructions;
    private String questionName;
    private String questionAnswer;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctAnswer;
    private String hint1;
    private String hint2;
    private String answerReveal;
    private String feedbackText;
    private String storyText;

    public Integer getValidationMethod() { return validationMethod; }
    public void setValidationMethod(Integer validationMethod) { this.validationMethod = validationMethod; }
    public String getRuleInstructions() { return ruleInstructions; }
    public void setRuleInstructions(String ruleInstructions) { this.ruleInstructions = ruleInstructions; }
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
    public String getAnswerReveal() { return answerReveal; }
    public void setAnswerReveal(String answerReveal) { this.answerReveal = answerReveal; }
    public String getFeedbackText() { return feedbackText; }
    public void setFeedbackText(String feedbackText) { this.feedbackText = feedbackText; }
    public String getStoryText() { return storyText; }
    public void setStoryText(String storyText) { this.storyText = storyText; }
}
