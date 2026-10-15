package com.chengyinhub.business.domain.vo;

/** 自动开售提审结果；缺少正常前提时保留当前生命周期并说明下一步。 */
public class TopicSaleReviewProgress {
    private final Long taskId;
    private final String nextStep;

    public TopicSaleReviewProgress(Long taskId, String nextStep) {
        this.taskId = taskId;
        this.nextStep = nextStep;
    }
    public Long getTaskId() { return taskId; }
    public String getNextStep() { return nextStep; }
}
