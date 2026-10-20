package com.chengyinhub.business.marketplace.paid;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** NEW owner-confirmed intent, not an existing editor payload or a paid entitlement. The actor is
 * supplied by authentication. A current generic draft CAS is distinct from the installation's
 * original revision and the real topic's owner/mode fingerprint. No TOPIC/ACTIVITY → mode guess. */
public final class PaidProfessionalMaterializationCommand {
    public static final String SCHEMA="w18-paid-professional-text-materialization-command-v1";
    public enum Mode { CITY_ORIENTATION, FREE_EXPLORATION }
    public final String requestId,installationRequestId,licenseId,moduleId,purchasedVersionId,contentHash,termsHash,componentHash;
    public final long ownedDraftId,currentDraftRevision,targetTopicId;
    public final String currentDraftPayloadHash,targetOwnerModeFingerprint;
    public final Mode confirmedMode;
    public PaidProfessionalMaterializationCommand(String requestId,String installationRequestId,long ownedDraftId,
            String licenseId,String moduleId,String purchasedVersionId,String contentHash,String termsHash,String componentHash,
            long currentDraftRevision,String currentDraftPayloadHash,long targetTopicId,String targetOwnerModeFingerprint,Mode confirmedMode){
        require(uuid(requestId)&&uuid(installationRequestId)&&ownedDraftId>0&&currentDraftRevision>0&&targetTopicId>0);
        require(licenseId!=null&&licenseId.matches("w18-paid-[1-9][0-9]{0,18}")&&text(moduleId,128)&&text(purchasedVersionId,128));
        for(String hash:Arrays.asList(contentHash,termsHash,componentHash,currentDraftPayloadHash,targetOwnerModeFingerprint))require(hash!=null&&hash.matches("[0-9a-f]{64}"));
        this.requestId=requestId;this.installationRequestId=installationRequestId;this.ownedDraftId=ownedDraftId;this.licenseId=licenseId;
        this.moduleId=moduleId;this.purchasedVersionId=purchasedVersionId;this.contentHash=contentHash;this.termsHash=termsHash;this.componentHash=componentHash;
        this.currentDraftRevision=currentDraftRevision;this.currentDraftPayloadHash=currentDraftPayloadHash;this.targetTopicId=targetTopicId;this.targetOwnerModeFingerprint=targetOwnerModeFingerprint;
        this.confirmedMode=Objects.requireNonNull(confirmedMode,"explicit mode required");
    }
    String digest(){return digest(SCHEMA,requestId,installationRequestId,Long.toString(ownedDraftId),licenseId,moduleId,purchasedVersionId,contentHash,termsHash,componentHash,Long.toString(currentDraftRevision),currentDraftPayloadHash,Long.toString(targetTopicId),targetOwnerModeFingerprint,confirmedMode.name());}
    String ownerRequestKey(long actor){return ownerRequestKey(actor,requestId);}
    static String ownerRequestKey(long actor,String requestId){require(actor>0&&uuid(requestId));return digest("w18-paid-materialization-request-v1",Long.toString(actor),requestId);}
    String consumedThemeBindingKey(long originalDraftId){require(originalDraftId>0);return digest("w18-paid-consumed-theme-professional-binding-v1",licenseId,Long.toString(originalDraftId));}
    void requireSource(PaidInstalledTextReader.Captured captured){
        require(captured!=null&&ownedDraftId==captured.ownedDraftId&&installationRequestId.equals(captured.intent.requestId)
                &&licenseId.equals(captured.grant.getLicenseId())&&moduleId.equals(captured.source.getModuleId())
                &&purchasedVersionId.equals(captured.grant.getPurchasedVersionId())&&contentHash.equals(captured.source.getContentHash())
                &&termsHash.equals(captured.grant.getOffer().getTerms().getTermsHash())&&componentHash.equals(captured.componentHash));
    }
    private static String digest(String... values){try{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        for(String value:values){byte[] part=value.getBytes(StandardCharsets.UTF_8);out.writeInt(part.length);out.write(part);}
        return PaidPurchaseSnapshot.sha(bytes.toByteArray());
    }catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static boolean text(String value,int maximum){
        if(value==null||value.isEmpty()||!value.equals(value.trim())||value.getBytes(StandardCharsets.UTF_8).length>maximum)return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isISOControl(c))return false;
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}
        return true;
    }
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("PAID_MATERIALIZATION_INTENT_MISMATCH");}
}
