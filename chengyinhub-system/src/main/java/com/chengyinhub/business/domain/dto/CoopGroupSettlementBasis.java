package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 带队场次口径下，某商家的已接待团结算基数。 */
public class CoopGroupSettlementBasis {
    private Integer verifiedHeads;
    private BigDecimal verifiedSales;

    public Integer getVerifiedHeads() { return verifiedHeads; }
    public void setVerifiedHeads(Integer verifiedHeads) { this.verifiedHeads = verifiedHeads; }

    public BigDecimal getVerifiedSales() { return verifiedSales; }
    public void setVerifiedSales(BigDecimal verifiedSales) { this.verifiedSales = verifiedSales; }
}
