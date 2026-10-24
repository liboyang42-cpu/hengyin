package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** A包当前资格改为「快照列表 + 原ID锁读当前行」；玩法单测按查询条件回一张当前有效的原报名。 */
public final class CurrentRegistrationStubs {
    private CurrentRegistrationStubs() { }

    public static void currentFromProbe(CmsRegistrationMapper registrations) {
        Map<List<Long>, CmsRegistration> byOwner = new ConcurrentHashMap<>();
        Map<Long, CmsRegistration> byId = new ConcurrentHashMap<>();
        AtomicLong nextId = new AtomicLong();
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            if (probe == null) return new ArrayList<CmsRegistration>(); // when(...) 重设 stub 时的空参调用
            List<Long> owner = Arrays.asList(probe.getMemberId(), probe.getOwnerType(), probe.getOwnerId());
            CmsRegistration row = byOwner.computeIfAbsent(owner, key -> {
                CmsRegistration current = new CmsRegistration();
                current.setId(nextId.incrementAndGet()); current.setMemberId(key.get(0)); current.setOwnerType(key.get(1));
                current.setOwnerId(key.get(2)); current.setRegistrationStatus(2); current.setPaymentStatus(2);
                // 原 ID 始终读回自己的行，不能被另一成员/活动的并发查询覆盖。
                byId.put(current.getId(), current);
                return current;
            });
            List<CmsRegistration> rows = new ArrayList<>();
            rows.add(row);
            return rows;
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            return id == null ? null : byId.get(id);
        });
    }
}
