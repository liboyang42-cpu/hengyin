package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MerchantSafetySummaryVO {
    private Long merchantId;
    private String sampleStatus;
    private Integer sampleCount;
    private Integer minimumSampleCount;
    private Integer windowDays;
    private String level;
    private Date latestAt;
    private String disclaimerCode;
    private List<Dimension> dimensions = new ArrayList<>();

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getSampleStatus() { return sampleStatus; }
    public void setSampleStatus(String sampleStatus) { this.sampleStatus = sampleStatus; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Integer getMinimumSampleCount() { return minimumSampleCount; }
    public void setMinimumSampleCount(Integer minimumSampleCount) { this.minimumSampleCount = minimumSampleCount; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Date getLatestAt() { return latestAt; }
    public void setLatestAt(Date latestAt) { this.latestAt = latestAt; }
    public String getDisclaimerCode() { return disclaimerCode; }
    public void setDisclaimerCode(String disclaimerCode) { this.disclaimerCode = disclaimerCode; }
    public List<Dimension> getDimensions() { return dimensions; }
    public void setDimensions(List<Dimension> dimensions) { this.dimensions = dimensions; }

    public static class Dimension {
        private String code;
        private String level;
        private Integer sampleCount;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public Integer getSampleCount() { return sampleCount; }
        public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    }
}
