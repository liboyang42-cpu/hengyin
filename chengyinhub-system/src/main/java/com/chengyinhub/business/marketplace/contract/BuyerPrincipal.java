package com.chengyinhub.business.marketplace.contract;

import java.util.Objects;

/** License holder, not the logged-in operator, content author or player actor. */
public final class BuyerPrincipal {
    public enum Kind { INDIVIDUAL, ORGANIZATION, MERCHANT }
    private final Kind kind;
    private final long entityId;

    public BuyerPrincipal(Kind kind, long entityId) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.entityId = ContractChecks.positive(entityId, "entityId");
    }

    public Kind getKind() { return kind; }
    public long getEntityId() { return entityId; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BuyerPrincipal)) return false;
        BuyerPrincipal that = (BuyerPrincipal) other;
        return kind == that.kind && entityId == that.entityId;
    }

    @Override
    public int hashCode() { return Objects.hash(kind, entityId); }
}
