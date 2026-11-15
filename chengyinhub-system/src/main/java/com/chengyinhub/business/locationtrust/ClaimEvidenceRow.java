package com.chengyinhub.business.locationtrust;

import java.math.BigDecimal;
import java.util.Date;

/** One row of {@code location_claim_evidence}. */
public class ClaimEvidenceRow {
    private Long id;
    private Long memberId;
    private String placeRef;
    private BigDecimal placeLat;
    private BigDecimal placeLng;
    private String captureSource;
    private String photoSha256;
    private Long photoPhash;
    private Date photoTakenAt;
    private BigDecimal photoLat;
    private BigDecimal photoLng;
    private Long fixId;
    private String verdict;
    private String reasons;
    private Long duplicateOf;
    private Date consumedAt;
    private String consumedRef;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getPlaceRef() { return placeRef; }
    public void setPlaceRef(String placeRef) { this.placeRef = placeRef; }
    public BigDecimal getPlaceLat() { return placeLat; }
    public void setPlaceLat(BigDecimal placeLat) { this.placeLat = placeLat; }
    public BigDecimal getPlaceLng() { return placeLng; }
    public void setPlaceLng(BigDecimal placeLng) { this.placeLng = placeLng; }
    public String getCaptureSource() { return captureSource; }
    public void setCaptureSource(String captureSource) { this.captureSource = captureSource; }
    public String getPhotoSha256() { return photoSha256; }
    public void setPhotoSha256(String photoSha256) { this.photoSha256 = photoSha256; }
    public Long getPhotoPhash() { return photoPhash; }
    public void setPhotoPhash(Long photoPhash) { this.photoPhash = photoPhash; }
    public Date getPhotoTakenAt() { return photoTakenAt; }
    public void setPhotoTakenAt(Date photoTakenAt) { this.photoTakenAt = photoTakenAt; }
    public BigDecimal getPhotoLat() { return photoLat; }
    public void setPhotoLat(BigDecimal photoLat) { this.photoLat = photoLat; }
    public BigDecimal getPhotoLng() { return photoLng; }
    public void setPhotoLng(BigDecimal photoLng) { this.photoLng = photoLng; }
    public Long getFixId() { return fixId; }
    public void setFixId(Long fixId) { this.fixId = fixId; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getReasons() { return reasons; }
    public void setReasons(String reasons) { this.reasons = reasons; }
    public Long getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(Long duplicateOf) { this.duplicateOf = duplicateOf; }
    public Date getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Date consumedAt) { this.consumedAt = consumedAt; }
    public String getConsumedRef() { return consumedRef; }
    public void setConsumedRef(String consumedRef) { this.consumedRef = consumedRef; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
