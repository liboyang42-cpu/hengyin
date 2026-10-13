package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 俱乐部对公收款人最小资料（独立私密表，不并进公共 Club 模型）。
 *
 * <p>九组最小字段一个都不能缺；敏感值由 mapper 侧 PiiCryptoTypeHandler 落密文，
 * 统一社会信用代码另建 blind index 列供加密后精确检索。
 */
public class ClubCorporatePayeeProfile {
    public static final String VERIFY_PENDING = "PENDING";
    public static final String VERIFY_VERIFIED = "VERIFIED";
    public static final String VERIFY_REJECTED = "REJECTED";

    private Long id;
    private Long clubId;
    private Long version;
    private Integer current;
    private String legalName;
    private String unifiedSocialCreditCode;
    private String creditCodeIndex;
    private String licenseObjectKey;
    private String licenseHash;
    private String bankAccountName;
    private String bankName;
    private String corporateBankAccountNo;
    private String invoiceType;
    private String taxNo;
    private String financeContactName;
    private String financeContactPhone;
    private String factsHash;
    private String verifyStatus;
    private Long verifiedBy;
    private Date verifiedAt;
    private String verifyResult;
    private Long createdBy;
    private Date createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public Long getVersion() { return version; } public void setVersion(Long v) { version = v; }
    public Integer getCurrent() { return current; } public void setCurrent(Integer v) { current = v; }
    public String getLegalName() { return legalName; } public void setLegalName(String v) { legalName = v; }
    public String getUnifiedSocialCreditCode() { return unifiedSocialCreditCode; }
    public void setUnifiedSocialCreditCode(String v) { unifiedSocialCreditCode = v; }
    public String getCreditCodeIndex() { return creditCodeIndex; } public void setCreditCodeIndex(String v) { creditCodeIndex = v; }
    public String getLicenseObjectKey() { return licenseObjectKey; } public void setLicenseObjectKey(String v) { licenseObjectKey = v; }
    public String getLicenseHash() { return licenseHash; } public void setLicenseHash(String v) { licenseHash = v; }
    public String getBankAccountName() { return bankAccountName; } public void setBankAccountName(String v) { bankAccountName = v; }
    public String getBankName() { return bankName; } public void setBankName(String v) { bankName = v; }
    public String getCorporateBankAccountNo() { return corporateBankAccountNo; }
    public void setCorporateBankAccountNo(String v) { corporateBankAccountNo = v; }
    public String getInvoiceType() { return invoiceType; } public void setInvoiceType(String v) { invoiceType = v; }
    public String getTaxNo() { return taxNo; } public void setTaxNo(String v) { taxNo = v; }
    public String getFinanceContactName() { return financeContactName; } public void setFinanceContactName(String v) { financeContactName = v; }
    public String getFinanceContactPhone() { return financeContactPhone; } public void setFinanceContactPhone(String v) { financeContactPhone = v; }
    public String getFactsHash() { return factsHash; } public void setFactsHash(String v) { factsHash = v; }
    public String getVerifyStatus() { return verifyStatus; } public void setVerifyStatus(String v) { verifyStatus = v; }
    public Long getVerifiedBy() { return verifiedBy; } public void setVerifiedBy(Long v) { verifiedBy = v; }
    public Date getVerifiedAt() { return verifiedAt; } public void setVerifiedAt(Date v) { verifiedAt = v; }
    public String getVerifyResult() { return verifyResult; } public void setVerifyResult(String v) { verifyResult = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public Date getCreatedAt() { return createdAt; } public void setCreatedAt(Date v) { createdAt = v; }
}
