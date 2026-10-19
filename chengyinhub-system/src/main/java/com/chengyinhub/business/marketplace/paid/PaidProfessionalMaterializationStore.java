package com.chengyinhub.business.marketplace.paid;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** NEW destination capture only. Existing CMS tables are read/locked, never mutated here. */
public interface PaidProfessionalMaterializationStore {
    PaidProfessionalTargetStore.Row lockTopic(@Param("actor")long actor,@Param("topic")long topic);
    List<PaidProfessionalTargetStore.Row> lockContentRoles(@Param("topic")long topic);
    DraftRow lockCurrentDraft(@Param("actor")long actor,@Param("draft")long draft);
    final class DraftRow {
        private Long id;public Long getId(){return id;}public void setId(Long v){id=v;}
        private Long ownerMemberId;public Long getOwnerMemberId(){return ownerMemberId;}public void setOwnerMemberId(Long v){ownerMemberId=v;}
        private Long version;public Long getVersion(){return version;}public void setVersion(Long v){version=v;}
        private String businessType;public String getBusinessType(){return businessType;}public void setBusinessType(String v){businessType=v;}
        private String status;public String getStatus(){return status;}public void setStatus(String v){status=v;}
        private String payloadHash;public String getPayloadHash(){return payloadHash;}public void setPayloadHash(String v){payloadHash=v;}
    }
}
