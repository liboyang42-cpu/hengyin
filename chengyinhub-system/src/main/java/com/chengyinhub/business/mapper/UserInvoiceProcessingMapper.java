package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface UserInvoiceProcessingMapper {
    List<Long> refundPauseCandidates(@Param("after") long after);
    int reserveIssuedReference(@Param("id") Long id, @Param("issuer") Long issuer, @Param("invoiceNo") String invoiceNo);
    List<Long> listScoped(@Param("actor") Long actor, @Param("before") Long before);
    Map<String,Object> scopeRead(@Param("id") Long id, @Param("actor") Long actor);
    Map<String,Object> scope(@Param("id") Long id, @Param("actor") Long actor);
    Map<String,Object> lockProcessing(@Param("id") Long id);
    UserInvoiceApplication lockApplication(@Param("id") Long id);
    int transition(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                   @Param("invoiceNo") String invoiceNo, @Param("reason") String reason);
    int advance(@Param("id") Long id, @Param("version") long version, @Param("handler") Long handler,
                @Param("operation") String operation, @Param("reconciliation") String reconciliation,
                @Param("evidence") String evidence);
    Map<String,Object> receipt(@Param("id") Long id, @Param("actor") Long actor, @Param("request") String request);
    int audit(@Param("id") Long id, @Param("actor") Long actor, @Param("request") String request,
              @Param("fingerprint") String fingerprint, @Param("action") String action,
              @Param("status") String status, @Param("version") long version, @Param("evidence") String evidence);
    List<Long> affectedApplications(@Param("registrationIds") List<Long> registrationIds);
}
