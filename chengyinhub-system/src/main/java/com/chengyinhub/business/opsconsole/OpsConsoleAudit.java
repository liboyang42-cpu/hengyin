package com.chengyinhub.business.opsconsole;

import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R10 运营控制台写操作的审计：每一次后台改动追加一条 business_audit_event（domain=PLATFORM，actor=SYS_USER）。
 * 与 RuoYi @Log(sys_oper_log) 并存：@Log 记请求，这里记业务前后态，平台审计页（/business/audit-log）可直接查到。
 * 调用方与业务写在同一事务里：审计写不进去则整笔回滚。
 */
@Component
public class OpsConsoleAudit
{
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private BusinessAuditEventMapper mapper;

    public OpsConsoleAudit() {}

    OpsConsoleAudit(BusinessAuditEventMapper mapper) { this.mapper = mapper; }

    public void record(Long sysUserId, String action, String targetType, Long targetId,
                       Object before, Object after, String reason)
    {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("PLATFORM");
        event.setDomainId(0L);
        event.setOwnerMemberId(0L);
        event.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        event.setActorMemberId(sysUserId);
        event.setActorRoleCode("SYS_USER");
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId == null ? 0L : targetId);
        event.setBeforeJson(json(before));
        event.setAfterJson(json(after));
        event.setReason(reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason));
        event.setRequestId("ops:" + UUID.randomUUID().toString().replace("-", ""));
        event.setCreateTime(new Date());
        if (mapper.insertBusinessAuditEvent(event) != 1) throw new IllegalStateException("审计写入失败");
    }

    static String json(Object value)
    {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("审计内容无法序列化", e); }
    }
}
