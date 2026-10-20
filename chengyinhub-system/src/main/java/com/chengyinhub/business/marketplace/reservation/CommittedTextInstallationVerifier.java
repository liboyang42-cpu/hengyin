package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.store.ModuleReservationRow;
import java.time.Instant;
import java.util.Collections;

/** Pure exact comparison with the existing installation identities. Not a grant resolver, body
 * disclosure authority or installer. Caller supplies a locked committed row and verified grant. */
public final class CommittedTextInstallationVerifier {
    private CommittedTextInstallationVerifier() { }
    public static void requireMatches(ModuleReservationRow row, ModuleInstallRequest request, Instant deadline,
            ModuleLicenseGrant grant, ModuleVersionSnapshot source, String region) {
        if(row==null || request==null || deadline==null || grant==null || source==null
                || !request.getBindings().isEmpty() || !request.getChanges().isEmpty() || !source.getRequiredBindings().isEmpty()
                || !"CONSUMED".equals(row.getState()) || row.getReservedAt()==null || row.getExpiresAt()==null || row.getTerminalAt()==null
                || row.getTerminalAt().before(row.getReservedAt()) || !row.getTerminalAt().before(row.getExpiresAt())
                || !deadline.equals(row.getExpiresAt().toInstant())
                || !ReservationIdentity.key(request.getBuyer(),request.getRequestId()).equals(row.getReservationKey())
                || !ReservationIdentity.license(request.getLicenseId()).equals(row.getLicenseKey())
                || !ReservationIdentity.payload(request,deadline).equals(row.getPayloadHash())
                || !ReservationIdentity.authority(grant,source,region,Collections.<String,ModuleInstallAuthority.BindingApproval>emptyMap()).equals(row.getAuthorityHash())
                || !request.getBuyer().getKind().name().equals(row.getBuyerKind()) || request.getBuyer().getEntityId()!=row.getBuyerId()
                || row.getActorId()!=request.getBuyer().getEntityId() || !request.getRequestId().equals(row.getRequestId())
                || !request.getLicenseId().equals(row.getLicenseId()) || !request.getModuleId().equals(row.getModuleId())
                || !request.getVersionId().equals(row.getVersionId()) || !request.getExpectedContentHash().equals(row.getContentHash())
                || !request.getExpectedOfferVersion().equals(row.getOfferVersion()) || !request.getExpectedTermsHash().equals(row.getTermsHash())
                || request.getDraftId()!=row.getDraftId() || request.getExpectedDraftRevision()!=row.getDraftRevision()
                || !grant.getOffer().getSeller().getKind().name().equals(row.getSellerKind()) || grant.getOffer().getSeller().getEntityId()!=row.getSellerId()
                || !grant.getSourceEventId().equals(row.getSourceEventId()) || !grant.getAuthorityReference().equals(row.getGrantEvidenceReference())
                || !source.getApprovalReference().equals(row.getApprovalReference())) {
            throw new IllegalArgumentException("committed installation integrity mismatch");
        }
    }
}
