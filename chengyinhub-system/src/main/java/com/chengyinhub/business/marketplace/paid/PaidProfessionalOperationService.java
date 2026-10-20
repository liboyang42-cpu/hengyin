package com.chengyinhub.business.marketplace.paid;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** NEW owner operation coordinator. Preparation is metadata, not authority. A create holds its
 * operation row through the SAME physical CMS/provenance transaction. Retained terminal rows
 * prevent delayed requests from reviving a cancelled/rejected operation. No purchase refund,
 * license/quota reset, CMS undo, role grant, publication or run is performed here. */
public final class PaidProfessionalOperationService implements PaidProfessionalOperationHandling {
    private final PaidProfessionalMaterializationService kernel;
    private final PaidPersistenceBoundary persistence;
    private final PaidProfessionalOperationStore store;
    private final com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore owners;
    private final Clock clock;
    public PaidProfessionalOperationService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){
        this(session,manager,clock,PaidProfessionalMaterializationService.Policy.DISABLED);
    }
    public PaidProfessionalOperationService(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,PaidProfessionalMaterializationService.Policy policy){
        this(new PaidProfessionalMaterializationService(session,manager,clock,policy),clock,policy);
    }
    PaidProfessionalOperationService(PaidProfessionalMaterializationService kernel,Clock clock,PaidProfessionalMaterializationService.Policy policy){
        this.kernel=Objects.requireNonNull(kernel);this.clock=Objects.requireNonNull(clock);Objects.requireNonNull(policy);
        persistence=kernel.operationBoundary();store=persistence.mapper(PaidProfessionalOperationStore.class);owners=persistence.mapper(com.chengyinhub.business.marketplace.paid.store.PaidLicenseStore.class);
    }
    public Map<String,Object> submit(long actor,PaidProfessionalMaterializationCommand command){
        execute(actor,command);return status(actor,command.requestId);
    }
    public Map<String,Object> cancel(long actor,PaidProfessionalMaterializationCommand command){
        prepare(actor,command,true);return status(actor,command.requestId);
    }
    Map<String,Object> legacyMaterialize(long actor,PaidProfessionalMaterializationCommand command){
        Execution result=execute(actor,command);
        if(!"CREATED".equals(result.state)&&result.rejected!=null)throw result.rejected;
        require("CREATED".equals(result.state));if(result.receipt!=null)return result.receipt;
        return persistence.inTransaction(()->{
            PaidProfessionalOperationRow row=locked(actor,command);require("CREATED".equals(row.getState()));
            // Compatibility receipt may still require current source/target checks. This permit
            // can only reuse an existing matching provenance row; it cannot create a missing CMS.
            return kernel.createInCurrentTransaction(actor,command,new Permit(persistence,actor,command,false));
        });
    }
    private Execution execute(long actor,PaidProfessionalMaterializationCommand command){
        PaidProfessionalOperationRow prepared=prepare(actor,command,false);
        if(!"PREPARED".equals(prepared.getState()))return new Execution(prepared.getState(),null);
        try{
            return persistence.inTransaction(()->{
                PaidProfessionalOperationRow row=locked(actor,command);
                if(!"PREPARED".equals(row.getState())){require(owners.activeBuyer(actor)!=null);return new Execution(row.getState(),null);}
                Map<String,Object> receipt=kernel.createInCurrentTransaction(actor,command,new Permit(persistence,actor,command,true));
                finish(actor,command,"CREATED",null);
                return new Execution("CREATED",receipt);
            });
        }catch(IllegalArgumentException rejected){
            // The failed transaction has completed rollback before this transaction begins.
            // A concurrent retry/cancel must hold the same row. Never replace its terminal result.
            return persistence.inTransaction(()->{
                PaidProfessionalOperationRow row=locked(actor,command);require(owners.activeBuyer(actor)!=null);
                if("PREPARED".equals(row.getState())){
                    Map<String,Object> history=kernel.historyInCurrentTransaction(actor,command.requestId,command);
                    finish(actor,command,created(history)?"CREATED":"REJECTED",created(history)?null:rejectionCode(rejected));
                    row=locked(actor,command);
                }
                return new Execution(row.getState(),null,rejected);
            });
        }
    }
    private PaidProfessionalOperationRow prepare(long actor,PaidProfessionalMaterializationCommand command,boolean cancel){
        kernel.requireOperationEnabled(actor);require(command!=null);
        return persistence.inTransaction(()->{
            byte[] bytes=PaidProfessionalOperationCodec.encode(command);
            PaidProfessionalOperationRow initial=new PaidProfessionalOperationRow();initial.setOperationKey(command.ownerRequestKey(actor));initial.setOwnerMemberId(actor);
            initial.setRequestId(command.requestId);initial.setCommandHash(command.digest());initial.setCommandData(bytes);initial.setState("PREPARED");initial.setCreatedAt(at());initial.setUpdatedAt(initial.getCreatedAt());
            int changed=store.insertIfAbsent(initial);require(changed>=0&&changed<=2);PaidProfessionalOperationRow row=locked(actor,command);
            require(owners.activeBuyer(actor)!=null);
            Map<String,Object> history=kernel.historyInCurrentTransaction(actor,command.requestId,command);
            if("PREPARED".equals(row.getState())){
                // A compatible pre-registry creation is historical evidence, never a fresh grant.
                if(created(history))finish(actor,command,"CREATED",null);
                else if(cancel)finish(actor,command,"CANCELLED",null);
                row=locked(actor,command);
            }else require("CREATED".equals(row.getState())==created(history));
            return row;
        });
    }
    public Map<String,Object> status(long actor,String requestId){
        kernel.requireOperationEnabled(actor);String key=PaidProfessionalMaterializationCommand.ownerRequestKey(actor,requestId);
        return persistence.inTransaction(()->{
            PaidProfessionalOperationRow row=store.lock(actor,key);require(owners.activeBuyer(actor)!=null);
            Map<String,Object> output=new LinkedHashMap<String,Object>();output.put("schema","w18-paid-professional-operation-v1");
            output.put("scope","OWNER_OPERATION_METADATA_ONLY");output.put("requestId",requestId);
            if(row==null){output.put("state","NOT_FOUND");output.put("safeToReplace",false);return Collections.unmodifiableMap(output);}
            PaidProfessionalMaterializationCommand command=verify(actor,key,row);Map<String,Object> history=kernel.historyInCurrentTransaction(actor,requestId,command);
            if("CREATED".equals(row.getState()))require(created(history));
            if("REJECTED".equals(row.getState())||"CANCELLED".equals(row.getState()))require(!created(history));
            output.put("state",row.getState());output.put("safeToReplace","REJECTED".equals(row.getState())||"CANCELLED".equals(row.getState()));
            output.put("commandHash",row.getCommandHash());output.put("createdAt",row.getCreatedAt().toInstant().toString());output.put("updatedAt",row.getUpdatedAt().toInstant().toString());
            output.put("rejectionCode",row.getRejectionCode());output.put("creation","CREATED".equals(row.getState())?history:null);
            return Collections.unmodifiableMap(output);
        });
    }
    /** Owner operation discovery survives local recovery-store loss. Each page scans at most50
     * immutable descriptors. Filtering by license happens only after full bounded verification;
     * an empty filtered page with hasMore=true is NOT absence. This is not an entitlement index. */
    public Map<String,Object> history(long actor,String licenseId,String before){
        kernel.requireOperationEnabled(actor);require(licenseId!=null&&licenseId.matches("w18-paid-[1-9][0-9]{0,18}")
                &&(before==null||before.matches("[0-9a-f]{64}")));
        return persistence.inTransaction(()->{
            require(owners.activeBuyer(actor)!=null);List<PaidProfessionalOperationRow> rows=store.history(actor,before);require(rows!=null&&rows.size()<=51);
            List<Map<String,Object>> items=new ArrayList<Map<String,Object>>();String previous=before;int count=0;String next=null;
            for(PaidProfessionalOperationRow row:rows){
                require(row!=null&&row.getOperationKey()!=null&&row.getOperationKey().matches("[0-9a-f]{64}")&&(previous==null||row.getOperationKey().compareTo(previous)<0));
                PaidProfessionalMaterializationCommand command=verify(actor,row.getOperationKey(),row);previous=row.getOperationKey();
                if(count++>=50)break;next=row.getOperationKey();
                if(!licenseId.equals(command.licenseId))continue;
                // Discovery returns immutable intent + reference only. The separately fenced status
                // call determines a current operation terminal; no page scan is permission to reset.
                Map<String,Object> item=new LinkedHashMap<String,Object>();item.put("operationKey",row.getOperationKey());item.put("commandHash",row.getCommandHash());
                item.put("createdAt",row.getCreatedAt().toInstant().toString());item.put("command",commandMap(command));items.add(Collections.unmodifiableMap(item));
            }
            Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("schema","w18-paid-professional-operation-history-v1");result.put("scope","OWNER_OPERATION_INTENT_REFERENCES_ONLY");
            result.put("licenseId",licenseId);result.put("items",Collections.unmodifiableList(items));result.put("scannedCount",Math.min(rows.size(),50));
            result.put("hasMore",rows.size()>50);result.put("nextBeforeOperationKey",rows.size()>50?next:null);return Collections.unmodifiableMap(result);
        });
    }
    private static Map<String,Object> commandMap(PaidProfessionalMaterializationCommand c){
        Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("schema",PaidProfessionalMaterializationCommand.SCHEMA);m.put("requestId",c.requestId);m.put("installationRequestId",c.installationRequestId);
        m.put("ownedDraftId",c.ownedDraftId);m.put("licenseId",c.licenseId);m.put("moduleId",c.moduleId);m.put("purchasedVersionId",c.purchasedVersionId);m.put("contentHash",c.contentHash);m.put("termsHash",c.termsHash);m.put("componentHash",c.componentHash);
        m.put("currentDraftRevision",c.currentDraftRevision);m.put("currentDraftPayloadHash",c.currentDraftPayloadHash);m.put("targetTopicId",c.targetTopicId);m.put("targetOwnerModeFingerprint",c.targetOwnerModeFingerprint);m.put("confirmedMode",c.confirmedMode.name());return Collections.unmodifiableMap(m);
    }
    /** Serialize the actor/request operation BEFORE taking the buyer lock. Fresh creation then
     * follows topic → request/license source → OMS → buyer, matching the protected body reader.
     * Metadata transactions take operation → buyer and never wait for source mutexes. The kernel
     * still locks and validates the current active buyer before any CMS/provenance mutation. */
    private PaidProfessionalOperationRow locked(long actor,PaidProfessionalMaterializationCommand command){
        persistence.fenceCurrentTransaction();PaidProfessionalOperationRow row=store.lock(actor,command.ownerRequestKey(actor));
        PaidProfessionalMaterializationCommand stored=verify(actor,command.ownerRequestKey(actor),row);require(stored.digest().equals(command.digest()));return row;
    }
    private PaidProfessionalMaterializationCommand verify(long actor,String key,PaidProfessionalOperationRow row){
        require(row!=null&&Long.valueOf(actor).equals(row.getOwnerMemberId())&&key.equals(row.getOperationKey())
                &&row.getCommandData()!=null&&row.getCommandBytes()!=null&&row.getCommandBytes()>0&&row.getCommandBytes()<=4096&&row.getCommandBytes()==row.getCommandData().length
                &&row.getCreatedAt()!=null&&row.getUpdatedAt()!=null&&!row.getUpdatedAt().before(row.getCreatedAt())
                &&Arrays.asList("PREPARED","CREATED","REJECTED","CANCELLED").contains(row.getState()));
        PaidProfessionalMaterializationCommand c=PaidProfessionalOperationCodec.decode(row.getCommandData());
        require(key.equals(c.ownerRequestKey(actor))&&c.requestId.equals(row.getRequestId())&&c.digest().equals(row.getCommandHash()));
        require("REJECTED".equals(row.getState())?validRejection(row.getRejectionCode()):row.getRejectionCode()==null);return c;
    }
    private void finish(long actor,PaidProfessionalMaterializationCommand c,String state,String reason){
        persistence.fenceCurrentTransaction();require(Arrays.asList("CREATED","REJECTED","CANCELLED").contains(state));
        require(store.finish(actor,c.ownerRequestKey(actor),c.digest(),state,reason,at())==1);
        require(state.equals(locked(actor,c).getState()));
    }
    private Timestamp at(){return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));}
    private static boolean created(Map<String,Object> history){return "CREATION_COMMITTED".equals(history.get("state"));}
    private static String rejectionCode(IllegalArgumentException error){
        String message=error.getMessage();if(message!=null){
            for(String field:Arrays.asList("title","description","ruleInstructions","merchantGuide","questionName","questionAnswer","hint1","hint2","answerReveal"))
                if(message.startsWith("W18_MATERIALIZATION_FIELD_REJECTED:"+field+":"))return "SOURCE_FIELD_REJECTED_"+field;
        }return "CURRENT_SOURCE_OR_TARGET_UNAVAILABLE";
    }
    private static boolean validRejection(String code){return "CURRENT_SOURCE_OR_TARGET_UNAVAILABLE".equals(code)
            ||code!=null&&code.matches("SOURCE_FIELD_REJECTED_(title|description|ruleInstructions|merchantGuide|questionName|questionAnswer|hint1|hint2|answerReveal)");}
    private static final class Execution {
        final String state;final Map<String,Object> receipt;final IllegalArgumentException rejected;
        Execution(String state,Map<String,Object> receipt){this(state,receipt,null);}
        Execution(String state,Map<String,Object> receipt,IllegalArgumentException rejected){this.state=state;this.receipt=receipt;this.rejected=rejected;}
    }
    /** Private construction plus the actual transaction resource make this a programming boundary,
     * not a cryptographic grant. CMS permission is still resolved by the original kernel. */
    static final class Permit {
        private final PaidPersistenceBoundary boundary;private final Object transaction;private final long actor;private final String digest;private final boolean create;
        private Permit(PaidPersistenceBoundary p,long actor,PaidProfessionalMaterializationCommand c,boolean create){boundary=p;transaction=p.currentTransactionIdentity();this.actor=actor;digest=c.digest();this.create=create;}
        void validate(PaidPersistenceBoundary p,long actor,PaidProfessionalMaterializationCommand c){require(p==boundary&&actor==this.actor&&digest.equals(c.digest()));p.requireTransactionIdentity(transaction);}
        void requireCreation(){require(create);}
    }
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("PAID_PROFESSIONAL_OPERATION_UNAVAILABLE_OR_CONFLICT");}
}
