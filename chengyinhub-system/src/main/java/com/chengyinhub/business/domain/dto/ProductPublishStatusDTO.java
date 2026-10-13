package com.chengyinhub.business.domain.dto;

/** 积分商城商品目录的上下架请求，只允许写入商品标识与目标状态。 */
public class ProductPublishStatusDTO {

    private Long id;

    /** 0 审核中，1 上架，2 下架。 */
    private Integer publishStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(Integer publishStatus) {
        this.publishStatus = publishStatus;
    }
}
