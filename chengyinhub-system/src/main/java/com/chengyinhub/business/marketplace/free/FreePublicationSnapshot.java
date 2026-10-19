package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.contract.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.security.MessageDigest;

/** Frozen server registry format. Integrity validation is NOT review approval or authority to ingest. */
public final class FreePublicationSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ModuleVersionSnapshot version;
    private final LicenseOfferSnapshot offer;
    private final byte[] content, terms;
    private final java.time.Instant validUntil;

    public FreePublicationSnapshot(ModuleVersionSnapshot version, LicenseOfferSnapshot offer, byte[] content, byte[] terms, java.time.Instant validUntil) {
        this.validUntil = Objects.requireNonNull(validUntil);
        if(validUntil.getNano()%1000!=0) throw new IllegalArgumentException("microsecond validity required");
        this.version = Objects.requireNonNull(version); this.offer = Objects.requireNonNull(offer);
        this.content = Objects.requireNonNull(content).clone(); this.terms = Objects.requireNonNull(terms).clone();
        if (content.length == 0 || content.length > 262144 || terms.length == 0 || terms.length > 65535
                || !sha(content).equals(version.getContentHash()) || !sha(terms).equals(offer.getTerms().getTermsDocumentHash())
                || !version.getCreator().equals(offer.getSeller()) || !version.getModuleId().equals(offer.getModuleId())
                || version.getCreator().getKind() != BuyerPrincipal.Kind.INDIVIDUAL
                || version.getSourceKind() != ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE || version.getSchemaVersion() != 1
                || offer.getAcquisition() != LicenseOfferSnapshot.Acquisition.FREE || offer.getPriceMinor() != 0
                || !offer.getBuyerKinds().equals(Collections.singleton(BuyerPrincipal.Kind.INDIVIDUAL))
                || offer.getTerms().getUpdates() != LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION)
            throw new IllegalArgumentException("unsupported free individual text publication");
        com.chengyinhub.business.marketplace.reservation.TextModuleDraftCodec.validateReviewedSource(this.content,version);
    }
    public java.time.Instant getValidUntil() { return validUntil; }
    public ModuleVersionSnapshot getVersion() { return version; }
    public LicenseOfferSnapshot getOffer() { return offer; }
    public byte[] getContent() { return content.clone(); }
    public byte[] getTerms() { return terms.clone(); }
    public String ownershipKey() {
        try {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            for(String field:Arrays.asList("w18-free-offer-identity-v1",offer.getSeller().getKind().name(),
                    Long.toString(offer.getSeller().getEntityId()),offer.getModuleId(),offer.getOfferId(),offer.getOfferVersion())) {
                byte[] encoded=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);
            }
            out.flush();return sha(bytes.toByteArray());
        } catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    public String encode() {
        ObjectNode root = JSON.createObjectNode(); root.put("format", "w18-free-publication-v1"); root.put("validUntil",validUntil.toString());
        root.set("version", JSON.valueToTree(version)); root.set("offer", JSON.valueToTree(offer));
        return root.toString();
    }
    public static FreePublicationSnapshot decode(String encoded, byte[] content, byte[] terms) {
        try {
            if (encoded == null || encoded.length() > 65536) throw new IllegalArgumentException("invalid registry document");
            JsonNode root = JSON.readTree(encoded);
            if (!"w18-free-publication-v1".equals(text(root,"format"))) throw new IllegalArgumentException("registry format");
            JsonNode v=root.required("version"), o=root.required("offer"), t=o.required("terms");
            LicenseTermsSnapshot policy = new LicenseTermsSnapshot(text(t,"termsVersion"),terms,text(t,"termsDocumentHash"),
                    text(t,"termsDocumentReference"),LicenseTermsSnapshot.Permission.valueOf(text(t,"commercialUse")),
                    LicenseTermsSnapshot.Adaptation.valueOf(text(t,"adaptation")),LicenseTermsSnapshot.Permission.valueOf(text(t,"translation")),
                    LicenseTermsSnapshot.Updates.valueOf(text(t,"updates")),LicenseTermsSnapshot.Redistribution.valueOf(text(t,"redistribution")),
                    strings(t.required("allowedRegions")),limit(t.required("themeLimit")),limit(t.required("merchantLimit")),limit(t.required("runLimit")));
            if (!policy.getTermsHash().equals(text(t,"termsHash"))) throw new IllegalArgumentException("typed rights changed");
            Set<BuyerPrincipal.Kind> buyers=new LinkedHashSet<BuyerPrincipal.Kind>();
            for(String s:strings(o.required("buyerKinds"))) buyers.add(BuyerPrincipal.Kind.valueOf(s));
            LicenseOfferSnapshot offer=new LicenseOfferSnapshot(text(o,"offerId"),text(o,"offerVersion"),text(o,"moduleId"),
                    principal(o.required("seller")),buyers,LicenseOfferSnapshot.Acquisition.valueOf(text(o,"acquisition")),number(o,"priceMinor"),text(o,"currency"),policy);
            Map<String,ModuleVersionSnapshot.BindingKind> bindings=new LinkedHashMap<String,ModuleVersionSnapshot.BindingKind>();
            JsonNode b=v.required("requiredBindings"); if(!b.isObject()) throw new IllegalArgumentException("bindings");
            Iterator<String> keys=b.fieldNames(); while(keys.hasNext()){String k=keys.next();bindings.put(k,ModuleVersionSnapshot.BindingKind.valueOf(text(b,k)));}
            long schema=number(v,"schemaVersion"); if(schema!=1) throw new IllegalArgumentException("schema");
            ModuleVersionSnapshot version=new ModuleVersionSnapshot(principal(v.required("creator")),text(v,"moduleId"),text(v,"versionId"),
                    text(v,"version"),(int)schema,ModuleVersionSnapshot.SourceKind.valueOf(text(v,"sourceKind")),number(v,"sourceId"),
                    text(v,"sourceRevision"),content,text(v,"contentHash"),text(v,"approvalReference"),bindings);
            FreePublicationSnapshot result=new FreePublicationSnapshot(version,offer,content,terms,java.time.Instant.parse(text(root,"validUntil")));
            // Reject extra/missing/coerced fields as well as semantic mismatches, without accepting caller claims.
            if (!JSON.readTree(result.encode()).equals(root)) throw new IllegalArgumentException("noncanonical registry fields");
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("invalid frozen free publication",e); }
    }
    private static BuyerPrincipal principal(JsonNode n){return new BuyerPrincipal(BuyerPrincipal.Kind.valueOf(text(n,"kind")),number(n,"entityId"));}
    private static LicenseTermsSnapshot.Limit limit(JsonNode n){
        JsonNode u=n.required("unlimited"); if(!u.isBoolean())throw new IllegalArgumentException("explicit limit required");
        long maximum=number(n,"maximum"); if(u.booleanValue()&&maximum!=0)throw new IllegalArgumentException("unlimited maximum");
        return u.booleanValue()?LicenseTermsSnapshot.Limit.unlimited():LicenseTermsSnapshot.Limit.atMost(maximum);
    }
    private static Set<String> strings(JsonNode n){
        if(!n.isArray())throw new IllegalArgumentException("array required");Set<String>s=new LinkedHashSet<String>();
        for(JsonNode item:n){if(!item.isTextual()||!s.add(item.textValue()))throw new IllegalArgumentException("unique text required");}return s;
    }
    private static String text(JsonNode n,String key){JsonNode v=n.required(key);if(!v.isTextual())throw new IllegalArgumentException(key);return v.textValue();}
    private static long number(JsonNode n,String key){JsonNode v=n.required(key);if(!v.isIntegralNumber()||!v.canConvertToLong())throw new IllegalArgumentException(key);return v.longValue();}
    public static String sha(byte[] bytes){
        try {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
