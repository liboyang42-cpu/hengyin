package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 同一受益角色、同一服务单元的一项原子经济请求。 */
public class SettlementClaim extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_ACTIVE = "ACTIVE";

    private Long id;
    private String commercialContextType;
    private Long commercialContextId;
    private String fulfillmentType;
    private Long fulfillmentId;
    private String beneficiaryRole;
    private Long beneficiaryId;
    private String serviceUnit;
    private String settlementRoute;
    private String sourceContractType;
    private Long sourceContractId;
    private String status;

    public static SettlementClaim request(String contextType, Long contextId,
            String fulfillmentType, Long fulfillmentId, String beneficiaryRole,
            Long beneficiaryId, String serviceUnit, String route,
            String sourceContractType, Long sourceContractId) {
        SettlementClaim claim = new SettlementClaim();
        claim.setCommercialContextType(contextType);
        claim.setCommercialContextId(contextId);
        claim.setFulfillmentType(fulfillmentType);
        claim.setFulfillmentId(fulfillmentId);
        claim.setBeneficiaryRole(beneficiaryRole);
        claim.setBeneficiaryId(beneficiaryId);
        claim.setServiceUnit(serviceUnit);
        claim.setSettlementRoute(route);
        claim.setSourceContractType(sourceContractType);
        claim.setSourceContractId(sourceContractId);
        claim.setStatus(STATUS_ACTIVE);
        return claim;
    }

    public String economicKey() {
        return commercialContextType + "|" + commercialContextId + "|"
                + fulfillmentType + "|" + fulfillmentId + "|"
                + beneficiaryRole + "|" + beneficiaryId + "|" + serviceUnit;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCommercialContextType() { return commercialContextType; }
    public void setCommercialContextType(String value) { this.commercialContextType = value; }
    public Long getCommercialContextId() { return commercialContextId; }
    public void setCommercialContextId(Long value) { this.commercialContextId = value; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String value) { this.fulfillmentType = value; }
    public Long getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(Long value) { this.fulfillmentId = value; }
    public String getBeneficiaryRole() { return beneficiaryRole; }
    public void setBeneficiaryRole(String value) { this.beneficiaryRole = value; }
    public Long getBeneficiaryId() { return beneficiaryId; }
    public void setBeneficiaryId(Long value) { this.beneficiaryId = value; }
    public String getServiceUnit() { return serviceUnit; }
    public void setServiceUnit(String value) { this.serviceUnit = value; }
    public String getSettlementRoute() { return settlementRoute; }
    public void setSettlementRoute(String value) { this.settlementRoute = value; }
    public String getSourceContractType() { return sourceContractType; }
    public void setSourceContractType(String value) { this.sourceContractType = value; }
    public Long getSourceContractId() { return sourceContractId; }
    public void setSourceContractId(Long value) { this.sourceContractId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
}
