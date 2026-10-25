package com.chengyinhub.business.domain;

import com.chengyinhub.system.domain.SysCategory;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.List;

/**
 * 模板库对象 cms_template_library
 * 
 * @author webxh
 * @date 2025-09-02
 */
public class CmsTemplateLibrary extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 模板ID */
    private Long id;

    /** 发布用户ID */
    @Excel(name = "发布用户ID")
    private Long memberId;

    @Excel(name = "关联的用户模板ID")
    private Long memberTemplateId;

    /** 沿 memberTemplateId/owner 读取的个人模板语义；公共投影不能独立改写。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String interactionType;

    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }

    /** 模板类别ID */
    @Excel(name = "模板类别ID")
    private Long categoryId;

    /** 标题 */
    @Excel(name = "标题")
    private String title;

    /** 介绍 */
    @Excel(name = "介绍")
    private String description;

    /** 图片URL */
    @Excel(name = "图片URL")
    private String imgUrl;


    @Excel(name = "游戏人数")
    private String players;

    @Excel(name = "适用场景")
    private String usageLocation;

    @Excel(name = "所需材料")
    private String requiredMaterials;



    /** 时长(分钟) */
    @Excel(name = "时长(分钟)")
    private Long duration;

    /** 难度 */
    @Excel(name = "难度")
    private String difficulty;

    /** 是否同步到模板库 */
    @Excel(name = "是否同步到模板库")
    private Integer isSync;

    /** 规则说明 */
    @Excel(name = "规则说明")
    private String ruleInstructions;

    /** 给商家/店员看的执行说明;玩家侧一律不下发 */
    private String merchantGuide;

    /** 条件奖励:同 CmsMemberTemplate#rewardRulesJson */
    private String rewardRulesJson;

    /** 验证方式:1、文字验证 2、图片验证  3、选项验证 */
    @Excel(name = "验证方式:1、文字验证 2、图片验证  3、选项验证")
    private Integer validationMethod;

    /** vm=7 的传感器类型:still/steps/audio_clip */
    private String sensorType;

    /** vm=7 的玩法参数 JSON */
    private String sensorConfig;

    /** 问题名称 */
    @Excel(name = "问题名称")
    private String questionName;

    /** 问题答案 */
    @Excel(name = "问题答案")
    private String questionAnswer;

    /** 反馈文字 */
    @Excel(name = "反馈文字")
    private String feedbackText;

    /** 反馈优惠券ID */
    @Excel(name = "反馈优惠券ID")
    private Long couponId;

    /** 公共详情从关联的用户模板真源投影，不在模板库表重复持久化。 */
    private String medalImg;

    /** 公共详情从关联的用户模板真源投影，不在模板库表重复持久化。 */
    private String medalName;

    /** 勋章样式:glow=二维发光/enamel=3D珐琅。 */
    private String medalStyle;

    /** 剧情文案 */
    private String storyText;

    /** 剧情配图URL */
    private String storyImg;

    /** 剧情多节点JSON列表 [{"text":"","tag":"","img":""}] */
    private String storyJson;

    /** 一级提示 */
    private String hint1;

    /** 二级提示 */
    private String hint2;

    /** 兜底答案/解析 */
    private String answerReveal;

    /** 拍照打卡要求描述 */
    private String photoRequireDesc;

    /** 拍照打卡是否需人工审核:0否 1是 */
    private Integer photoReview;

    /** 语音讲解音频URL */
    private String audioUrl;

    /** 语音时长(秒) */
    private Integer audioDuration;

    /** 问题选项A */
    @Excel(name = "问题选项A")
    private String questionA;

    /** 问题选项B */
    @Excel(name = "问题选项B")
    private String questionB;

    /** 问题选项C */
    @Excel(name = "问题选项C")
    private String questionC;

    /** 问题选项D */
    @Excel(name = "问题选项D")
    private String questionD;

    /** 正确答案(A/B/C/D) */
    @Excel(name = "正确答案(A/B/C/D)")
    private String correctAnswer;

    /** 题干配图URL(看图答题) */
    @Excel(name = "题干配图URL")
    private String questionImg;

    /** 题干配音URL(听音答题) */
    @Excel(name = "题干配音URL")
    private String questionAudio;

    /** 选项媒体,按字母对齐 questionA~D:{"A":{"img":"..","audio":".."}} */
    private String questionOptionMediaJson;

    /** 偏好题组配置(validationMethod=6) */
    private String preferenceJson;

    /** 高级玩法配置(schemaVersion=1) */
    private String advancedConfigJson;

    /** 活动类别(多选)，逗号隔开 */
    @Excel(name = "活动类别(多选)，逗号隔开")
    private String activityCategoryids;

    /** 后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败； */
    @Excel(name = "后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Long status;

    /** 发布状态：0下架中  1已上架 */
    @Excel(name = "发布状态：0下架中  1已上架")
    private Long publishStatus;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String reason;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /**评分-后台输入*/
    private String rating;

    /**使用人数-后台输入*/
    private Integer useNum;

    /**
     * 模板形态:0 单节点玩法 / 1 剧情包 / 2 店铺 IP 包。
     *
     * <p>★ 这是模板市场的**筛选轴**,不是展示标签:商家想找「能直接套到我店里的 IP 包」,
     * 在混着单节点玩法的一锅端列表里是找不到的。
     * <p>⚠️ 默认 0 意味着「没标过的一律算单节点玩法」——
     * 这与库里存量的实际形态一致,且不会把整套剧情包误标成单节点(那是反向的错)。
     */
    private Integer packType;



    /**验证方式*/
    private String validationMethodStr;

    /**反馈方式*/
    private String feedbackMethodStr;



    private List<SysCategory> sysCategoryList;


    private SmsCoupon smsCoupon;


    private String publisher;


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

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public SmsCoupon getSmsCoupon() {
        return smsCoupon;
    }

    public void setSmsCoupon(SmsCoupon smsCoupon) {
        this.smsCoupon = smsCoupon;
    }

    public List<SysCategory> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<SysCategory> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
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

    public Long getMemberTemplateId() {
        return memberTemplateId;
    }

    public void setMemberTemplateId(Long memberTemplateId) {
        this.memberTemplateId = memberTemplateId;
    }

    public void setCategoryId(Long categoryId)
    {
        this.categoryId = categoryId;
    }

    public Long getCategoryId()
    {
        return categoryId;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getTitle()
    {
        return title;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getDescription()
    {
        return description;
    }

    public void setImgUrl(String imgUrl)
    {
        this.imgUrl = imgUrl;
    }

    public String getImgUrl()
    {
        return imgUrl;
    }

    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    public Long getDuration()
    {
        return duration;
    }

    public void setDifficulty(String difficulty)
    {
        this.difficulty = difficulty;
    }

    public String getDifficulty()
    {
        return difficulty;
    }

    public void setIsSync(Integer isSync)
    {
        this.isSync = isSync;
    }

    public Integer getIsSync()
    {
        return isSync;
    }

    public void setRuleInstructions(String ruleInstructions)
    {
        this.ruleInstructions = ruleInstructions;
    }

    public String getRuleInstructions()
    {
        return ruleInstructions;
    }

    public void setMerchantGuide(String merchantGuide)
    {
        this.merchantGuide = merchantGuide;
    }

    public String getMerchantGuide()
    {
        return merchantGuide;
    }

    public void setRewardRulesJson(String rewardRulesJson) { this.rewardRulesJson = rewardRulesJson; }
    public String getRewardRulesJson() { return rewardRulesJson; }

    public void setValidationMethod(Integer validationMethod)
    {
        this.validationMethod = validationMethod;
    }

    public Integer getValidationMethod()
    {
        return validationMethod;
    }

    public String getSensorType() { return sensorType; }
    public void setSensorType(String sensorType) { this.sensorType = sensorType; }
    public String getSensorConfig() { return sensorConfig; }
    public void setSensorConfig(String sensorConfig) { this.sensorConfig = sensorConfig; }

    public void setQuestionName(String questionName)
    {
        this.questionName = questionName;
    }

    public String getQuestionName()
    {
        return questionName;
    }

    public void setQuestionAnswer(String questionAnswer)
    {
        this.questionAnswer = questionAnswer;
    }

    public String getQuestionAnswer()
    {
        return questionAnswer;
    }

    public void setFeedbackText(String feedbackText)
    {
        this.feedbackText = feedbackText;
    }

    public String getFeedbackText()
    {
        return feedbackText;
    }

    public void setCouponId(Long couponId)
    {
        this.couponId = couponId;
    }

    public Long getCouponId()
    {
        return couponId;
    }

    public String getMedalImg()
    {
        return medalImg;
    }

    public void setMedalImg(String medalImg)
    {
        this.medalImg = medalImg;
    }

    public String getMedalName()
    {
        return medalName;
    }

    public void setMedalName(String medalName)
    {
        this.medalName = medalName;
    }

    public String getMedalStyle()
    {
        return medalStyle;
    }

    public void setMedalStyle(String medalStyle)
    {
        this.medalStyle = medalStyle;
    }

    public void setStoryText(String storyText)
    {
        this.storyText = storyText;
    }

    public String getStoryText()
    {
        return storyText;
    }

    public void setStoryImg(String storyImg)
    {
        this.storyImg = storyImg;
    }

    public String getStoryImg()
    {
        return storyImg;
    }

    public void setStoryJson(String storyJson)
    {
        this.storyJson = storyJson;
    }

    public String getStoryJson()
    {
        return storyJson;
    }

    public void setHint1(String hint1)
    {
        this.hint1 = hint1;
    }

    public String getHint1()
    {
        return hint1;
    }

    public void setHint2(String hint2)
    {
        this.hint2 = hint2;
    }

    public String getHint2()
    {
        return hint2;
    }

    public void setAnswerReveal(String answerReveal)
    {
        this.answerReveal = answerReveal;
    }

    public String getAnswerReveal()
    {
        return answerReveal;
    }

    public void setPhotoRequireDesc(String photoRequireDesc)
    {
        this.photoRequireDesc = photoRequireDesc;
    }

    public String getPhotoRequireDesc()
    {
        return photoRequireDesc;
    }

    public void setPhotoReview(Integer photoReview)
    {
        this.photoReview = photoReview;
    }

    public Integer getPhotoReview()
    {
        return photoReview;
    }

    public void setAudioUrl(String audioUrl)
    {
        this.audioUrl = audioUrl;
    }

    public String getAudioUrl()
    {
        return audioUrl;
    }

    public void setAudioDuration(Integer audioDuration)
    {
        this.audioDuration = audioDuration;
    }

    public Integer getAudioDuration()
    {
        return audioDuration;
    }

    public void setQuestionA(String questionA)
    {
        this.questionA = questionA;
    }

    public String getQuestionA()
    {
        return questionA;
    }

    public void setQuestionB(String questionB)
    {
        this.questionB = questionB;
    }

    public String getQuestionB()
    {
        return questionB;
    }

    public void setQuestionC(String questionC)
    {
        this.questionC = questionC;
    }

    public String getQuestionC()
    {
        return questionC;
    }

    public void setQuestionD(String questionD)
    {
        this.questionD = questionD;
    }

    public String getQuestionD()
    {
        return questionD;
    }

    public void setCorrectAnswer(String correctAnswer)
    {
        this.correctAnswer = correctAnswer;
    }

    public String getCorrectAnswer()
    {
        return correctAnswer;
    }

    public void setQuestionImg(String questionImg)
{
        this.questionImg = questionImg;
    }

    public String getQuestionImg()
{
        return questionImg;
    }

    public void setQuestionAudio(String questionAudio)
{
        this.questionAudio = questionAudio;
    }

    public String getQuestionAudio()
{
        return questionAudio;
    }

    public void setQuestionOptionMediaJson(String questionOptionMediaJson)
{
        this.questionOptionMediaJson = questionOptionMediaJson;
    }

    public String getQuestionOptionMediaJson()
{
        return questionOptionMediaJson;
    }

    public void setPreferenceJson(String preferenceJson) { this.preferenceJson = preferenceJson; }

    public String getPreferenceJson() { return preferenceJson; }

    public String getAdvancedConfigJson() { return advancedConfigJson; }

    public void setAdvancedConfigJson(String advancedConfigJson) { this.advancedConfigJson = advancedConfigJson; }

    public void setActivityCategoryids(String activityCategoryids)
    {
        this.activityCategoryids = activityCategoryids;
    }

    public String getActivityCategoryids()
    {
        return activityCategoryids;
    }

    public void setStatus(Long status)
    {
        this.status = status;
    }

    public Long getStatus()
    {
        return status;
    }

    public void setPublishStatus(Long publishStatus)
    {
        this.publishStatus = publishStatus;
    }

    public Long getPublishStatus()
    {
        return publishStatus;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getReason()
    {
        return reason;
    }

    public void setDelFlag(Integer delFlag)
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag()
    {
        return delFlag;
    }


    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public Integer getPackType() { return packType; }
    public void setPackType(Integer packType) { this.packType = packType; }

    public Integer getUseNum() {
        return useNum;
    }

    public void setUseNum(Integer useNum) {
        this.useNum = useNum;
    }

    public String getValidationMethodStr() {
        return validationMethodStr;
    }

    public void setValidationMethodStr(String validationMethodStr) {
        this.validationMethodStr = validationMethodStr;
    }

    public String getFeedbackMethodStr() {
        return feedbackMethodStr;
    }

    public void setFeedbackMethodStr(String feedbackMethodStr) {
        this.feedbackMethodStr = feedbackMethodStr;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
                .append("memberTemplateId", getMemberTemplateId())
            .append("categoryId", getCategoryId())
            .append("title", getTitle())
            .append("description", getDescription())
            .append("imgUrl", getImgUrl())
            .append("duration", getDuration())
            .append("difficulty", getDifficulty())
            .append("isSync", getIsSync())
            .append("ruleInstructions", getRuleInstructions())
            .append("merchantGuide", getMerchantGuide())
            .append("rewardRulesJson", getRewardRulesJson())
            .append("validationMethod", getValidationMethod())
            .append("sensorType", getSensorType())
            .append("sensorConfig", getSensorConfig())
            .append("questionName", getQuestionName())
            .append("questionAnswer", getQuestionAnswer())
            .append("feedbackText", getFeedbackText())
            .append("couponId", getCouponId())
            .append("medalImg", getMedalImg())
            .append("medalName", getMedalName())
            .append("medalStyle", getMedalStyle())
            .append("storyText", getStoryText())
            .append("storyImg", getStoryImg())
            .append("storyJson", getStoryJson())
            .append("hint1", getHint1())
            .append("hint2", getHint2())
            .append("answerReveal", getAnswerReveal())
            .append("photoRequireDesc", getPhotoRequireDesc())
            .append("photoReview", getPhotoReview())
            .append("audioUrl", getAudioUrl())
            .append("audioDuration", getAudioDuration())
            .append("questionA", getQuestionA())
            .append("questionB", getQuestionB())
            .append("questionC", getQuestionC())
            .append("questionD", getQuestionD())
            .append("correctAnswer", getCorrectAnswer())
            .append("preferenceJson", getPreferenceJson())
            .append("advancedConfigJson", getAdvancedConfigJson())
            .append("activityCategoryids", getActivityCategoryids())
            .append("status", getStatus())
            .append("publishStatus", getPublishStatus())
            .append("reason", getReason())
            .append("delFlag", getDelFlag())
                .append("rating", getRating())
                .append("useNum", getUseNum())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
