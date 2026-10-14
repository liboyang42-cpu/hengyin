package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 非资格测试的明确已付原对象夹具；资格负控须覆盖这些默认事实，不改生产门禁。 */
public final class CurrentPlayTestFixtures {
    private CurrentPlayTestFixtures() { }
    /**
     * 资格类测试用：新锁读只委托到测试自己在同一 mapper 上声明的活动/报名夹具，不额外授予资格。
     * 报名锁读以最近一次列表查询的 probe 重查该夹具并按原ID取行；未写出的归属列按 probe 补齐。
     */
    public static void delegateLocks(CmsActivityMapper activities,CmsRegistrationMapper registrations) {
        when(activities.selectCmsActivityByIdForUpdate(anyLong()))
                .thenAnswer(call -> activities.selectCmsActivityById(call.getArgument(0)));
        when(registrations.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(call -> {
            Long id=call.getArgument(0);CmsRegistration probe=null;
            for(org.mockito.invocation.Invocation invocation:org.mockito.Mockito.mockingDetails(registrations).getInvocations()) {
                if("selectCmsRegistrationList".equals(invocation.getMethod().getName())) probe=invocation.getArgument(0);
            }
            if(probe==null) return null;
            java.util.List<CmsRegistration> rows=registrations.selectCmsRegistrationList(probe);
            if(rows!=null) for(CmsRegistration row:rows) {
                if(row==null || !id.equals(row.getId())) continue;
                if(row.getMemberId()==null) row.setMemberId(probe.getMemberId());
                if(row.getOwnerType()==null) row.setOwnerType(probe.getOwnerType());
                if(row.getOwnerId()==null) row.setOwnerId(probe.getOwnerId());
                if(row.getRegistrationStatus()==null) row.setRegistrationStatus(probe.getRegistrationStatus());
                if(row.getPaymentStatus()==null) row.setPaymentStatus(probe.getPaymentStatus());
                return row;
            }
            return null;
        });
    }

    /** 按服务实例的 activityMapper/registrationMapper 字段安装；测试未注入时补同名 mock。 */
    public static void installOn(Object service) {
        CmsActivityMapper activities=(CmsActivityMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"activityMapper");
        if(activities==null) {
            activities=org.mockito.Mockito.mock(CmsActivityMapper.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"activityMapper",activities);
        }
        CmsRegistrationMapper registrations=(CmsRegistrationMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"registrationMapper");
        if(registrations==null) {
            registrations=org.mockito.Mockito.mock(CmsRegistrationMapper.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"registrationMapper",registrations);
        }
        install(activities,registrations);
    }
    public static void install(CmsActivityMapper activities,CmsRegistrationMapper registrations) {
        AtomicReference<CmsRegistration> selected=new AtomicReference<>();
        when(activities.selectCmsActivityByIdForUpdate(anyLong())).thenAnswer(call -> activities.selectCmsActivityById(call.getArgument(0)));
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(call -> {
            CmsRegistration probe=call.getArgument(0),row=new CmsRegistration();row.setId(9000001L);
            row.setMemberId(probe.getMemberId());row.setOwnerType(probe.getOwnerType());row.setOwnerId(probe.getOwnerId());
            row.setPaymentStatus(2);row.setRegistrationStatus(2);row.setPurchaseKind(1);
            selected.set(row);return Collections.singletonList(row);
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(9000001L)).thenAnswer(call -> selected.get());
    }
}
