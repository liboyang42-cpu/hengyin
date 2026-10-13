package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

public class TopicOnsiteMetricsVO {
    private int nService;
    private int arrived;
    private int s1;
    private int s3;
    private int completed;
    private int joined;
    private int r90Eligible;
    private int r90Completed;
    private int r90Observing;
    private int refunded;
    private double arrivalRate;
    private double completionRate;
    private double refundRate;
    private int complaintUsers;
    private double complaintUserRate;
    private double waitCoverage;
    private int negativeWaitSamples;
    private boolean negativeWaitWarning;
    private boolean waitGatePassed;
    private boolean g2Passed;
    private List<String> g2Reasons = new ArrayList<>();
    private List<TopicChapterWaitVO> chapterWaits = new ArrayList<>();

    public int getNService() { return nService; }
    public void setNService(int nService) { this.nService = nService; }
    public int getArrived() { return arrived; }
    public void setArrived(int arrived) { this.arrived = arrived; }
    public int getS1() { return s1; }
    public void setS1(int s1) { this.s1 = s1; }
    public int getS3() { return s3; }
    public void setS3(int s3) { this.s3 = s3; }
    public int getCompleted() { return completed; }
    public void setCompleted(int completed) { this.completed = completed; }
    public int getJoined() { return joined; }
    public void setJoined(int joined) { this.joined = joined; }
    public int getR90Eligible() { return r90Eligible; }
    public void setR90Eligible(int r90Eligible) { this.r90Eligible = r90Eligible; }
    public int getR90Completed() { return r90Completed; }
    public void setR90Completed(int r90Completed) { this.r90Completed = r90Completed; }
    public int getR90Observing() { return r90Observing; }
    public void setR90Observing(int r90Observing) { this.r90Observing = r90Observing; }
    public int getRefunded() { return refunded; }
    public void setRefunded(int refunded) { this.refunded = refunded; }
    public double getArrivalRate() { return arrivalRate; }
    public void setArrivalRate(double arrivalRate) { this.arrivalRate = arrivalRate; }
    public double getCompletionRate() { return completionRate; }
    public void setCompletionRate(double completionRate) { this.completionRate = completionRate; }
    public double getRefundRate() { return refundRate; }
    public void setRefundRate(double refundRate) { this.refundRate = refundRate; }
    public int getComplaintUsers() { return complaintUsers; }
    public void setComplaintUsers(int complaintUsers) { this.complaintUsers = complaintUsers; }
    public double getComplaintUserRate() { return complaintUserRate; }
    public void setComplaintUserRate(double complaintUserRate) { this.complaintUserRate = complaintUserRate; }
    public double getWaitCoverage() { return waitCoverage; }
    public void setWaitCoverage(double waitCoverage) { this.waitCoverage = waitCoverage; }
    public int getNegativeWaitSamples() { return negativeWaitSamples; }
    public void setNegativeWaitSamples(int negativeWaitSamples) { this.negativeWaitSamples = negativeWaitSamples; }
    public boolean isNegativeWaitWarning() { return negativeWaitWarning; }
    public void setNegativeWaitWarning(boolean negativeWaitWarning) { this.negativeWaitWarning = negativeWaitWarning; }
    public boolean isWaitGatePassed() { return waitGatePassed; }
    public void setWaitGatePassed(boolean waitGatePassed) { this.waitGatePassed = waitGatePassed; }
    public boolean isG2Passed() { return g2Passed; }
    public void setG2Passed(boolean g2Passed) { this.g2Passed = g2Passed; }
    public List<String> getG2Reasons() { return g2Reasons; }
    public void setG2Reasons(List<String> g2Reasons) { this.g2Reasons = g2Reasons; }
    public List<TopicChapterWaitVO> getChapterWaits() { return chapterWaits; }
    public void setChapterWaits(List<TopicChapterWaitVO> chapterWaits) { this.chapterWaits = chapterWaits; }
}
