package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

public class MerchantCrmCustomerPageVO {
    private List<MerchantCrmCustomerRowVO> rows = new ArrayList<>();
    private Long total = 0L;
    private Integer pageNum;
    private Integer pageSize;
    private MerchantCrmSegmentCountsVO segmentCounts = new MerchantCrmSegmentCountsVO();
    private List<MerchantCrmTagVO> availableTags = new ArrayList<>();
    private String couponDeliveryStatus;
    private String notificationDeliveryStatus;

    public List<MerchantCrmCustomerRowVO> getRows() { return rows; }
    public void setRows(List<MerchantCrmCustomerRowVO> value) { rows = value; }
    public Long getTotal() { return total; }
    public void setTotal(Long value) { total = value; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer value) { pageNum = value; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer value) { pageSize = value; }
    public MerchantCrmSegmentCountsVO getSegmentCounts() { return segmentCounts; }
    public void setSegmentCounts(MerchantCrmSegmentCountsVO value) { segmentCounts = value; }
    public List<MerchantCrmTagVO> getAvailableTags() { return availableTags; }
    public void setAvailableTags(List<MerchantCrmTagVO> value) { availableTags = value; }
    public String getCouponDeliveryStatus() { return couponDeliveryStatus; }
    public void setCouponDeliveryStatus(String value) { couponDeliveryStatus = value; }
    public String getNotificationDeliveryStatus() { return notificationDeliveryStatus; }
    public void setNotificationDeliveryStatus(String value) { notificationDeliveryStatus = value; }
}
