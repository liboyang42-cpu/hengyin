package com.chengyinhub.business.domain.dto;

import java.util.List;

public class MerchantReviewCreateRequest {
    private Long merchantRowId;
    private Long registrationId;
    private Integer rating;
    private String content;
    private List<String> imageUrls;
    private String requestId;

    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long merchantRowId) { this.merchantRowId = merchantRowId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
