package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Opaque transaction-local preparation result. Do not serialize or persist as a financial intent. */
public final class FreeExploreRefundContext {
    private final Long sourceRegistrationId;
    private final BigDecimal cashAmount;
    private final List<Long> registrations;
    private final Set<Long> replaceableRefundIds;
    public FreeExploreRefundContext(Long sourceRegistrationId, BigDecimal cashAmount,
                                    List<Long> registrations, Set<Long> replaceableRefundIds) {
        this.sourceRegistrationId = sourceRegistrationId;
        this.cashAmount = cashAmount;
        this.registrations = List.copyOf(registrations);
        this.replaceableRefundIds = Set.copyOf(replaceableRefundIds);
    }
    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public List<Long> getRegistrations() { return registrations; }
    public Set<Long> getReplaceableRefundIds() { return replaceableRefundIds; }
}
