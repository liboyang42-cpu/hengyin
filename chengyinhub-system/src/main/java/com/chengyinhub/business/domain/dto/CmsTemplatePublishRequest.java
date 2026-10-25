package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 模板发布请求参数CmsTemplatePublishRequest
 */
@Schema(description = "模板发布请求参数")
public class CmsTemplatePublishRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "显式清空字段：rewardRulesJson、merchantGuide、sensorType、sensorConfig、storyImg")
    private Set<String> clearFields;

    public Set<String> getClearFields() { return clearFields; }
    public void setClearFields(Set<String> clearFields) { this.clearFields = clearFields; }

    @Schema(description = "业务主体作用域：MERCHANT 表示归属当前商家；留空表示个人")
    private String scope;

    @Schema(description = "引用的模板库ID(可为空)", example = "0")
    private Long originalTemplateId;

    /** 仅创建采用草稿：内容从可见公共模板读取，不信任客户端的公开投影。 */
    private Boolean adoptSourceOnly;

    public Boolean getAdoptSourceOnly() { return adoptSourceOnly; }
    public void setAdoptSourceOnly(Boolean adoptSourceOnly) { this.adoptSourceOnly = adoptSourceOnly; }

    @Schema(description = "模板类别ID", example = "1")
    private Long categoryId;

    @Schema(description = "标题", required = true, example = "营销活动模板")
    @NotBlank(message = "标题不能为空")
    private String title;

    @Schema(description = "介绍", example = "这是一个营销活动模板")
    private String description;

    @Schema(description = "图片URL", example = "https://example.com/template.jpg")
    private String imgUrl;


    @Schema(description = "游戏人数", example = "")
    private String players;

    @Schema(description = "适用场景", example = "")
    private String usageLocation;

    @Schema(description = "所需材料", example = "")
    private String requiredMaterials;

    @Schema(description = "时长(分钟)", example = "60")
    private Integer duration;

    @Schema(description = "难度", example = "中等")
    private String difficulty;

    @Schema(description = "是否同步到模板库: 0否 1是", example = "1")
    private Integer isSync;

    @Schema(description = "规则说明", example = "请按照规则填写内容")
    private String ruleInstructions;

    /** 给商家/店员看的执行说明;玩家侧一律不下发 */
    private String merchantGuide;

    /** 条件奖励:[{outcomeCode,couponId,xp,medalName,medalImg,medalStyle,feedbackText}] */
    private String rewardRulesJson;

    @Schema(description = "验证方式: 1、文字验证 2、图片验证 3、选项验证", example = "1")
    private Integer validationMethod;

    @Schema(description = "玩法语义，沿用个人模板 interactionType；与验证方式正交")
    private String interactionType;

    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }

    @Schema(description = "vm=7 传感器类型:still/steps/audio_clip")
    private String sensorType;

    @Schema(description = "vm=7 传感器玩法参数 JSON")
    private String sensorConfig;

    @Schema(description = "问题名称", example = "验证问题")
    private String questionName;

    @Schema(description = "问题答案", example = "这是答案")
    private String questionAnswer;

    @Schema(description = "反馈文字", example = "感谢您的参与")
    private String feedbackText;

    @Schema(description = "反馈优惠券ID", example = "123")
    private Long couponId;

    @Schema(description = "勋章图片URL(俱乐部上传,空=不发勋章)")
    private String medalImg;

    @Schema(description = "勋章名称")
    private String medalName;

    @Schema(description = "勋章样式:glow=二维发光/enamel=3D珐琅", example = "glow")
    private String medalStyle;

    @Schema(description = "问题选项A", example = "选项A")
    private String questionA;

    @Schema(description = "问题选项B", example = "选项B")
    private String questionB;

    @Schema(description = "问题选项C", example = "选项C")
    private String questionC;

    @Schema(description = "问题选项D", example = "选项D")
    private String questionD;

    @Schema(description = "正确答案(A/B/C/D)", example = "A")
    private String correctAnswer;

    @Schema(description = "题干配图URL(看图答题)")
    private String questionImg;

    @Schema(description = "题干配音URL(听音答题)")
    private String questionAudio;

    @Schema(description = "选项媒体,按字母对齐 questionA~D", example = "{\"A\":{\"img\":\"https://cdn/a.jpg\"}}")
    private String questionOptionMediaJson;

    @Schema(description = "偏好题组 JSON(validationMethod=6)")
    private String preferenceJson;

    @Schema(description = "高级玩法配置 JSON(schemaVersion=1)")
    private String advancedConfigJson;

    @Schema(description = "活动类别(多选)，逗号隔开", example = "1,2,3")
    private String activityCategoryids;

    @Schema(description = "草稿ID(覆盖更新时传)")
    private Long id;

    @Schema(description = "剧情文案")
    private String storyText;

    @Schema(description = "剧情配图URL")
    private String storyImg;

    @Schema(description = "剧情多节点JSON列表 [{\"text\":\"\",\"tag\":\"\",\"img\":\"\"}]")
    private String storyJson;

    @Schema(description = "一级提示")
    private String hint1;

    @Schema(description = "二级提示")
    private String hint2;

    @Schema(description = "兜底答案/解析")
    private String answerReveal;

    @Schema(description = "拍照打卡要求描述")
    private String photoRequireDesc;

    @Schema(description = "拍照打卡是否需人工审核:0否 1是", example = "0")
    private Integer photoReview;

    @Schema(description = "语音讲解音频URL")
    private String audioUrl;

    @Schema(description = "语音时长(秒)")
    private Integer audioDuration;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getPlayers() {
        return players;
    }

    public void setPlayers(String players) {
        this.players = players;
    }

    public String getUsageLocation() {
        return usageLocation;
    }

    public void setUsageLocation(String usageLocation) {
        this.usageLocation = usageLocation;
    }

    public String getRequiredMaterials() {
        return requiredMaterials;
    }

    public void setRequiredMaterials(String requiredMaterials) {
        this.requiredMaterials = requiredMaterials;
    }

    // Getter和Setter方法
    public Long getOriginalTemplateId() {
        return originalTemplateId;
    }

    public void setOriginalTemplateId(Long originalTemplateId) {
        this.originalTemplateId = originalTemplateId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImgUrl() {
        return imgUrl;
    }

    public void setImgUrl(String imgUrl) {
        this.imgUrl = imgUrl;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getIsSync() {
        return isSync;
    }

    public void setIsSync(Integer isSync) {
        this.isSync = isSync;
    }

    public String getRuleInstructions() {
        return ruleInstructions;
    }

    public void setRuleInstructions(String ruleInstructions) {
        this.ruleInstructions = ruleInstructions;
    }

    public String getMerchantGuide() {
        return merchantGuide;
    }

    public void setMerchantGuide(String merchantGuide) {
        this.merchantGuide = merchantGuide;
    }

    public String getRewardRulesJson() {
        return rewardRulesJson;
    }

    public void setRewardRulesJson(String rewardRulesJson) {
        this.rewardRulesJson = rewardRulesJson;
    }

    public Integer getValidationMethod() {
        return validationMethod;
    }

    public void setValidationMethod(Integer validationMethod) {
        this.validationMethod = validationMethod;
    }

    public String getSensorType() { return sensorType; }
    public void setSensorType(String sensorType) { this.sensorType = sensorType; }
    public String getSensorConfig() { return sensorConfig; }
    public void setSensorConfig(String sensorConfig) { this.sensorConfig = sensorConfig; }

    public String getQuestionName() {
        return questionName;
    }

    public void setQuestionName(String questionName) {
        this.questionName = questionName;
    }

    public String getQuestionAnswer() {
        return questionAnswer;
    }

    public void setQuestionAnswer(String questionAnswer) {
        this.questionAnswer = questionAnswer;
    }

    public String getFeedbackText() {
        return feedbackText;
    }

    public void setFeedbackText(String feedbackText) {
        this.feedbackText = feedbackText;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public String getMedalImg() {
        return medalImg;
    }

    public void setMedalImg(String medalImg) {
        this.medalImg = medalImg;
    }

    public String getMedalName() {
        return medalName;
    }

    public void setMedalName(String medalName) {
        this.medalName = medalName;
    }

    public String getMedalStyle() {
        return medalStyle;
    }

    public void setMedalStyle(String medalStyle) {
        this.medalStyle = medalStyle;
    }

    public String getQuestionA() {
        return questionA;
    }

    public void setQuestionA(String questionA) {
        this.questionA = questionA;
    }

    public String getQuestionB() {
        return questionB;
    }

    public void setQuestionB(String questionB) {
        this.questionB = questionB;
    }

    public String getQuestionC() {
        return questionC;
    }

    public void setQuestionC(String questionC) {
        this.questionC = questionC;
    }

    public String getQuestionD() {
        return questionD;
    }

    public void setQuestionD(String questionD) {
        this.questionD = questionD;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getActivityCategoryids() {
        return activityCategoryids;
    }

    public void setActivityCategoryids(String activityCategoryids) {
        this.activityCategoryids = activityCategoryids;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStoryText() {
        return storyText;
    }

    public void setStoryText(String storyText) {
        this.storyText = storyText;
    }

    public String getStoryImg() {
        return storyImg;
    }

    public void setStoryImg(String storyImg) {
        this.storyImg = storyImg;
    }

    public String getStoryJson() {
        return storyJson;
    }

    public void setStoryJson(String storyJson) {
        this.storyJson = storyJson;
    }

    public String getHint1() {
        return hint1;
    }

    public void setHint1(String hint1) {
        this.hint1 = hint1;
    }

    public String getHint2() {
        return hint2;
    }

    public void setHint2(String hint2) {
        this.hint2 = hint2;
    }

    public String getAnswerReveal() {
        return answerReveal;
    }

    public void setAnswerReveal(String answerReveal) {
        this.answerReveal = answerReveal;
    }

    public String getPhotoRequireDesc() {
        return photoRequireDesc;
    }

    public void setPhotoRequireDesc(String photoRequireDesc) {
        this.photoRequireDesc = photoRequireDesc;
    }

    public Integer getPhotoReview() {
        return photoReview;
    }

    public void setPhotoReview(Integer photoReview) {
        this.photoReview = photoReview;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public String getQuestionImg() {
        return questionImg;
    }

    public void setQuestionImg(String questionImg) {
        this.questionImg = questionImg;
    }

    public String getQuestionAudio() {
        return questionAudio;
    }

    public void setQuestionAudio(String questionAudio) {
        this.questionAudio = questionAudio;
    }

    public String getQuestionOptionMediaJson() {
        return questionOptionMediaJson;
    }

    public void setQuestionOptionMediaJson(String questionOptionMediaJson) {
        this.questionOptionMediaJson = questionOptionMediaJson;
    }

    public String getPreferenceJson() { return preferenceJson; }

    public void setPreferenceJson(String preferenceJson) { this.preferenceJson = preferenceJson; }

    public String getAdvancedConfigJson() { return advancedConfigJson; }

    public void setAdvancedConfigJson(String advancedConfigJson) { this.advancedConfigJson = advancedConfigJson; }

    public Integer getAudioDuration() {
        return audioDuration;
    }

    public void setAudioDuration(Integer audioDuration) {
        this.audioDuration = audioDuration;
    }

    // equals方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CmsTemplatePublishRequest that = (CmsTemplatePublishRequest) o;
        return Objects.equals(originalTemplateId, that.originalTemplateId) &&
                Objects.equals(categoryId, that.categoryId) &&
                Objects.equals(title, that.title) &&
                Objects.equals(description, that.description) &&
                Objects.equals(imgUrl, that.imgUrl) &&
                Objects.equals(players, that.players) &&
                Objects.equals(usageLocation, that.usageLocation) &&
                Objects.equals(requiredMaterials, that.requiredMaterials) &&
                Objects.equals(duration, that.duration) &&
                Objects.equals(difficulty, that.difficulty) &&
                Objects.equals(isSync, that.isSync) &&
                Objects.equals(ruleInstructions, that.ruleInstructions) &&
                Objects.equals(validationMethod, that.validationMethod) &&
                Objects.equals(interactionType, that.interactionType) &&
                Objects.equals(sensorType, that.sensorType) &&
                Objects.equals(sensorConfig, that.sensorConfig) &&
                Objects.equals(questionName, that.questionName) &&
                Objects.equals(questionAnswer, that.questionAnswer) &&
                Objects.equals(feedbackText, that.feedbackText) &&
                Objects.equals(couponId, that.couponId) &&
                Objects.equals(medalStyle, that.medalStyle) &&
                Objects.equals(questionA, that.questionA) &&
                Objects.equals(questionB, that.questionB) &&
                Objects.equals(questionC, that.questionC) &&
                Objects.equals(questionD, that.questionD) &&
                Objects.equals(correctAnswer, that.correctAnswer) &&
                Objects.equals(activityCategoryids, that.activityCategoryids) &&
                Objects.equals(storyText, that.storyText) &&
                Objects.equals(storyImg, that.storyImg) &&
                Objects.equals(storyJson, that.storyJson) &&
                Objects.equals(hint1, that.hint1) &&
                Objects.equals(hint2, that.hint2) &&
                Objects.equals(answerReveal, that.answerReveal) &&
                Objects.equals(photoRequireDesc, that.photoRequireDesc) &&
                Objects.equals(photoReview, that.photoReview) &&
                Objects.equals(audioUrl, that.audioUrl) &&
                Objects.equals(audioDuration, that.audioDuration) &&
                Objects.equals(questionImg, that.questionImg) &&
                Objects.equals(questionAudio, that.questionAudio) &&
                Objects.equals(questionOptionMediaJson, that.questionOptionMediaJson) &&
                Objects.equals(preferenceJson, that.preferenceJson) &&
                Objects.equals(advancedConfigJson, that.advancedConfigJson);
    }

    // hashCode方法
    @Override
    public int hashCode() {
        return Objects.hash(originalTemplateId, categoryId, title, description, imgUrl,players,usageLocation,requiredMaterials, duration, difficulty, isSync, ruleInstructions, validationMethod, interactionType, sensorType, sensorConfig, questionName, questionAnswer, feedbackText, couponId, medalStyle, questionA, questionB, questionC, questionD, correctAnswer, activityCategoryids, storyText, storyImg, storyJson, hint1, hint2, answerReveal, photoRequireDesc, photoReview, audioUrl, audioDuration, questionImg, questionAudio, questionOptionMediaJson, preferenceJson, advancedConfigJson);
    }

    // toString方法
    @Override
    public String toString() {
        return "CmsTemplatePublishRequest{" +
                "originalTemplateId=" + originalTemplateId +
                ", categoryId=" + categoryId +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imgUrl='" + imgUrl + '\'' +
                ", players='" + players + '\'' +
                ", usageLocation='" + usageLocation + '\'' +
                ", requiredMaterials='" + requiredMaterials + '\'' +


                ", duration=" + duration +
                ", difficulty='" + difficulty + '\'' +
                ", isSync=" + isSync +
                ", ruleInstructions='" + ruleInstructions + '\'' +
                ", validationMethod=" + validationMethod +
                ", sensorType='" + sensorType + '\'' +
                ", sensorConfig='" + sensorConfig + '\'' +
                ", questionName='" + questionName + '\'' +
                ", questionAnswer='" + questionAnswer + '\'' +
                ", feedbackText='" + feedbackText + '\'' +
                ", couponId=" + couponId +
                ", medalStyle='" + medalStyle + '\'' +
                ", questionA='" + questionA + '\'' +
                ", questionB='" + questionB + '\'' +
                ", questionC='" + questionC + '\'' +
                ", questionD='" + questionD + '\'' +
                ", correctAnswer='" + correctAnswer + '\'' +
                ", activityCategoryids='" + activityCategoryids + '\'' +
                ", storyText='" + storyText + '\'' +
                ", storyImg='" + storyImg + '\'' +
                ", storyJson='" + storyJson + '\'' +
                ", hint1='" + hint1 + '\'' +
                ", hint2='" + hint2 + '\'' +
                ", answerReveal='" + answerReveal + '\'' +
                ", photoRequireDesc='" + photoRequireDesc + '\'' +
                ", photoReview=" + photoReview +
                ", audioUrl='" + audioUrl + '\'' +
                ", audioDuration=" + audioDuration +
                ", advancedConfigJson='" + advancedConfigJson + '\'' +
                '}';
    }

    // 无参构造函数
    public CmsTemplatePublishRequest() {
    }

    // 全参构造函数（可选）
    public CmsTemplatePublishRequest(Long originalTemplateId, Long categoryId, String title,
                                     String description, String imgUrl,String players,String usageLocation,String requiredMaterials, Integer duration, String difficulty, Integer isSync, String ruleInstructions, Integer validationMethod, String questionName, String questionAnswer, String feedbackText, Long couponId, String questionA, String questionB, String questionC, String questionD, String correctAnswer, String activityCategoryids) {
        this.originalTemplateId = originalTemplateId;
        this.categoryId = categoryId;
        this.title = title;
        this.description = description;
        this.imgUrl = imgUrl;
        this.players = imgUrl;
        this.usageLocation = usageLocation;
        this.requiredMaterials = requiredMaterials;
        this.duration = duration;
        this.difficulty = difficulty;
        this.isSync = isSync;
        this.ruleInstructions = ruleInstructions;
        this.validationMethod = validationMethod;
        this.questionName = questionName;
        this.questionAnswer = questionAnswer;
        this.feedbackText = feedbackText;
        this.couponId = couponId;
        this.questionA = questionA;
        this.questionB = questionB;
        this.questionC = questionC;
        this.questionD = questionD;
        this.correctAnswer = correctAnswer;
        this.activityCategoryids = activityCategoryids;
    }
}
