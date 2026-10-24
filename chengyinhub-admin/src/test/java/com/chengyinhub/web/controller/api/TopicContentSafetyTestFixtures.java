package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * AUDIT-P1:主题文本改走统一门禁(POST_REVIEW)后,控制器测试需要一扇真门禁包住各自的机审 mock。
 * 机审开启;操作者有 openid(否则门禁按「缺身份」打标放行,测不到违规拦截)。
 */
public final class TopicContentSafetyTestFixtures {

    private TopicContentSafetyTestFixtures() {
    }

    public static IAuditTaskService wire(ApiTopicController controller, IWxContentSecurityService security) {
        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(gate, "auditTaskService", mock(IAuditTaskService.class));
        when(security.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
        Object members = ReflectionTestUtils.getField(controller, "umsMemberService");
        if (members != null && mockingDetails(members).isMock() && mockingDetails(members).getStubbings().stream()
                .noneMatch(stub -> "selectUmsMemberById".equals(stub.getInvocation().getMethod().getName()))) {
            UmsMember actor = new UmsMember();
            actor.setWxOpenId("openid-test");
            when(((IUmsMemberService) members).selectUmsMemberById(any())).thenReturn(actor);
        }
        return auditTaskService;
    }
}
