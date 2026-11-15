package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.domain.AjaxResult;

/** The channel event route reads the existing activity publish answer: an id, or the host-facing refusal. */
class ApiChannelEventControllerTest
{
    @Test
    void takesTheActivityIdFromASuccessfulPublish()
    {
        assertEquals(900L, ApiChannelEventController.publishedActivityId(AjaxResult.success("活动发布成功", 900L)));
        assertEquals(901L, ApiChannelEventController.publishedActivityId(AjaxResult.success("活动发布成功，请等待审核", "901")));
    }

    @Test
    void passesThePublishRefusalThroughAsAStableError()
    {
        ChannelRejection r = assertThrows(ChannelRejection.class,
                () -> ApiChannelEventController.publishedActivityId(AjaxResult.error("仅俱乐部主理人或商家可发布活动")));
        assertEquals(409, r.getStatus());
        assertEquals("EVENT_PUBLISH_REFUSED", r.getErrorCode());
        assertEquals("仅俱乐部主理人或商家可发布活动", r.getMessage());
        assertEquals("EVENT_PUBLISH_REFUSED",
                assertThrows(ChannelRejection.class, () -> ApiChannelEventController.publishedActivityId(null)).getErrorCode());
        assertEquals("EVENT_PUBLISH_REFUSED", assertThrows(ChannelRejection.class,
                () -> ApiChannelEventController.publishedActivityId(AjaxResult.success("ok", "abc"))).getErrorCode());
    }
}
