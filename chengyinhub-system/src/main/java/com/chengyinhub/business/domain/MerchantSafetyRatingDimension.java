package com.chengyinhub.business.domain;

public class MerchantSafetyRatingDimension {
    private Long id;
    private Long ratingId;
    private String dimensionCode;
    private String level;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRatingId() { return ratingId; }
    public void setRatingId(Long ratingId) { this.ratingId = ratingId; }
    public String getDimensionCode() { return dimensionCode; }
    public void setDimensionCode(String dimensionCode) { this.dimensionCode = dimensionCode; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}
