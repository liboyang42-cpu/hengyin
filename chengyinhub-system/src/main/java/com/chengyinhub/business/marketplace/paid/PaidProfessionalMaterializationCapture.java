package com.chengyinhub.business.marketplace.paid;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** NEW transaction-local target capture. It does not insert a template or claim a full topic graph
 * CAS. First binding attaches a consumed license/theme scope to one explicitly chosen real topic;
 * later publication still needs the registered rights-aware W02 snapshot/adapter. */
final class PaidProfessionalMaterializationCapture {
    private final PaidPersistenceBoundary persistence;
    private final java.time.Clock clock;
    private final PaidProfessionalMaterializationStore store;
    PaidProfessionalMaterializationCapture(PaidPersistenceBoundary persistence,java.time.Clock clock){this.persistence=Objects.requireNonNull(persistence);this.clock=Objects.requireNonNull(clock);store=persistence.mapper(PaidProfessionalMaterializationStore.class);}
    /** Lock actual topic then its existing CONTENT row before paid source locks. No new role or
     * owner fallback is created. This ordering also permits a future rights-aware reviewer to
     * lock topic before license and template; it must not invert license/template locks. */
    Topic lockTopic(long actor,PaidProfessionalMaterializationCommand command){
        persistence.fenceCurrentTransaction();require(actor>0&&command!=null);
        PaidProfessionalTargetStore.Row topic=store.lockTopic(actor,command.targetTopicId);
        require(topic!=null&&Long.valueOf(command.targetTopicId).equals(topic.getId())&&Long.valueOf(actor).equals(topic.getPublisherMemberId())
                &&Integer.valueOf(0).equals(topic.getDelFlag())&&topic.getName()!=null&&topic.getName().length()<=256&&topic.getName().getBytes(StandardCharsets.UTF_8).length<=1024
                &&(topic.getConfigVersion()==null||topic.getConfigVersion()>=0));
        List<PaidProfessionalTargetStore.Row> roles=store.lockContentRoles(command.targetTopicId);require(roles!=null&&roles.size()==1);
        PaidProfessionalTargetStore.Row role=roles.get(0);require(role!=null&&role.getRoleId()!=null&&role.getRoleId()>0
                &&Long.valueOf(command.targetTopicId).equals(role.getRoleTopicId())&&"CONTENT".equals(role.getRoleSlot())
                &&"MEMBER".equals(role.getEntityType())&&Long.valueOf(actor).equals(role.getEntityId())&&Integer.valueOf(1).equals(role.getRoleStatus()));
        topic.setRoleId(role.getRoleId());topic.setRoleTopicId(role.getRoleTopicId());topic.setRoleSlot(role.getRoleSlot());topic.setEntityType(role.getEntityType());topic.setEntityId(role.getEntityId());topic.setRoleStatus(role.getRoleStatus());topic.setDeclaredBy(role.getDeclaredBy());topic.setCreatedAt(role.getCreatedAt());
        Integer mode=command.confirmedMode==PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION?1:2;
        require(mode.equals(topic.getProductType())&&command.targetOwnerModeFingerprint.equals(PaidProfessionalTargetReader.fingerprint(topic)));
        return new Topic(topic.getId(),role.getRoleId(),topic.getConfigVersion(),command.confirmedMode,command.targetOwnerModeFingerprint);
    }
    /** Generic draft CAS is checked separately and no payloadJson is materialized into CMS.
     * Only TOPIC-container → CMS_TOPIC is registered by this proposed first adapter. ACTIVITY
     * remains a supported installed copy, but its professional destination mapping is unregistered. */
    Draft lockCurrentDraft(long actor,PaidProfessionalMaterializationCommand command,PaidInstalledTextReader.Captured source){
        persistence.fenceCurrentTransaction();source.requireCurrent(persistence,clock.instant());command.requireSource(source);require(source.actor==actor);
        PaidProfessionalMaterializationStore.DraftRow row=store.lockCurrentDraft(actor,source.intent.targetDraftId);
        require(row!=null&&Long.valueOf(source.intent.targetDraftId).equals(row.getId())&&Long.valueOf(actor).equals(row.getOwnerMemberId())
                &&"DRAFT".equals(row.getStatus())&&"TOPIC".equals(row.getBusinessType())&&row.getBusinessType().equals(source.intent.businessType)
                &&Long.valueOf(command.currentDraftRevision).equals(row.getVersion())&&command.currentDraftPayloadHash.equals(row.getPayloadHash()));
        // This is the existing opaque version/hash CAS, not a raw JSON byte hash or a professional
        // payload schema. MySQL JSON storage may normalize representation. We neither fetch nor
        // materialize that JSON; the verified installed component is the sole template source.
        return new Draft(row.getId(),row.getVersion(),row.getPayloadHash());
    }
    static final class Topic {
        final long id,contentRoleId;final Long routeConfigVersion;final PaidProfessionalMaterializationCommand.Mode mode;final String ownerModeFingerprint;
        private Topic(long id,long contentRoleId,Long routeConfigVersion,PaidProfessionalMaterializationCommand.Mode mode,String fingerprint){this.id=id;this.contentRoleId=contentRoleId;this.routeConfigVersion=routeConfigVersion;this.mode=mode;ownerModeFingerprint=fingerprint;}
    }
    static final class Draft {
        final long id,revision;final String payloadHash;
        private Draft(long id,long revision,String payloadHash){this.id=id;this.revision=revision;this.payloadHash=payloadHash;}
    }
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("PAID_MATERIALIZATION_TARGET_CHANGED_OR_UNSUPPORTED");}
}
