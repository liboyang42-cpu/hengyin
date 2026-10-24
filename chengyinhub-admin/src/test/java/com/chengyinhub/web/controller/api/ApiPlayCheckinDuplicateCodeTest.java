package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService.ConsumeResult;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P0-26:一个静态打卡码命中多个节点时拒绝打卡,不静默取第一条。 */
class ApiPlayCheckinDuplicateCodeTest {

    private static final String STATIC = "STORE-CODE";

    private ICmsTopicNodeService nodeService;
    private ICmsActivityService activityService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        IPlayCheckinCodeService liveCodes = mock(IPlayCheckinCodeService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        activityService = mock(ICmsActivityService.class);
        controller = spy(new ApiPlayProgressController());
        doReturn(41L).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCheckinCodeService", liveCodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        when(nodeService.selectCmsTopicNodeById(77L)).thenReturn(node(77L,44L));
        CmsActivity activity = new CmsActivity(); activity.setId(43L); activity.setTopicId(44L);
        when(activityService.selectCmsActivityById(43L)).thenReturn(activity);
        CmsRegistration ticket = new CmsRegistration(); ticket.setId(91L); ticket.setMemberId(41L);
        ticket.setOwnerType(2L); ticket.setOwnerId(43L); ticket.setRegistrationStatus(2);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(java.util.Collections.singletonList(ticket));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsRegistrationService",registrations);
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller,44L);
        when(liveCodes.inspect(STATIC)).thenReturn(ConsumeResult.invalid());
    }

    private static CmsTopicNode node(long id, long topicId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(topicId);
        n.setCheckinCode(STATIC);
        return n;
    }

    @Test
    void duplicateStaticCodeIsRejectedAfterOriginalTargetAuthorizationWithoutProgressWrites() {
        when(nodeService.selectCmsTopicNodeList(any()))
                .thenReturn(Arrays.asList(node(77L, 44L), node(78L, 45L)));

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",STATIC,null,null,null,null,"77");

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("该打卡码对应多个站点，请联系商家处理", r.get(AjaxResult.MSG_TAG));
        org.mockito.Mockito.verify((com.chengyinhub.business.mapper.CmsActivityMapper)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"currentPlayActivityMapper"),org.mockito.Mockito.never()).selectCmsActivityByIdForUpdate(any());
    }
}
