package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/**
 * Explicit closed whitelist for MEMBER_TEMPLATE/schema 1 text-only content. Not a legacy template
 * deserializer, content reviewer, renderer or script runner. Unsupported fields/types fail closed,
 * including media, NPC, route, coupon/reward and permission graphs. No generic bean/property copy.
 */
public final class TextModuleDraftCodec {
    static final String SCHEMA = "w18-member-text-v1";
    // Technical storage/parser bound, not a license limit or product offering default.
    static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> TEXT = new HashSet<String>(Arrays.asList("title", "description",
            "ruleInstructions", "merchantGuide", "questionName", "questionAnswer", "hint1", "hint2", "answerReveal"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private TextModuleDraftCodec() { }

    private static ObjectNode parseSource(byte[] bytes, ModuleVersionSnapshot source) {
        if (source.getSourceKind() != ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE || source.getSchemaVersion() != 1)
            throw new IllegalArgumentException("unsupported module source/schema");
        verifyBytes(bytes, source.getContentHash());
        ObjectNode output = JSON.createObjectNode();
        Map<String, ModuleVersionSnapshot.BindingKind> placeholders = new TreeMap<String, ModuleVersionSnapshot.BindingKind>();
        Set<String> seen = new HashSet<String>();
        try (JsonParser p = JSON.getFactory().createParser(utf8(bytes))) {
            p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            expect(p.nextToken(), JsonToken.START_OBJECT);
            while (p.nextToken() != JsonToken.END_OBJECT) {
                expect(p.currentToken(), JsonToken.FIELD_NAME);
                String field = p.currentName(); seen.add(field);
                JsonToken value = p.nextToken();
                if ("schema".equals(field)) {
                    expect(value, JsonToken.VALUE_STRING);
                    if (!SCHEMA.equals(p.getText())) throw new IllegalArgumentException("unsupported content schema");
                    output.put("schema", SCHEMA);
                } else if ("bindings".equals(field)) {
                    expect(value, JsonToken.START_OBJECT);
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        expect(p.currentToken(), JsonToken.FIELD_NAME);
                        String name = validText(p.currentName());
                        expect(p.nextToken(), JsonToken.VALUE_STRING);
                        placeholders.put(name, ModuleVersionSnapshot.BindingKind.valueOf(p.getText()));
                    }
                } else if (TEXT.contains(field)) {
                    expect(value, JsonToken.VALUE_STRING);
                    output.put(field, validText(p.getText()));
                } else {
                    throw new IllegalArgumentException("unsupported content field: " + field);
                }
            }
            if (p.nextToken() != null || !seen.containsAll(Arrays.asList("schema", "title", "bindings"))
                    || output.path("title").asText().trim().isEmpty()) throw new IllegalArgumentException("incomplete content");
        } catch (IOException e) { throw new IllegalArgumentException("invalid reviewed content", e); }
        if (!placeholders.equals(source.getRequiredBindings())) throw new IllegalArgumentException("content placeholder mismatch");
        return output;
    }

    /** Schema/hash validation only, never content approval or license issuance. */
    public static void validateReviewedSource(byte[] bytes, ModuleVersionSnapshot source) {
        parseSource(bytes, source);
    }

    static String cloneContent(byte[] bytes, DraftInstallPlan plan) {
        return cloneContent(bytes, plan.getSource(), plan.getBindings());
    }
    private static String cloneContent(byte[] bytes, ModuleVersionSnapshot source,
            Map<String, ModuleInstallAuthority.BindingApproval> bindings) {
        ObjectNode output = parseSource(bytes, source);
        ObjectNode mapped = output.putObject("bindings");
        for (Map.Entry<String, ModuleInstallAuthority.BindingApproval> e : new TreeMap<String, ModuleInstallAuthority.BindingApproval>(bindings).entrySet()) {
            ModuleInstallAuthority.BindingApproval b = e.getValue();
            ObjectNode ref = mapped.putObject(e.getKey());
            ref.put("kind", b.getResource().getKind().name()); ref.put("resourceId", b.getResource().getResourceId());
            if (b.getMerchantId() != null) ref.put("merchantId", b.getMerchantId());
            ref.put("approvalReference", b.getAuthorityReference());
            // Approved resources are references only; this does not issue rewards or grant permissions.
        }
        output.put("validationMethod", 1);
        return output.toString();
    }

    static String frozenRights(DraftInstallPlan p, byte[] document) {
        return frozenRights(p.getGrant(), p.getSource(), p.getTarget().getRegion(), p.getRequest(), document);
    }
    private static String frozenRights(ModuleLicenseGrant g, ModuleVersionSnapshot s, String region,
                                       ModuleInstallRequest request, byte[] document) {
        LicenseOfferSnapshot o = g.getOffer(); LicenseTermsSnapshot t = o.getTerms();
        verifyBytes(document, t.getTermsDocumentHash());
        ObjectNode n = JSON.createObjectNode();
        // Paid purchased-version duration is explicit in v2. Unspecified legacy/FREE snapshots
        // retain their exact v1 bytes; null expiry alone must never be interpreted as this duration.
        n.put("schema", t.getUseDuration() == null ? "w18-frozen-install-rights-v1" : "w18-frozen-install-rights-v2");
        if (t.getUseDuration() != null) n.put("useDuration", t.getUseDuration().name());
        n.put("licenseId", g.getLicenseId()); n.put("evidenceKind", g.getEvidenceKind().name());
        n.put("evidenceReference", g.getAuthorityReference()); n.put("sourceEventId", g.getSourceEventId());
        n.put("purchasedVersionId", g.getPurchasedVersionId());
        n.put("validFrom", g.getValidFrom().toString());
        if (g.getValidUntil() == null) n.putNull("validUntil"); else n.put("validUntil", g.getValidUntil().toString());
        n.put("offerId", o.getOfferId()); n.put("offerVersion", o.getOfferVersion());
        n.put("acquisition", o.getAcquisition().name()); n.put("priceMinor", o.getPriceMinor()); n.put("currency", o.getCurrency());
        n.put("termsVersion", t.getTermsVersion()); n.put("termsHash", t.getTermsHash());
        n.put("termsDocumentHash", t.getTermsDocumentHash()); n.put("termsDocumentReference", t.getTermsDocumentReference());
        n.put("commercialUse", t.getCommercialUse().name()); n.put("adaptation", t.getAdaptation().name());
        n.put("translation", t.getTranslation().name()); n.put("updates", t.getUpdates().name());
        n.put("redistribution", t.getRedistribution().name());
        for (String allowedRegion : new TreeSet<String>(t.getAllowedRegions())) n.withArray("allowedRegions").add(allowedRegion);
        limit(n, "themeLimit", t.getThemeLimit()); limit(n, "merchantLimit", t.getMerchantLimit()); limit(n, "runLimit", t.getRunLimit());
        n.put("sourceKind", s.getSourceKind().name()); n.put("sourceId", s.getSourceId()); n.put("sourceRevision", s.getSourceRevision());
        n.put("sourceSchemaVersion", s.getSchemaVersion()); n.put("version", s.getVersion());
        n.put("targetRegion", region);
        n.put("commercialUseRequested", request.isCommercialUse());
        for (ModuleInstallRequest.Change c : request.getChanges()) n.withArray("requestedChanges").add(c.name());
        return n.toString();
    }

    /** NEW pure integrity check for an already committed, unchanged no-binding installation.
     * This does not resolve a buyer, authorize body disclosure, consume usage or issue a plan.
     * The caller must authenticate and lock the real current paid grant and committed source chain.
     * Exact serializer comparison avoids tree-parsing hostile stored component/rights JSON. */
    public static void verifyInstalledClone(ModuleLicenseGrant grant, ModuleVersionSnapshot source,
            ModuleInstallRequest request, String region, byte[] sourceBytes, byte[] termsBytes,
            String componentJson, String frozenRightsJson) {
        if (grant == null || source == null || request == null || componentJson == null || frozenRightsJson == null
                || componentJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES + 4096
                || frozenRightsJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !request.getChanges().isEmpty() || !request.getBindings().isEmpty() || !source.getRequiredBindings().isEmpty()
                || !request.getBuyer().equals(grant.getBuyer()) || !request.getLicenseId().equals(grant.getLicenseId())
                || !request.getModuleId().equals(source.getModuleId()) || !request.getModuleId().equals(grant.getOffer().getModuleId())
                || !request.getVersionId().equals(source.getVersionId()) || !request.getVersionId().equals(grant.getPurchasedVersionId())
                || !request.getExpectedContentHash().equals(source.getContentHash())
                || !source.getContentHash().equals(grant.getGrantedVersionHashes().get(source.getVersionId()))
                || !source.getCreator().equals(grant.getOffer().getSeller())
                || !request.getExpectedOfferVersion().equals(grant.getOffer().getOfferVersion())
                || !request.getExpectedTermsHash().equals(grant.getOffer().getTerms().getTermsHash())
                || !grant.getOffer().getTerms().getAllowedRegions().contains(region)
                || request.isCommercialUse() && grant.getOffer().getTerms().getCommercialUse() != LicenseTermsSnapshot.Permission.ALLOWED
                || !componentJson.equals(cloneContent(sourceBytes, source, Collections.<String, ModuleInstallAuthority.BindingApproval>emptyMap()))
                || !frozenRightsJson.equals(frozenRights(grant, source, region, request, termsBytes))) {
            throw new IllegalArgumentException("installed source integrity mismatch");
        }
    }
    private static void limit(ObjectNode n, String field, LicenseTermsSnapshot.Limit value) {
        ObjectNode limit = n.putObject(field); limit.put("unlimited", value.isUnlimited()); limit.put("maximum", value.getMaximum());
    }
    static void verifyBytes(byte[] bytes, String expectedHash) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("missing/oversized reviewed bytes");
        try {
            StringBuilder actual = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!actual.toString().equals(expectedHash)) throw new IllegalArgumentException("reviewed bytes hash mismatch");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("malformed UTF-8", e); }
    }
    private static String validText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("malformed UTF-16");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("malformed UTF-16");
        }
        return text;
    }
    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw new IllegalArgumentException("unsupported reviewed content shape");
    }
}
