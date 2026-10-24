package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
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
        ReflectionTestUtils.setField(controller, "playCheckinCodeService", liveCodes);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        CurrentPlayControllerFixtures.install(controller);
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
    void duplicateStaticCodeIsRejectedBeforeAnyContextLookup() {
        when(nodeService.selectCmsTopicNodeList(any()))
                .thenReturn(Arrays.asList(node(77L, 44L), node(78L, 45L)));

        AjaxResult r = controller.checkin("43", STATIC, null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("该打卡码对应多个站点，请联系商家处理", r.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(activityService);
    }
}
