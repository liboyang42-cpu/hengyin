package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 包把 ApiPlayProgressController 的新使用资格改为 activity→registration 当前锁读
 * (CurrentPlayRegistration)。旧控制器单测只在 service 层夹具上声明活动/报名事实。
 * 本夹具让新锁读委托到同一 service 夹具：返回的就是测试自己声明的活动与报名行，
 * 不额外授予资格；查询结果按 probe 过滤条件补齐归属列(真实库里被该 where 查出的行必然满足)。
 */
final class CurrentPlayControllerFixtures {
    private static final long SYNTHETIC_ID_BASE = 7_000_000_000L;

    private CurrentPlayControllerFixtures() { }

    static void install(Object controller) {
        if (ReflectionTestUtils.getField(controller, "currentPlayActivityMapper") == null) {
            CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
            when(activityMapper.selectCmsActivityByIdForUpdate(anyLong())).thenAnswer(call -> {
                // 调用时取控制器当前 service：用例方法可能在 setUp 之后换掉 service 夹具。
                ICmsActivityService activities = (ICmsActivityService) ReflectionTestUtils.getField(controller, "cmsActivityService");
                return activities == null ? null : activities.selectCmsActivityById(call.<Long>getArgument(0));
            });
            ReflectionTestUtils.setField(controller, "currentPlayActivityMapper", activityMapper);
        }
        CmsRegistrationMapper existing = (CmsRegistrationMapper) ReflectionTestUtils.getField(controller, "cmsRegistrationMapper");
        if (existing == null) {
            ReflectionTestUtils.setField(controller, "cmsRegistrationMapper",
                    delegate(mock(CmsRegistrationMapper.class), controller));
        } else {
            // 测试自带的 mapper mock：只补两条锁读委托；用例方法里后写的同名 stub 仍优先。
            delegate(existing, controller);
        }
    }

    static CmsRegistrationMapper delegate(CmsRegistrationMapper mapper, Object controller) {
        List<CmsRegistration> lastRows = new ArrayList<>();
        when(mapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            ICmsRegistrationService registrations =
                    (ICmsRegistrationService) ReflectionTestUtils.getField(controller, "cmsRegistrationService");
            List<CmsRegistration> rows = registrations == null ? null : registrations.selectCmsRegistrationList(probe);
            lastRows.clear();
            if (rows != null) for (CmsRegistration row : rows) if (row != null) lastRows.add(asQueried(row, probe));
            return rows == null ? null : new ArrayList<>(lastRows);
        });
        when(mapper.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            for (CmsRegistration row : lastRows) if (Objects.equals(id, row.getId())) return row;
            return null;
        });
        return mapper;
    }

    /** 被 probe 的 where 条件查出的行必然满足这些列；夹具未显式写出的才补，测试已写的值保持不变。 */
    private static CmsRegistration asQueried(CmsRegistration row, CmsRegistration probe) {
        if (row.getId() == null) row.setId(SYNTHETIC_ID_BASE + System.identityHashCode(row)); // 真实行必有主键
        if (row.getMemberId() == null) row.setMemberId(probe.getMemberId());
        if (row.getOwnerType() == null) row.setOwnerType(probe.getOwnerType());
        if (row.getOwnerId() == null) row.setOwnerId(probe.getOwnerId());
        if (row.getRegistrationStatus() == null) row.setRegistrationStatus(probe.getRegistrationStatus());
        if (row.getPaymentStatus() == null) row.setPaymentStatus(probe.getPaymentStatus());
        return row;
    }
}
