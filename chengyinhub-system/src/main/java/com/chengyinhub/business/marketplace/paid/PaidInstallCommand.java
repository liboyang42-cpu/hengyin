package com.chengyinhub.business.marketplace.paid;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** NEW untrusted, explicit confirmation. Metadata and this value never create purchase authority.
 * Mode remains unresolved: TOPIC/ACTIVITY is a storage business type, not a gameplay mode. */
public final class PaidInstallCommand {
    public static final String SCHEMA="w18-paid-private-text-install-command-v1";
    public final String requestId,licenseId,moduleId,versionId,contentHash,termsHash,businessType,targetPayloadHash,planningRegion;
    public final long targetDraftId,targetRevision;public final boolean commercialUse;
    public PaidInstallCommand(String requestId,String licenseId,String moduleId,String versionId,String contentHash,String termsHash,
            long targetDraftId,long targetRevision,String businessType,String targetPayloadHash,String planningRegion,boolean commercialUse){
        require(requestId!=null&&requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        require(licenseId!=null&&licenseId.matches("w18-paid-[1-9][0-9]{0,18}"));
        require(text(moduleId,128)&&text(versionId,128)&&hash(contentHash)&&hash(termsHash)&&hash(targetPayloadHash));
        require(targetDraftId>0&&targetRevision>0&&Arrays.asList("TOPIC","ACTIVITY").contains(businessType)&&text(planningRegion,128));
        this.requestId=requestId;this.licenseId=licenseId;this.moduleId=moduleId;this.versionId=versionId;this.contentHash=contentHash;this.termsHash=termsHash;
        this.targetDraftId=targetDraftId;this.targetRevision=targetRevision;this.businessType=businessType;this.targetPayloadHash=targetPayloadHash;
        this.planningRegion=planningRegion;this.commercialUse=commercialUse;
    }
    String digest(){return digest(SCHEMA,requestId,licenseId,moduleId,versionId,contentHash,termsHash,Long.toString(targetDraftId),Long.toString(targetRevision),businessType,targetPayloadHash,planningRegion,Boolean.toString(commercialUse));}
    static String key(long actor,String request){require(actor>0);return digest("w18-paid-command-owner-v1",Long.toString(actor),request);}
    String coreRequestId(){return "w18-paid-command:"+requestId;}
    private static String digest(String... values){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);for(String s:values){byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}return PaidPurchaseSnapshot.sha(bytes.toByteArray());}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static boolean hash(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static boolean text(String s,int max){if(s==null||s.isEmpty()||!s.equals(s.trim())||s.getBytes(StandardCharsets.UTF_8).length>max)return false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isISOControl(c))return false;if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("INVALID_INSTALL_COMMAND");}
    @Override public String toString(){return "PaidInstallCommand[redacted]";}
}
