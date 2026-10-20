package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Length-prefixed, sorted, exact UTF-8 encoding; database collation cannot merge business keys. */
final class ReservationIdentity {
    private ReservationIdentity() { }
    static String key(BuyerPrincipal buyer, String requestId) {
        return digest("request-v1", buyer.getKind().name(), Long.toString(buyer.getEntityId()), requestId);
    }
    static String license(String licenseId) { return digest("license-v1", licenseId); }
    static String placeholder(String placeholder) { return digest("placeholder-v1", placeholder); }
    static String payload(ModuleInstallRequest r, Instant requestedUntil) {
        List<String> parts = new ArrayList<String>(Arrays.asList("install-intent-v1", r.getBuyer().getKind().name(),
                Long.toString(r.getBuyer().getEntityId()), r.getRequestId(), r.getLicenseId(), r.getModuleId(),
                r.getVersionId(), r.getExpectedContentHash(), r.getExpectedOfferVersion(), r.getExpectedTermsHash(),
                Long.toString(r.getDraftId()), Long.toString(r.getExpectedDraftRevision()),
                Boolean.toString(r.isCommercialUse()), requestedUntil.toString()));
        TreeSet<String> changes = new TreeSet<String>();
        for (ModuleInstallRequest.Change c : r.getChanges()) changes.add(c.name());
        parts.add(Integer.toString(changes.size())); parts.addAll(changes);
        parts.add(Integer.toString(r.getBindings().size()));
        for (Map.Entry<String, ModuleInstallRequest.ResourceRef> e : new TreeMap<String, ModuleInstallRequest.ResourceRef>(r.getBindings()).entrySet()) {
            parts.add(e.getKey()); parts.add(e.getValue().getKind().name()); parts.add(Long.toString(e.getValue().getResourceId()));
        }
        return digest(parts.toArray(new String[0]));
    }
    static String authority(DraftInstallPlan plan) {
        return authority(plan.getGrant(), plan.getSource(), plan.getTarget().getRegion(), plan.getBindings());
    }
    static String authority(ModuleLicenseGrant g, ModuleVersionSnapshot s, String region,
                            Map<String, ModuleInstallAuthority.BindingApproval> bindings) {
        LicenseOfferSnapshot o = g.getOffer();
        List<String> parts = new ArrayList<String>(Arrays.asList("reservation-authority-v1", g.getLicenseId(),
                g.getBuyer().getKind().name(), Long.toString(g.getBuyer().getEntityId()), g.getPurchasedVersionId(),
                g.getEvidenceKind().name(), g.getAuthorityReference(), g.getSourceEventId(), g.getValidFrom().toString(),
                g.getValidUntil() == null ? "unlimited" : g.getValidUntil().toString(), o.getOfferId(), o.getOfferVersion(),
                o.getSeller().getKind().name(), Long.toString(o.getSeller().getEntityId()), o.getAcquisition().name(),
                Long.toString(o.getPriceMinor()), o.getCurrency(), o.getTerms().getTermsHash(), s.getModuleId(),
                s.getVersionId(), s.getContentHash(), s.getApprovalReference(), s.getSourceKind().name(),
                Long.toString(s.getSourceId()), s.getSourceRevision(), Integer.toString(s.getSchemaVersion()),
                region));
        for (Map.Entry<String, ModuleInstallAuthority.BindingApproval> e : new TreeMap<String, ModuleInstallAuthority.BindingApproval>(bindings).entrySet()) {
            ModuleInstallAuthority.BindingApproval b = e.getValue();
            parts.add(e.getKey()); parts.add(b.getResource().getKind().name()); parts.add(Long.toString(b.getResource().getResourceId()));
            parts.add(b.getMerchantId() == null ? "none" : b.getMerchantId().toString());
            parts.add(b.getAuthorityReference()); parts.add(b.getRegion()); parts.add(b.getValidUntil().toString());
        }
        return digest(parts.toArray(new String[0]));
    }
    private static String digest(String... parts) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String part : parts) {
                validText(part);
                byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void validText(String value) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) throw new IllegalArgumentException("invalid identity");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("malformed UTF-16");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("malformed UTF-16");
        }
    }
}
