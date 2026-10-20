package com.chengyinhub.business.marketplace.paid;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** NEW read adapter: MEMBER content ownership and legacy publisher-edit scope must BOTH match. */
public interface PaidProfessionalTargetStore {
    List<Row> list(@Param("actor")long actor,@Param("before")Long before);
    List<Row> capture(@Param("actor")long actor,@Param("topic")long topic);
    final class Row {
        private String roleSlot;public String getRoleSlot(){return roleSlot;}public void setRoleSlot(String v){roleSlot=v;}
        private Long id;public Long getId(){return id;}public void setId(Long v){id=v;}
        private Long publisherMemberId;public Long getPublisherMemberId(){return publisherMemberId;}public void setPublisherMemberId(Long v){publisherMemberId=v;}
        private String name;public String getName(){return name;}public void setName(String v){name=v;}
        private Integer productType;public Integer getProductType(){return productType;}public void setProductType(Integer v){productType=v;}
        private Long configVersion;public Long getConfigVersion(){return configVersion;}public void setConfigVersion(Long v){configVersion=v;}
        private Integer delFlag;public Integer getDelFlag(){return delFlag;}public void setDelFlag(Integer v){delFlag=v;}
        private Long roleId;public Long getRoleId(){return roleId;}public void setRoleId(Long v){roleId=v;}
        private Long roleTopicId;public Long getRoleTopicId(){return roleTopicId;}public void setRoleTopicId(Long v){roleTopicId=v;}
        private String entityType;public String getEntityType(){return entityType;}public void setEntityType(String v){entityType=v;}
        private Long entityId;public Long getEntityId(){return entityId;}public void setEntityId(Long v){entityId=v;}
        private Integer roleStatus;public Integer getRoleStatus(){return roleStatus;}public void setRoleStatus(Integer v){roleStatus=v;}
        private Long declaredBy;public Long getDeclaredBy(){return declaredBy;}public void setDeclaredBy(Long v){declaredBy=v;}
        private java.sql.Timestamp createdAt;public java.sql.Timestamp getCreatedAt(){return createdAt;}public void setCreatedAt(java.sql.Timestamp v){createdAt=v;}
    }
}
