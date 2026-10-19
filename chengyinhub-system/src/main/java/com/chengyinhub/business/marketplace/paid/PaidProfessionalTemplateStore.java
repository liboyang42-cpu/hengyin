package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import org.apache.ibatis.annotations.Param;

/** NEW dedicated writer. No generic CMS mapper, update, delete, adoption or upsert is exposed. */
public interface PaidProfessionalTemplateStore {
    PaidProfessionalProvenanceRow findRequest(@Param("key")String key);
    PaidProfessionalProvenanceRow findBinding(@Param("key")String key);
    int insertMarkedTemplate(CmsMemberTemplate row);
    TemplateGuard lockTemplateGuard(@Param("actor")long actor,@Param("id")long id);
    CmsMemberTemplate readTemplate(@Param("actor")long actor,@Param("id")long id);
    int insertProvenance(PaidProfessionalProvenanceRow row);
    final class TemplateGuard {
        private String marker;public String getMarker(){return marker;}public void setMarker(String v){marker=v;}
        private Long characters;public Long getCharacters(){return characters;}public void setCharacters(Long v){characters=v;}
    }
}
