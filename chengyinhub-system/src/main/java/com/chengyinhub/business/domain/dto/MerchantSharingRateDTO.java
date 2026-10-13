package com.chengyinhub.business.domain.dto;


import java.util.List;

/**
 * 商家分润比例设置DTO
 */
public class MerchantSharingRateDTO {

    /** 报名记录ID */
    private Long id;

    /** 处理状态 */
    private Integer status;

    /** 驳回原因 */
    private String reason;

    /** 分润比例列表 */
    private List<SharingRateItem> sharingRates;

    // getter setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<SharingRateItem> getSharingRates() {
        return sharingRates;
    }

    public void setSharingRates(List<SharingRateItem> sharingRates) {
        this.sharingRates = sharingRates;
    }

    /**
     * 分润比例项
     */
    public static class SharingRateItem {

        /** 记录ID */
        private Long id;

        /** 商家用户ID */
        private Long memberId;

        /** 主题ID */
        private Long topicId;

        /** 分润比例 */
        private Double sharingRate;

        // getter setter
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getMemberId() {
            return memberId;
        }

        public void setMemberId(Long memberId) {
            this.memberId = memberId;
        }

        public Long getTopicId() {
            return topicId;
        }

        public void setTopicId(Long topicId) {
            this.topicId = topicId;
        }

        public Double getSharingRate() {
            return sharingRate;
        }

        public void setSharingRate(Double sharingRate) {
            this.sharingRate = sharingRate;
        }
    }
}