package com.chengyinhub.business.marketplace.paid;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** NEW default-off owner projection. It does NOT prove an editable content_draft schema or grant
 * materialization/publication. A commit adapter must recheck CONTENT authority and its target CAS.
 * cms_topic.member_id is a publisher, not the ownership source: this bounded path additionally
 * requires the unique active CONTENT/MEMBER slot to name the actor. No CLUB/PLATFORM ID fallback. */
public final class PaidProfessionalTargetReader {
    public enum Policy { DISABLED, INDIVIDUAL_OWN_CONTENT_AND_PUBLISHER_V1 }
    private final PaidPersistenceBoundary persistence;private final PersistedPaidLicenseService licenses;
    private final PaidProfessionalTargetStore targets;private final Clock clock;private final Policy policy;
    public PaidProfessionalTargetReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){this(session,manager,clock,Policy.DISABLED);}
    public PaidProfessionalTargetReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy){
        persistence=new PaidPersistenceBoundary(session,manager);this.clock=Objects.requireNonNull(clock);this.policy=Objects.requireNonNull(policy);
        licenses=new PersistedPaidLicenseService(persistence,clock,PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1);targets=persistence.mapper(PaidProfessionalTargetStore.class);
    }
    public Map<String,Object> list(long actor,String licenseId,Long before){
        enabled(actor);require(before==null||before>0);return persistence.inTransaction(()->{
            require(licenses.captureOwnedInCurrentTransaction(actor,licenseId).grant.isActiveAt(clock.instant()));
            List<PaidProfessionalTargetStore.Row> rows=targets.list(actor,before);require(rows!=null&&rows.size()<=51);
            List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();Long previous=before;
            for(PaidProfessionalTargetStore.Row row:rows){validate(row,actor);require(previous==null||row.getId()<previous);previous=row.getId();if(result.size()<50)result.add(project(row));}
            boolean more=rows.size()>50;return map("schema","w18-owned-professional-topic-targets-v1","scope","INDIVIDUAL_CONTENT_OWNER_AND_PUBLISHER_METADATA_ONLY","licenseId",licenseId,"checkedAt",clock.instant().toString(),"items",Collections.unmodifiableList(result),"hasMore",more,"nextBeforeTopicId",more?rows.get(49).getId():null);
        });
    }
    public Map<String,Object> detail(long actor,String licenseId,long topicId){
        enabled(actor);require(topicId>0);return persistence.inTransaction(()->{
            require(licenses.captureOwnedInCurrentTransaction(actor,licenseId).grant.isActiveAt(clock.instant()));
            List<PaidProfessionalTargetStore.Row> rows=targets.capture(actor,topicId);require(rows!=null&&rows.size()==1);PaidProfessionalTargetStore.Row row=rows.get(0);validate(row,actor);require(row.getId()==topicId);return project(row);
        });
    }
    private void enabled(long actor){require(actor>0&&policy==Policy.INDIVIDUAL_OWN_CONTENT_AND_PUBLISHER_V1);persistence.verifyConfiguration();}
    private static void validate(PaidProfessionalTargetStore.Row row,long actor){
        require(row!=null&&row.getId()!=null&&row.getId()>0&&Long.valueOf(actor).equals(row.getPublisherMemberId())&&Integer.valueOf(0).equals(row.getDelFlag())
                &&"CONTENT".equals(row.getRoleSlot())&&row.getRoleId()!=null&&row.getRoleId()>0&&row.getId().equals(row.getRoleTopicId())&&"MEMBER".equals(row.getEntityType())&&Long.valueOf(actor).equals(row.getEntityId())&&Integer.valueOf(1).equals(row.getRoleStatus())
                &&row.getName()!=null&&row.getName().length()<=256&&row.getName().getBytes(StandardCharsets.UTF_8).length<=1024&&(row.getConfigVersion()==null||row.getConfigVersion()>=0));
    }
    private static Map<String,Object> project(PaidProfessionalTargetStore.Row row){
        Integer product=row.getProductType();String mode= Integer.valueOf(1).equals(product)?"CITY_ORIENTATION":Integer.valueOf(2).equals(product)?"FREE_EXPLORATION":"UNRESOLVED";
        return map("topicId",row.getId(),"name",row.getName(),"productType",product,"mode",mode,"routeConfigVersion",row.getConfigVersion(),
                "ownerModeFingerprint",fingerprint(row),"fingerprintProfile","W18_CONTENT_OWNER_MODE_V1","materializationStatus","PROTECTED_TEMPLATE_ADAPTER_REQUIRED");
    }
    /** Deliberately named owner/mode fingerprint: config_version is only a route authoring CAS.
     * Do not claim this replaces the full owner content_draft revision/hash or a reviewed release. */
    static String fingerprint(PaidProfessionalTargetStore.Row row){try{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        for(Object value:Arrays.asList("W18_CONTENT_OWNER_MODE_V1",row.getId(),row.getPublisherMemberId(),row.getName(),row.getProductType(),row.getConfigVersion(),row.getDelFlag(),row.getRoleId(),row.getRoleTopicId(),row.getRoleSlot(),row.getEntityType(),row.getEntityId(),row.getRoleStatus(),row.getDeclaredBy(),row.getCreatedAt()==null?null:row.getCreatedAt().toInstant().toString())){
            if(value==null){out.writeInt(-1);continue;}byte[] part=String.valueOf(value).getBytes(StandardCharsets.UTF_8);out.writeInt(part.length);out.write(part);
        }return PaidPurchaseSnapshot.sha(bytes.toByteArray());
    }catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("PAID_PROFESSIONAL_TARGET_UNAVAILABLE");}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return Collections.unmodifiableMap(result);}
}
